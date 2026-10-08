package ai.resourcepack.engine3d.bake;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Everything drawn in a model file as one triangle list, and the means to pose
 * it: node hierarchy, clips, and per vertex the slots (matrices) that move it.
 *
 * A slot is a node and the matrix taking a vertex from where it is stored to
 * that node's frame at rest. Skinned meshes get one slot per joint of their
 * skin (the inverse bind matrix); every other mesh gets one slot for its own
 * node, its vertices stored at rest in world space and moved rigidly. So the
 * same machinery poses a skinned animal, a hierarchy of rigid parts (a fan, a
 * door on a hinge) and a static prop alike.
 */
public final class Rig {

    public static final class Vert {
        public double[] pos;
        public int[] joints;
        public double[] weights;
        public double[] uv;
        public int material;
        public boolean rigid;
    }

    /** One node's local transform: translation, rotation, scale, or a fixed matrix. */
    public record Local(double[] t, double[] r, double[] s, double[] m) {
        Local with(String path, double[] v) {
            return switch (path) {
                case "translation" -> new Local(v, r, s, null);
                case "rotation" -> new Local(t, v, s, null);
                case "scale" -> new Local(t, r, v, null);
                default -> this;
            };
        }

        double[] matrix() {
            return m != null ? m : Mat.trs(t, r, s);
        }
    }

    public record Channel(int node, String path, double[] input, double[][] output, String interp) {
    }

    public record Clip(String name, double length, List<Channel> channels) {
    }

    /** A material, as the atlas needs it: an image (or none) and a colour factor. */
    public record Material(byte[] image, double[] factor) {
    }

    public final List<Vert> verts = new ArrayList<>();
    /** {a, b, c, material}. */
    public final List<int[]> tris = new ArrayList<>();
    public final List<Material> materials = new ArrayList<>();
    public final List<Clip> clips = new ArrayList<>();
    public String[] names;
    public int[] parent;
    public Local[] rest;
    /** Per slot: its node, and the matrix from stored position to that node at rest. */
    int[] slotNode;
    double[][] slotBind;

    private final double[][] restWorld;

    private Rig(int nodes) {
        names = new String[nodes];
        parent = new int[nodes];
        rest = new Local[nodes];
        restWorld = new double[nodes][];
        Arrays.fill(parent, -1);
    }

    /** A node's name without the "_123" suffix some exporters add. */
    public static String strip(String name) {
        return name == null ? "" : name.replaceAll("_\\d+$", "");
    }

    public int node(String name) {
        for (int i = 0; i < names.length; i++) if (strip(names[i]).equals(name) || names[i].equals(name)) return i;
        return -1;
    }

    public Clip clip(String name) {
        for (Clip c : clips) if (c.name.equals(name)) return c;
        for (Clip c : clips) if (c.name.equalsIgnoreCase(name)) return c;
        return null;
    }

    public double[] restWorldOf(int i) {
        if (restWorld[i] == null) {
            double[] m = rest[i].matrix();
            restWorld[i] = parent[i] < 0 ? m : Mat.mul(restWorldOf(parent[i]), m);
        }
        return restWorld[i];
    }

    // ---- loading ------------------------------------------------------------------

