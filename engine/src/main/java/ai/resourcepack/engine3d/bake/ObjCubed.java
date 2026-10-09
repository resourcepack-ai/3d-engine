package ai.resourcepack.engine3d.bake;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.CRC32;
import java.util.zip.Deflater;

/**
 * The obj³ encoder: a mesh and its animation frames packed into one texture
 * that obj³'s core shaders turn back into the mesh, and the item model that
 * carries it (one flat element per face, each pointing at its own header
 * pixel).
 *
 * A port of the encoder in obj³'s Blockbench plugin
 * (github.com/JagerMeistars/obj-cubed, MIT, Copyright (c) 2022 Godlander), cut
 * down to what an item display needs: one texture, no texture animation, no
 * per-slot display transforms. The texture layout is obj³'s, byte for byte,
 * because the shaders are obj³'s:
 * <pre>
 *   row 0        header (marker, sizes, counts, clock, flags, GUI transform)
 *   row 1        texture clock and display-slot table
 *   uvH rows     one pixel per face: where its element's UV points
 *   texH rows    the texture itself, flipped
 *   vpH rows     positions, 3 pixels each (24-bit fixed point, 1/65536 block)
 *   vtH rows     UVs, 2 pixels each
 *   vH rows      per frame, per face corner: (position index, uv index)
 * </pre>
 */
public final class ObjCubed {

    private ObjCubed() {
    }

    /** RGBA, rows top to bottom, as a PNG holds it. */
    public record Image(int width, int height, byte[] rgba) {
    }

    public record Result(byte[] png, int width, int height, int faces, int frames, JsonArray elements) {
        /** The item model, its texture named {@code textureRef} (e.g. {@code 3dengine:item/dog/idle}). */
        public String model(String textureRef) {
            return slotModel(textureRef, null, null);
        }

        /**
         * The model a held item uses in display context {@code slot} (null for
         * the neutral one every other context falls back to), with
         * {@code transform} (rotation, translation, scale: a block model's
         * display entry) for that context.
         *
         * obj³'s per-slot calibration: dropped and shelved items sit half a
         * block lower than everything else, so their carrier elements are
         * raised 8 units; and the slots that need the shader to know which
         * context it is in (both first-person hands, ground, shelf) carry a
         * marker id in the midpoint of every carrier face's U range, which
         * indexes the table row 1 of the texture holds.
         */
        public String slotModel(String textureRef, String slot, double[][] transform) {
            JsonObject textures = new JsonObject();
            textures.addProperty("0", textureRef);
            textures.addProperty("particle", textureRef);
            JsonObject display = new JsonObject();
            for (String s : SLOTS) display.add(s, display(s.equals(slot) ? transform : null));
            JsonObject model = new JsonObject();
            model.add("textures", textures);
            model.add("elements", slot == null ? elements : calibrated(slot));
            model.add("display", display);
            return model.toString();
        }

        private JsonArray calibrated(String slot) {
            int lift = slot.equals("ground") || slot.equals("on_shelf") ? 8 : 0;
            int id = switch (slot) {
                case "firstperson_righthand" -> 1;
                case "firstperson_lefthand" -> 2;
                case "ground" -> 3;
                case "on_shelf" -> 4;
                default -> 0;
            };
            if (lift == 0 && id == 0) return elements;
            JsonArray out = new JsonArray();
            for (var e : elements) {
                JsonObject el = e.getAsJsonObject().deepCopy();
                if (lift != 0) {
                    for (String k : new String[]{"from", "to"}) {
                        JsonArray v = el.getAsJsonArray(k);
                        v.set(1, new com.google.gson.JsonPrimitive(v.get(1).getAsDouble() + lift));
                    }
                }
                if (id != 0) {
                    JsonObject north = el.getAsJsonObject("faces").getAsJsonObject("north");
                    JsonArray uv = north.getAsJsonArray("uv");
                    double u0 = uv.get(0).getAsDouble(), u1 = uv.get(2).getAsDouble();
                    double texel = (u1 - u0) / 0.8;
                    double mid = u0 - 0.1 * texel + (0.5 + id * 0.035) * texel;
                    uv.set(0, new com.google.gson.JsonPrimitive(mid - 0.2 * texel));
                    uv.set(2, new com.google.gson.JsonPrimitive(mid + 0.2 * texel));
                }
                out.add(el);
            }
            return out;
        }

