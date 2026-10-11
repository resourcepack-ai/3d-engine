package ai.resourcepack.engine3d.bake;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Writes a {@link Rig} back out as a .glb: its skeleton, one skinned mesh of
 * the given triangles, its materials with their images shrunk to fit, and no
 * animations. For shipping a model smaller than it was made: decimate it,
 * shrink its textures, and it bakes the same.
 *
 * Every vertex is written skinned, rigid parts included (weighted wholly to
 * their own node, with that node's rest inverted as the bind matrix), which
 * draws them exactly as before.
 */
public final class GlbWriter {

    private GlbWriter() {
    }

    public static byte[] write(Rig rig, List<int[]> tris, int maxTexture, float jpegQuality) throws IOException {
        TreeSet<Integer> keptSet = new TreeSet<>();
        for (int[] t : tris) for (int k = 0; k < 3; k++) keptSet.add(t[k]);
        int[] keep = keptSet.stream().mapToInt(Integer::intValue).toArray();
        Map<Integer, Integer> remap = new HashMap<>();
        for (int i = 0; i < keep.length; i++) remap.put(keep[i], i);

        Bin bin = new Bin();
        JsonObject json = new JsonObject();
        JsonObject asset = new JsonObject();
        asset.addProperty("version", "2.0");
        asset.addProperty("generator", "3D Engine");
        json.add("asset", asset);
        JsonArray accessors = new JsonArray(), views = new JsonArray();

        // Nodes, as they were, plus one for the mesh.
        JsonArray nodes = new JsonArray();
        List<List<Integer>> children = new ArrayList<>();
        for (int i = 0; i < rig.names.length; i++) children.add(new ArrayList<>());
        for (int i = 0; i < rig.names.length; i++) if (rig.parent[i] >= 0) children.get(rig.parent[i]).add(i);
        for (int i = 0; i < rig.names.length; i++) {
            JsonObject n = new JsonObject();
            n.addProperty("name", rig.names[i]);
            Rig.Local l = rig.rest[i];
            if (l.m() != null) n.add("matrix", arr(l.m()));
            else {
                n.add("translation", arr(l.t()));
                n.add("rotation", arr(l.r()));
                n.add("scale", arr(l.s()));
            }
            if (!children.get(i).isEmpty()) {
                JsonArray c = new JsonArray();
                for (int k : children.get(i)) c.add(k);
                n.add("children", c);
            }
            nodes.add(n);
        }
        int meshNode = nodes.size();
        JsonObject mn = new JsonObject();
        mn.addProperty("name", "mesh");
        mn.addProperty("mesh", 0);
        mn.addProperty("skin", 0);
        nodes.add(mn);
        json.add("nodes", nodes);
        JsonArray roots = new JsonArray();
        for (int i = 0; i < rig.names.length; i++) if (rig.parent[i] < 0) roots.add(i);
        roots.add(meshNode);
        JsonObject scene = new JsonObject();
        scene.add("nodes", roots);
        JsonArray scenes = new JsonArray();
        scenes.add(scene);
        json.add("scenes", scenes);
        json.addProperty("scene", 0);

        // The skin: every slot a joint.
        int slots = rig.slotNode.length;
        float[] ibm = new float[slots * 16];
        for (int s = 0; s < slots; s++) for (int k = 0; k < 16; k++) ibm[s * 16 + k] = (float) rig.slotBind[s][k];
        int ibmAcc = accessor(accessors, views, bin, ibm, slots, "MAT4", null);
        JsonObject skin = new JsonObject();
        JsonArray joints = new JsonArray();
        for (int s = 0; s < slots; s++) joints.add(rig.slotNode[s]);
        skin.add("joints", joints);
        skin.addProperty("inverseBindMatrices", ibmAcc);
        JsonArray skins = new JsonArray();
        skins.add(skin);
        json.add("skins", skins);

        // Vertices.
        int n = keep.length;
        float[] pos = new float[n * 3], uv = new float[n * 2], w = new float[n * 4];
        int[] j = new int[n * 4];
        float[] min = {Float.MAX_VALUE, Float.MAX_VALUE, Float.MAX_VALUE}, max = {-Float.MAX_VALUE, -Float.MAX_VALUE, -Float.MAX_VALUE};
        for (int i = 0; i < n; i++) {
            Rig.Vert v = rig.verts.get(keep[i]);
            for (int a = 0; a < 3; a++) {
                pos[i * 3 + a] = (float) v.pos[a];
                min[a] = Math.min(min[a], pos[i * 3 + a]);
                max[a] = Math.max(max[a], pos[i * 3 + a]);
            }
            uv[i * 2] = (float) v.uv[0];
            uv[i * 2 + 1] = (float) v.uv[1];
            double sum = 0;
            for (int k = 0; k < 4; k++) sum += v.weights[k];
            for (int k = 0; k < 4; k++) {
                j[i * 4 + k] = v.weights[k] > 0 ? v.joints[k] : 0;
                w[i * 4 + k] = (float) (sum > 0 ? v.weights[k] / sum : k == 0 ? 1 : 0);
            }
        }
        JsonObject attrs = new JsonObject();
        attrs.addProperty("POSITION", accessor(accessors, views, bin, pos, n, "VEC3", new float[][]{min, max}));
        attrs.addProperty("TEXCOORD_0", accessor(accessors, views, bin, uv, n, "VEC2", null));
        attrs.addProperty("JOINTS_0", shortAccessor(accessors, views, bin, j, n));
        attrs.addProperty("WEIGHTS_0", accessor(accessors, views, bin, w, n, "VEC4", null));

        // One primitive per material.
        Map<Integer, List<int[]>> byMat = new HashMap<>();
        for (int[] t : tris) byMat.computeIfAbsent(t[3], k -> new ArrayList<>()).add(t);
        JsonArray prims = new JsonArray();
        JsonArray materials = new JsonArray(), textures = new JsonArray(), images = new JsonArray();
        Map<byte[], Integer> imageIndex = new IdentityHashMap<>();
        Map<Integer, Integer> materialIndex = new HashMap<>();
        for (var e : byMat.entrySet()) {
            int mi = e.getKey();
            if (!materialIndex.containsKey(mi)) {
                Rig.Material m = rig.materials.get(mi);
                JsonObject pbr = new JsonObject();
                pbr.add("baseColorFactor", arr(m.factor().length >= 4 ? m.factor() : new double[]{m.factor()[0], m.factor()[1], m.factor()[2], 1}));
                pbr.addProperty("metallicFactor", 0);
                pbr.addProperty("roughnessFactor", 1);
                if (m.image() != null) {
                    Integer img = imageIndex.get(m.image());
                    if (img == null) {
                        img = images.size();
                        imageIndex.put(m.image(), img);
                        byte[] jpeg = shrink(m.image(), maxTexture, jpegQuality);
                        JsonObject im = new JsonObject();
                        im.addProperty("bufferView", view(views, bin, jpeg, null));
                        im.addProperty("mimeType", "image/jpeg");
                        images.add(im);
                        JsonObject tex = new JsonObject();
                        tex.addProperty("source", img);
                        textures.add(tex);
                    }
                    JsonObject bct = new JsonObject();
                    bct.addProperty("index", img);
                    pbr.add("baseColorTexture", bct);
                }
                JsonObject mat = new JsonObject();
                mat.add("pbrMetallicRoughness", pbr);
                materialIndex.put(mi, materials.size());
                materials.add(mat);
            }
            List<int[]> list = e.getValue();
            int[] idx = new int[list.size() * 3];
            for (int t = 0; t < list.size(); t++) for (int k = 0; k < 3; k++) idx[t * 3 + k] = remap.get(list.get(t)[k]);
            JsonObject prim = new JsonObject();
            prim.add("attributes", attrs);
            prim.addProperty("indices", intAccessor(accessors, views, bin, idx));
            prim.addProperty("material", materialIndex.get(mi));
            prims.add(prim);
        }
        JsonObject mesh = new JsonObject();
        mesh.add("primitives", prims);
        JsonArray meshes = new JsonArray();
        meshes.add(mesh);
        json.add("meshes", meshes);
        json.add("materials", materials);
        if (!textures.isEmpty()) {
            json.add("textures", textures);
            json.add("images", images);
        }
        json.add("accessors", accessors);
        json.add("bufferViews", views);
        byte[] body = bin.bytes();
        JsonArray buffers = new JsonArray();
        JsonObject buffer = new JsonObject();
        buffer.addProperty("byteLength", body.length);
        buffers.add(buffer);
        json.add("buffers", buffers);

        byte[] text = json.toString().getBytes(StandardCharsets.UTF_8);
        int textLen = (text.length + 3) & ~3, binLen = (body.length + 3) & ~3;
        ByteBuffer out = ByteBuffer.allocate(12 + 8 + textLen + 8 + binLen).order(ByteOrder.LITTLE_ENDIAN);
        out.putInt(0x46546c67).putInt(2).putInt(out.capacity());
        out.putInt(textLen).putInt(0x4e4f534a).put(text);
        for (int i = text.length; i < textLen; i++) out.put((byte) ' ');
        out.putInt(binLen).putInt(0x004e4942).put(body);
        for (int i = body.length; i < binLen; i++) out.put((byte) 0);
        return out.array();
    }

    private static byte[] shrink(byte[] image, int max, float quality) throws IOException {
        BufferedImage src = ImageIO.read(new ByteArrayInputStream(image));
        int w = src.getWidth(), h = src.getHeight();
        double k = Math.min(1, (double) max / Math.max(w, h));
        int nw = Math.max(1, (int) Math.round(w * k)), nh = Math.max(1, (int) Math.round(h * k));
        BufferedImage dst = new BufferedImage(nw, nh, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = dst.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        // Halve in steps: one bicubic jump from 4096 to 512 aliases.
        BufferedImage cur = src;
        int cw = w, ch = h;
        while (cw / 2 >= nw && ch / 2 >= nh) {
            cw /= 2;
            ch /= 2;
            BufferedImage half = new BufferedImage(cw, ch, BufferedImage.TYPE_INT_RGB);
            Graphics2D hg = half.createGraphics();
            hg.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            hg.drawImage(cur, 0, 0, cw, ch, null);
            hg.dispose();
            cur = half;
        }
        g.drawImage(cur, 0, 0, nw, nh, null);
        g.dispose();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        ImageWriteParam param = writer.getDefaultWriteParam();
        param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
        param.setCompressionQuality(quality);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (MemoryCacheImageOutputStream ios = new MemoryCacheImageOutputStream(out)) {
            writer.setOutput(ios);
            writer.write(null, new IIOImage(dst, null, null), param);
        }
        writer.dispose();
        return out.toByteArray();
    }

    private static final class Bin {
        final ByteArrayOutputStream out = new ByteArrayOutputStream();

        int append(byte[] data) {
            while (out.size() % 4 != 0) out.write(0);
            int at = out.size();
            out.writeBytes(data);
            return at;
        }

        byte[] bytes() {
            return out.toByteArray();
        }
    }

    private static int view(JsonArray views, Bin bin, byte[] data, Integer target) {
        int off = bin.append(data);
        JsonObject v = new JsonObject();
        v.addProperty("buffer", 0);
        v.addProperty("byteOffset", off);
        v.addProperty("byteLength", data.length);
        if (target != null) v.addProperty("target", target);
        views.add(v);
        return views.size() - 1;
    }

    private static int accessor(JsonArray accessors, JsonArray views, Bin bin, float[] data, int count, String type, float[][] minMax) {
        ByteBuffer b = ByteBuffer.allocate(data.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (float f : data) b.putFloat(f);
        JsonObject a = new JsonObject();
        a.addProperty("bufferView", view(views, bin, b.array(), type.equals("MAT4") ? null : 34962));
        a.addProperty("componentType", 5126);
        a.addProperty("count", count);
        a.addProperty("type", type);
        if (minMax != null) {
            a.add("min", arr(minMax[0]));
            a.add("max", arr(minMax[1]));
        }
        accessors.add(a);
        return accessors.size() - 1;
    }

    private static int shortAccessor(JsonArray accessors, JsonArray views, Bin bin, int[] data, int count) {
        ByteBuffer b = ByteBuffer.allocate(data.length * 2).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : data) b.putShort((short) v);
        JsonObject a = new JsonObject();
        a.addProperty("bufferView", view(views, bin, b.array(), 34962));
        a.addProperty("componentType", 5123);
        a.addProperty("count", count);
        a.addProperty("type", "VEC4");
        accessors.add(a);
        return accessors.size() - 1;
    }

    private static int intAccessor(JsonArray accessors, JsonArray views, Bin bin, int[] data) {
        ByteBuffer b = ByteBuffer.allocate(data.length * 4).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : data) b.putInt(v);
        JsonObject a = new JsonObject();
        a.addProperty("bufferView", view(views, bin, b.array(), 34963));
        a.addProperty("componentType", 5125);
        a.addProperty("count", data.length);
        a.addProperty("type", "SCALAR");
        accessors.add(a);
        return accessors.size() - 1;
    }

    private static JsonArray arr(double[] v) {
        JsonArray a = new JsonArray();
        for (double x : v) a.add(x);
        return a;
    }

    private static JsonArray arr(float[] v) {
        JsonArray a = new JsonArray();
        for (float x : v) a.add(x);
        return a;
    }
}