    public static Rig fromGltf(Gltf g) throws IOException {
        var nodes = g.arr("nodes");
        Rig rig = new Rig(nodes.size());
        for (int i = 0; i < nodes.size(); i++) {
            JsonObject n = nodes.get(i).getAsJsonObject();
            rig.names[i] = n.has("name") ? n.get("name").getAsString() : "node" + i;
            double[] m = n.has("matrix") ? Gltf.doubles(n, "matrix") : null;
            rig.rest[i] = new Local(Gltf.doubles(n, "translation", 0, 0, 0), Gltf.doubles(n, "rotation", 0, 0, 0, 1), Gltf.doubles(n, "scale", 1, 1, 1), m);
            if (n.has("children")) for (JsonElement c : n.getAsJsonArray("children")) rig.parent[c.getAsInt()] = i;
        }

        // Materials: the base colour image and factor.
        Map<Integer, byte[]> images = new HashMap<>();
        for (JsonElement me : g.arr("materials")) {
            JsonObject pbr = me.getAsJsonObject().has("pbrMetallicRoughness") ? me.getAsJsonObject().getAsJsonObject("pbrMetallicRoughness") : null;
            double[] factor = Gltf.doubles(pbr, "baseColorFactor", 1, 1, 1, 1);
            byte[] image = null;
            if (pbr != null && pbr.has("baseColorTexture")) {
                int tex = pbr.getAsJsonObject("baseColorTexture").get("index").getAsInt();
                JsonObject t = g.get("textures", tex);
                if (t.has("source")) {
                    int src = t.get("source").getAsInt();
                    if (!images.containsKey(src)) images.put(src, g.image(src));
                    image = images.get(src);
                }
            }
            rig.materials.add(new Material(image, factor));
        }
        if (rig.materials.isEmpty()) rig.materials.add(new Material(null, new double[]{0.8, 0.8, 0.8, 1}));

        // Slots, then vertices.
        List<Integer> slotNode = new ArrayList<>();
        List<double[]> slotBind = new ArrayList<>();
        for (int ni = 0; ni < nodes.size(); ni++) {
            JsonObject n = nodes.get(ni).getAsJsonObject();
            if (!n.has("mesh")) continue;
            JsonObject mesh = g.get("meshes", n.get("mesh").getAsInt());
            int[] slotOf;
            boolean skinned = n.has("skin");
            if (skinned) {
                JsonObject skin = g.get("skins", n.get("skin").getAsInt());
                var joints = skin.getAsJsonArray("joints");
                double[][] ibm = skin.has("inverseBindMatrices") ? g.accessor(skin.get("inverseBindMatrices").getAsInt()) : null;
                slotOf = new int[joints.size()];
                for (int k = 0; k < joints.size(); k++) {
                    slotOf[k] = slotNode.size();
                    slotNode.add(joints.get(k).getAsInt());
                    slotBind.add(ibm != null ? ibm[k] : Mat.ident());
                }
            } else {
                slotOf = new int[]{slotNode.size()};
                slotNode.add(ni);
                slotBind.add(Mat.invert(rig.restWorldOf(ni)));
            }
            double[] world = rig.restWorldOf(ni);
            for (JsonElement pe : mesh.getAsJsonArray("primitives")) {
                JsonObject prim = pe.getAsJsonObject();
                int mode = Gltf.integer(prim, "mode", 4);
                if (mode != 4) continue;
                JsonObject at = prim.getAsJsonObject("attributes");
                double[][] p = g.accessor(at.get("POSITION").getAsInt());
                double[][] uv = at.has("TEXCOORD_0") ? g.accessor(at.get("TEXCOORD_0").getAsInt()) : null;
                double[][] j = skinned && at.has("JOINTS_0") ? g.accessor(at.get("JOINTS_0").getAsInt()) : null;
                double[][] w = skinned && at.has("WEIGHTS_0") ? g.accessor(at.get("WEIGHTS_0").getAsInt()) : null;
                int material = Gltf.integer(prim, "material", 0);
                if (material >= rig.materials.size()) material = 0;
                int base = rig.verts.size();
                for (int k = 0; k < p.length; k++) {
                    Vert v = new Vert();
                    v.uv = uv != null ? new double[]{uv[k][0], uv[k][1]} : new double[]{0.5, 0.5};
                    v.material = material;
                    if (j != null && w != null) {
                        v.pos = p[k];
                        v.joints = new int[4];
                        v.weights = new double[4];
                        for (int c = 0; c < 4; c++) {
                            v.joints[c] = slotOf[(int) j[k][c]];
                            v.weights[c] = w[k][c];
                        }
                    } else {
                        // Rigid: stored at rest in world space, moved by its node.
                        v.pos = skinned ? p[k] : Mat.xform(world, p[k]);
                        v.joints = new int[]{slotOf[0], 0, 0, 0};
                        v.weights = new double[]{1, 0, 0, 0};
                        v.rigid = true;
                    }
                    rig.verts.add(v);
                }
                double[][] idx = prim.has("indices") ? g.accessor(prim.get("indices").getAsInt()) : null;
                int count = idx != null ? idx.length : p.length;
                for (int k = 0; k + 2 < count; k += 3) {
                    int a = idx != null ? (int) idx[k][0] : k, b = idx != null ? (int) idx[k + 1][0] : k + 1, c = idx != null ? (int) idx[k + 2][0] : k + 2;
                    rig.tris.add(new int[]{base + a, base + b, base + c, material});
                }
            }
        }
        if (rig.tris.isEmpty()) throw new IOException("No triangles in this file");
        rig.slotNode = slotNode.stream().mapToInt(Integer::intValue).toArray();
        rig.slotBind = slotBind.toArray(new double[0][]);

        for (JsonElement ae : g.arr("animations")) {
            JsonObject a = ae.getAsJsonObject();
            List<Channel> channels = new ArrayList<>();
            double length = 0;
            for (JsonElement ce : a.getAsJsonArray("channels")) {
                JsonObject c = ce.getAsJsonObject();
                JsonObject target = c.getAsJsonObject("target");
                if (!target.has("node")) continue;
                String path = target.get("path").getAsString();
                if (path.equals("weights")) continue;
                JsonObject s = a.getAsJsonArray("samplers").get(c.get("sampler").getAsInt()).getAsJsonObject();
                double[][] in = g.accessor(s.get("input").getAsInt());
                double[] input = new double[in.length];
                for (int k = 0; k < in.length; k++) input[k] = in[k][0];
                String interp = s.has("interpolation") ? s.get("interpolation").getAsString() : "LINEAR";
                channels.add(new Channel(target.get("node").getAsInt(), path, input, g.accessor(s.get("output").getAsInt()), interp));
                if (input.length > 0) length = Math.max(length, input[input.length - 1]);
            }
            String name = a.has("name") ? a.get("name").getAsString() : "clip" + rig.clips.size();
            rig.clips.add(new Clip(name, length, channels));
        }
        return rig;
    }