        private static JsonObject display(double[][] t) {
            JsonObject d = new JsonObject();
            d.add("rotation", array(t != null ? t[0] : new double[]{0, 0, 0}));
            d.add("translation", array(t != null ? t[1] : new double[]{0, 0, 0}));
            d.add("scale", array(t != null ? t[2] : new double[]{1, 1, 1}));
            return d;
        }
    }

    /** The display contexts a held item has a model of its own for. */
    public static final String[] SLOTS = {"thirdperson_righthand", "thirdperson_lefthand", "firstperson_righthand", "firstperson_lefthand", "head", "ground", "fixed", "on_shelf"};

    /** How the shader reads its clock from the item's tint colour. */
    public enum Clock {
        /** Loops on the client's game time from frame 0, whatever the colour. */
        DIRECT(0),
        /** The colour is the clock: an offset for a loop, or a start tick with bit 15 for a one-shot (see Animator). */
        TIME(1);

        final int code;

        Clock(int code) {
            this.code = code;
        }
    }

    /**
     * Encodes {@code frames} (per frame, a position per vertex, in blocks, the
     * model's floor at y=0 one block below the display's... see the placement
     * in {@link Baker}) of a mesh with {@code faces} (3 or 4 vertex indices)
     * and {@code uvs} (v up), textured by {@code texture}, playing over
     * {@code ticks}.
     */
    public static Result encode(List<double[][]> frames, double[][] uvs, int[][] faces, Image texture, int ticks, Clock clock) {
        return encode(frames, uvs, faces, texture, ticks, clock, 1);
    }

