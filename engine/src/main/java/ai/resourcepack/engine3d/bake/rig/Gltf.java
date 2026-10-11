package ai.resourcepack.engine3d.bake;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;

/**
 * A glTF 2.0 file, .glb or .gltf (with its .bin and images beside it, or
 * embedded as data URIs): the JSON, and every accessor read out as rows of
 * doubles. Morph targets, sparse accessors and compression extensions
 * (Draco, meshopt) are not supported; a file using them says so.
 */
public final class Gltf {

    public final JsonObject json;
    private final List<ByteBuffer> buffers = new ArrayList<>();
    private final Path dir;

    private Gltf(JsonObject json, ByteBuffer glbBin, Path dir) throws IOException {
        this.json = json;
        this.dir = dir;
        for (JsonElement b : arr("buffers")) {
            JsonObject buffer = b.getAsJsonObject();
            if (!buffer.has("uri")) {
                if (glbBin == null) throw new IOException("A buffer has no data");
                buffers.add(glbBin);
            } else {
                buffers.add(ByteBuffer.wrap(readUri(buffer.get("uri").getAsString())).order(ByteOrder.LITTLE_ENDIAN));
            }
        }
        for (String ext : strings("extensionsRequired")) {
            if (ext.equals("KHR_draco_mesh_compression") || ext.equals("EXT_meshopt_compression") || ext.equals("KHR_texture_basisu"))
                throw new IOException("Uses " + ext + ", which isn't supported: export it again without compression");
        }
    }

    public static Gltf load(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        ByteBuffer buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
        if (bytes.length >= 12 && buf.getInt(0) == 0x46546c67) {
            JsonObject json = null;
            ByteBuffer bin = null;
            int off = 12;
            while (off + 8 <= bytes.length) {
                int len = buf.getInt(off), type = buf.getInt(off + 4);
                if (type == 0x4e4f534a) {
                    json = JsonParser.parseString(new String(bytes, off + 8, len, StandardCharsets.UTF_8)).getAsJsonObject();
                } else if (type == 0x004e4942) {
                    bin = ByteBuffer.wrap(bytes, off + 8, len).slice().order(ByteOrder.LITTLE_ENDIAN);
                }
                off += 8 + len;
            }
            if (json == null) throw new IOException(file.getFileName() + " has no JSON chunk");
            return new Gltf(json, bin, file.getParent());
        }
        JsonObject json = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
        return new Gltf(json, null, file.getParent());
    }

    private byte[] readUri(String uri) throws IOException {
        if (uri.startsWith("data:")) {
            int comma = uri.indexOf(',');
            return Base64.getDecoder().decode(uri.substring(comma + 1));
        }
        String path = java.net.URLDecoder.decode(uri, StandardCharsets.UTF_8);
        Path p = dir.resolve(path).normalize();
        if (!p.startsWith(dir.normalize())) throw new IOException("A file outside the model's folder is referenced: " + uri);
        return Files.readAllBytes(p);
    }

    // ---- JSON helpers -------------------------------------------------------------------

    public JsonArray arr(String key) {
        return json.has(key) ? json.getAsJsonArray(key) : new JsonArray();
    }

    public JsonObject get(String key, int i) {
        return json.getAsJsonArray(key).get(i).getAsJsonObject();
    }

    private List<String> strings(String key) {
        List<String> out = new ArrayList<>();
        for (JsonElement e : arr(key)) out.add(e.getAsString());
        return out;
    }

    static double[] doubles(JsonObject o, String key, double... fallback) {
        if (o == null || !o.has(key)) return fallback.clone();
        JsonArray a = o.getAsJsonArray(key);
        double[] out = new double[a.size()];
        for (int i = 0; i < out.length; i++) out[i] = a.get(i).getAsDouble();
        return out;
    }

    static int integer(JsonObject o, String key, int fallback) {
        return o != null && o.has(key) ? o.get(key).getAsInt() : fallback;
    }

    // ---- accessors -------------------------------------------------------------------

    private static int components(String type) {
        return switch (type) {
            case "SCALAR" -> 1;
            case "VEC2" -> 2;
            case "VEC3" -> 3;
            case "VEC4" -> 4;
            case "MAT4" -> 16;
            default -> throw new IllegalArgumentException("Unsupported accessor type " + type);
        };
    }

    /** Accessor {@code i} as rows of its components (one-element rows for scalars). */
    public double[][] accessor(int i) {
        JsonObject a = get("accessors", i);
        int count = a.get("count").getAsInt();
        int n = components(a.get("type").getAsString());
        int ct = a.get("componentType").getAsInt();
        boolean normalized = a.has("normalized") && a.get("normalized").getAsBoolean();
        double[][] out = new double[count][n];
        if (a.has("sparse")) throw new IllegalArgumentException("Sparse accessors are not supported");
        if (!a.has("bufferView")) return out;
        JsonObject bv = get("bufferViews", a.get("bufferView").getAsInt());
        ByteBuffer buf = buffers.get(bv.get("buffer").getAsInt());
        int size = switch (ct) {
            case 5120, 5121 -> 1;
            case 5122, 5123 -> 2;
            default -> 4;
        };
        int stride = integer(bv, "byteStride", size * n);
        int base = integer(bv, "byteOffset", 0) + integer(a, "byteOffset", 0);
        for (int k = 0; k < count; k++) {
            for (int c = 0; c < n; c++) {
                int o = base + k * stride + c * size;
                double v = switch (ct) {
                    case 5120 -> buf.get(o);
                    case 5121 -> buf.get(o) & 0xff;
                    case 5122 -> buf.getShort(o);
                    case 5123 -> buf.getShort(o) & 0xffff;
                    case 5125 -> buf.getInt(o) & 0xffffffffL;
                    case 5126 -> buf.getFloat(o);
                    default -> throw new IllegalArgumentException("Unsupported component type " + ct);
                };
                if (normalized) {
                    double max = switch (ct) {
                        case 5120 -> 127;
                        case 5121 -> 255;
                        case 5122 -> 32767;
                        case 5123 -> 65535;
                        default -> 1;
                    };
                    v = Math.max(v / max, -1);
                }
                out[k][c] = v;
            }
        }
        return out;
    }

    /** The bytes of image {@code i} (PNG or JPEG), embedded or beside the file. */
    public byte[] image(int i) throws IOException {
        JsonObject im = get("images", i);
        if (im.has("bufferView")) {
            JsonObject bv = get("bufferViews", im.get("bufferView").getAsInt());
            ByteBuffer buf = buffers.get(bv.get("buffer").getAsInt());
            int off = integer(bv, "byteOffset", 0), len = bv.get("byteLength").getAsInt();
            byte[] out = new byte[len];
            buf.get(off, out);
            return out;
        }
        if (im.has("uri")) return readUri(im.get("uri").getAsString());
        throw new IOException("Image " + i + " has no data");
    }
}