    /** A static mesh with one node, for OBJ files. */
    static Rig fromMesh(List<double[]> positions, List<double[]> uvs, List<int[]> tris, List<Material> materials) {
        Rig rig = new Rig(1);
        rig.names[0] = "root";
        rig.rest[0] = new Local(new double[]{0, 0, 0}, new double[]{0, 0, 0, 1}, new double[]{1, 1, 1}, null);
        rig.slotNode = new int[]{0};
        rig.slotBind = new double[][]{Mat.ident()};
        rig.materials.addAll(materials);
        int[] matOf = new int[positions.size()];
        for (int[] t : tris) for (int k = 0; k < 3; k++) matOf[t[k]] = t[3];
        for (int i = 0; i < positions.size(); i++) {
            Vert v = new Vert();
            v.pos = positions.get(i);
            v.uv = uvs.get(i);
            v.joints = new int[]{0, 0, 0, 0};
            v.weights = new double[]{1, 0, 0, 0};
            v.material = matOf[i];
            v.rigid = true;
            rig.verts.add(v);
        }
        rig.tris.addAll(tris);
        return rig;
    }

    // ---- posing -------------------------------------------------------------------

    private static double[] sample(Channel c, double t) {
        double[] input = c.input;
        boolean cubic = c.interp.equals("CUBICSPLINE");
        int last = input.length - 1;
        if (last < 0) return null;
        if (t <= input[0]) return value(c, 0, cubic);
        if (t >= input[last]) return value(c, last, cubic);
        int k = 0;
        while (input[k + 1] < t) k++;
        double u = (t - input[k]) / (input[k + 1] - input[k]);
        if (c.interp.equals("STEP")) return value(c, k, cubic);
        double[] a = value(c, k, cubic), b = value(c, k + 1, cubic);
        if (c.path.equals("rotation")) return Mat.slerp(Mat.normalize(a), Mat.normalize(b), u);
        return Mat.lerp(a, b, u);
    }

    private static double[] value(Channel c, int k, boolean cubic) {
        return c.output[cubic ? k * 3 + 1 : k];
    }

    public Local[] restLocals() {
        return rest.clone();
    }

    /** Every node's local transform with {@code clip} at time {@code t} (seconds); null clip = rest. */
    public Local[] localPose(Clip clip, double t) {
        Local[] local = rest.clone();
        if (clip != null) for (Channel c : clip.channels) {
            if (c.node >= local.length) continue;
            double[] v = sample(c, t);
            if (v != null) local[c.node] = local[c.node].with(c.path, v);
        }
        return local;
    }

    public double[][] worldFrom(Local[] local) {
        double[][] world = new double[local.length][];
        for (int i = 0; i < local.length; i++) worldOf(i, local, world);
        return world;
    }

    private double[] worldOf(int i, Local[] local, double[][] world) {
        if (world[i] == null) {
            double[] m = local[i].matrix();
            world[i] = parent[i] < 0 ? m : Mat.mul(worldOf(parent[i], local, world), m);
        }
        return world[i];
    }

    /** Every vertex's position for a set of local transforms. */
    public double[][] positions(Local[] local) {
        double[][] world = worldFrom(local);
        double[][] slot = new double[slotNode.length][];
        for (int s = 0; s < slot.length; s++) slot[s] = Mat.mul(world[slotNode[s]], slotBind[s]);
        double[][] out = new double[verts.size()][];
        for (int i = 0; i < out.length; i++) {
            Vert v = verts.get(i);
            double x = 0, y = 0, z = 0;
            for (int k = 0; k < v.weights.length; k++) {
                double w = v.weights[k];
                if (w == 0) continue;
                double[] p = Mat.xform(slot[v.joints[k]], v.pos);
                x += p[0] * w;
                y += p[1] * w;
                z += p[2] * w;
            }
            out[i] = new double[]{x, y, z};
        }
        return out;
    }

    /** Two poses mixed bone by bone: rotations slerped, translations and scales lerped. */
    public static Local[] blend(Local[] a, Local[] b, double w) {
        Local[] out = new Local[a.length];
        for (int i = 0; i < a.length; i++) {
            Local x = a[i], y = b[i];
            if (x.m != null || y.m != null) {
                out[i] = w < 0.5 ? x : y;
                continue;
            }
            out[i] = new Local(Mat.lerp(x.t, y.t, w), Mat.slerp(x.r, y.r, w), Mat.lerp(x.s, y.s, w), null);
        }
        return out;
    }
}