    /** As above, drawn {@code iconScale} times its size as an inventory icon (obj³ allows 0 to 4). */
    public static Result encode(List<double[][]> frames, double[][] uvs, int[][] faces, Image texture, int ticks, Clock clock, double iconScale) {
        int nframes = frames.size();
        int nfaces = faces.length;
        if (nfaces == 0) throw new IllegalArgumentException("No faces");
        int tw = texture.width, th = texture.height;
        if (tw < 16) throw new IllegalArgumentException("The texture must be at least 16 pixels wide");

        // Index positions and UVs, deduplicated as obj³ does (by value).
        List<double[]> positions = new ArrayList<>();
        List<double[]> uvList = new ArrayList<>();
        Map<String, Integer> posIndex = new HashMap<>();
        Map<String, Integer> uvIndex = new HashMap<>();
        int nvertices = nfaces * 4;
        int[] vertPos = new int[nvertices * nframes];
        int[] vertUv = new int[nvertices * nframes];
        int vi = 0;
        String[] uvKey = new String[uvs.length];
        double[][] roundedUv = new double[uvs.length][];
        for (int i = 0; i < uvs.length; i++) {
            double u = round(uvs[i][0], 6), v = round(uvs[i][1], 6);
            roundedUv[i] = new double[]{u, v};
            uvKey[i] = String.format("%.8f,%.8f", u, v);
        }
        for (double[][] frame : frames) {
            for (int[] face : faces) {
                int n = Math.min(4, face.length);
                for (int k = 0; k < 4; k++) {
                    int v = face[k < n ? k : n - 1];
                    double[] p = frame[v];
                    double x = round(p[0], 5), y = round(p[1], 5), z = round(p[2], 5);
                    String pk = x + "," + y + "," + z;
                    Integer pi = posIndex.get(pk);
                    if (pi == null) {
                        pi = positions.size();
                        posIndex.put(pk, pi);
                        positions.add(new double[]{x, y, z});
                    }
                    Integer ui = uvIndex.get(uvKey[v]);
                    if (ui == null) {
                        ui = uvList.size();
                        uvIndex.put(uvKey[v], ui);
                        uvList.add(roundedUv[v]);
                    }
                    vertPos[vi] = pi;
                    vertUv[vi] = ui;
                    vi++;
                }
            }
        }

        int headerRows = 2;
        int uvH = ceilDiv(nfaces, tw);
        int texH = th;
        int vpH = ceilDiv(positions.size() * 3, tw);
        int vtH = ceilDiv(uvList.size() * 2, tw);
        int vH = ceilDiv(vi * 2, tw);
        for (double[] p : positions)
            for (int a = 0; a < 3; a++) {
                double w = p[a] - (a == 1 ? 0.5 : 0);
                if (!(w >= -128 && w < 128)) throw new IllegalArgumentException("The model reaches past 128 blocks from its centre");
            }
        if (positions.size() > 16777215 || uvList.size() > 16777215) throw new IllegalArgumentException("Too many vertices");
        if (vpH > 65535 || vtH > 65535) throw new IllegalArgumentException("Too much data for the texture width: use fewer frames or triangles");
        int rows = headerRows + uvH + texH + vpH + vtH + vH;
        // obj³ pads to a power of two; a multiple of 16 is enough (the header
        // records the data height) and keeps the items atlas from doubling.
        int ty = ceilDiv(rows, 16) * 16;

        byte[] buf = new byte[tw * ty * 4];
        Put put = (x, y, r, g, b, a) -> {
            if (x < 0 || x >= tw || y < 0 || y >= ty) return;
            int i = (y * tw + x) * 4;
            buf[i] = (byte) r;
            buf[i + 1] = (byte) g;
            buf[i + 2] = (byte) b;
            buf[i + 3] = (byte) a;
        };

        int cb = (clock.code << 6) | (clock.code << 3) | clock.code;
        int dur = ticks == 0 ? nframes : ticks;
        int easing = 1, interpolation = 1, autorotate = 3, visibility = 7;
        boolean autoplay = true;
        int frameH = th, ntextures = 1;

        // Row 0: the header.
        put.at(0, 0, 12, 34, 56, 255);
        put.at(1, 0, tw / 256, tw % 256, frameH / 256, 255);
        put.at(2, 0, (nvertices / 16777216) % 256, (nvertices / 65536) % 256, (nvertices / 256) % 256, 255);
        put.at(3, 0, (nframes / 65536) % 256, (nframes / 256) % 256, nframes % 256, ntextures);
        put.at(4, 0, (dur / 65536) % 256, (dur / 256) % 256, dur % 256,
                128 | (autoplay ? 64 : 0) | ((easing & 3) << 4) | ((interpolation & 3) << 2) | ((easing >> 2) & 3));
        put.at(5, 0, (vpH / 256) % 256, vpH % 256, (vtH / 256) % 256, 255);
        put.at(6, 0, (autorotate << 5) | (visibility << 2) | (cb / 256), cb % 256, 2, 255);
        put.at(7, 0, frameH % 256, nvertices % 256, vtH % 256, 255);
        // GUI transform at q16: the icon's scale, no translation, rotation or pivot.
        int[] s1 = q16(iconScale, 0, 4), t0 = q16(0, -128, 128), r0 = q16(0, 0, 360);
        put.at(8, 0, s1[0], s1[1], s1[0], 255);
        put.at(9, 0, s1[1], s1[0], s1[1], 255);
        put.at(10, 0, t0[0], t0[1], t0[0], 255);
        put.at(11, 0, t0[1], t0[0], t0[1], 255);
        put.at(12, 0, r0[0], r0[1], r0[0], 255);
        put.at(13, 0, r0[1], r0[0], r0[1], 255);
        put.at(14, 0, t0[0], t0[1], t0[0], 255);
        put.at(15, 0, t0[1], t0[0], t0[1], 255);

        // Row 1: the display-slot table obj³ writes when nothing is customised
        // (both first-person hands, and the lift for ground and shelf).
        for (int i = 0; i < tw; i++) put.at(i, 1, 0, 0, 0, 255);
        put.at(0, 1, 0, 0, 2, 255);
        put.at(1, 1, 0, 0, 2, 255);
        put.at(2, 1, 0, 0, 1, 255);
        put.at(3, 1, 0, 0, 1, 255);
        put.at(5, 1, 4 << 1, 0, 2, 255);

        // One pixel per face, and the element pointing at it.
        JsonArray elements = new JsonArray();
        for (int i = 0; i < nfaces; i++) {
            int px = i % tw, py = i / tw + headerRows;
            put.at(px, py, (px / 256) % 256, px % 256, (py / 256) % 256, py % 256);
            JsonObject north = new JsonObject();
            north.add("uv", array((px + 0.1) * 16 / tw, (py + 0.1) * 16 / ty, (px + 0.9) * 16 / tw, (py + 0.9) * 16 / ty));
            north.addProperty("texture", "#0");
            north.addProperty("tintindex", 0);
            JsonObject facesObj = new JsonObject();
            facesObj.add("north", north);
            JsonObject elem = new JsonObject();
            elem.add("from", array(8, 8, 8));
            elem.add("to", array(24, 24, 8));
            elem.add("faces", facesObj);
            elements.add(elem);
        }

        // The texture, flipped.
        for (int py = 0; py < th; py++) {
            int srcY = th - 1 - py;
            System.arraycopy(texture.rgba, srcY * tw * 4, buf, ((headerRows + uvH) + py) * tw * 4, tw * 4);
        }

        // Positions, with obj³'s half-block drop baked in.
        int ybase = headerRows + uvH + texH;
        for (int i = 0; i < positions.size(); i++) {
            double[] p = positions.get(i);
            for (int j = 0; j < 3; j++) {
                double off = j == 1 ? -0.5 : 0;
                long v = (long) (8388608 + p[j] * 65536 + off * 65536);
                int q = i * 3 + j;
                put.at(q % tw, ybase + q / tw, (int) ((v >> 16) & 255), (int) ((v >> 8) & 255), (int) (v & 255), 255);
            }
        }
        // UVs.
        ybase += vpH;
        for (int i = 0; i < uvList.size(); i++) {
            double[] uv = uvList.get(i);
            for (int j = 0; j < 2; j++) {
                long v = (long) (Math.max(0, Math.min(1, uv[j])) * 65535);
                int q = i * 2 + j;
                put.at(q % tw, ybase + q / tw, (int) ((v >> 16) & 255), (int) ((v >> 8) & 255), (int) (v & 255), 255);
            }
        }
        // Per frame, per face corner: position index, UV index.
        ybase += vtH;
        for (int i = 0; i < vi; i++) {
            int q = i * 2;
            int p = vertPos[i], u = vertUv[i];
            put.at(q % tw, ybase + q / tw, (p >> 16) & 255, (p >> 8) & 255, p & 255, 255);
            q++;
            put.at(q % tw, ybase + q / tw, (u >> 16) & 255, (u >> 8) & 255, u & 255, 255);
        }

        return new Result(png(tw, ty, buf), tw, ty, nfaces, nframes, elements);
    }

    @FunctionalInterface
    private interface Put {
        void at(int x, int y, int r, int g, int b, int a);
    }

    private static int[] q16(double v, double min, double max) {
        double c = Math.max(min, Math.min(max, v));
        int u = (int) Math.round((c - min) / (max - min) * 65535);
        return new int[]{(u >> 8) & 255, u & 255};
    }

    private static double round(double v, int places) {
        double k = Math.pow(10, places);
        double r = Math.round(v * k) / k;
        return r == 0 ? 0 : r; // no negative zero
    }

    private static int ceilDiv(int a, int b) {
        return (a + b - 1) / b;
    }

    static JsonArray array(double... v) {
        JsonArray a = new JsonArray();
        for (double x : v) {
            if (x == Math.rint(x) && Math.abs(x) < 1e9) a.add((long) x);
            else a.add(x);
        }
        return a;
    }

    // ---- PNG ------------------------------------------------------------------------

    /** RGBA8 PNG, no filtering: the data rows are not images and filtering them buys nothing. */
    public static byte[] png(int w, int h, byte[] rgba) {
        ByteArrayOutputStream raw = new ByteArrayOutputStream(h * (w * 4 + 1));
        for (int y = 0; y < h; y++) {
            raw.write(0);
            raw.write(rgba, y * w * 4, w * 4);
        }
        Deflater deflater = new Deflater(6);
        deflater.setInput(raw.toByteArray());
        deflater.finish();
        ByteArrayOutputStream z = new ByteArrayOutputStream();
        byte[] chunk = new byte[65536];
        while (!deflater.finished()) z.write(chunk, 0, deflater.deflate(chunk));
        deflater.end();

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        ByteBuffer ihdr = ByteBuffer.allocate(13).putInt(w).putInt(h).put((byte) 8).put((byte) 6).put((byte) 0).put((byte) 0).put((byte) 0);
        chunk(out, "IHDR", ihdr.array());
        chunk(out, "IDAT", z.toByteArray());
        chunk(out, "IEND", new byte[0]);
        return out.toByteArray();
    }

    private static void chunk(ByteArrayOutputStream out, String type, byte[] data) {
        byte[] t = type.getBytes(StandardCharsets.US_ASCII);
        out.writeBytes(ByteBuffer.allocate(4).putInt(data.length).array());
        CRC32 crc = new CRC32();
        crc.update(t);
        crc.update(data);
        out.writeBytes(t);
        out.writeBytes(data);
        out.writeBytes(ByteBuffer.allocate(4).putInt((int) crc.getValue()).array());
    }
}
