package ai.resourcepack.engine3d.bake;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;

/**
 * Turns a model file (and its spec) into game models: one looping model per
 * state, played on the client's clock from an offset the server picks, and
 * one short one-shot per transition, blended bone by bone from a sync point of
 * the source state onto the first frame of the target, so that the server
 * always knows which frame is showing and no pose ever pops.
 *
 * Each game model is every frame's vertex positions, encoded by
 * {@link ObjCubed}.
 */
public final class Baker {

    /** Frames of the target played on after a transition, so a client a tick behind never blends into the next range. */
    private static final int PAD = 2;
    /** An item display draws a model's y=0 half a block below the entity: this stands it on the entity's feet. */
    private static final double LIFT = 0.5;

    public record Baked(Manifest manifest, Map<String, ObjCubed.Result> models) {
    }

    private final Rig rig;
    private final Spec spec;
    private final Consumer<String> log;
    private final List<LegChain> legs = new ArrayList<>();
    private final Map<String, int[]> points = new LinkedHashMap<>();
    private final Map<String, double[]> pointLocal = new LinkedHashMap<>();

    private record LegChain(int[] chain, int target, int last, double[] endLocal) {
    }

    private Baker(Rig rig, Spec spec, Consumer<String> log) {
        this.rig = rig;
        this.spec = spec;
        this.log = log;
    }

    public static Rig load(Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        if (name.endsWith(".obj")) return ObjLoader.load(file);
        return Rig.fromGltf(Gltf.load(file));
    }

    public static Baked bake(Path file, Spec spec, String name, Consumer<String> log) throws IOException {
        Rig rig = load(file);
        if (spec.animationsFrom != null) {
            Path other = file.getParent().resolve(spec.animationsFrom).normalize();
            if (!other.startsWith(file.getParent())) throw new IOException("animations-from must be beside the model");
            if (!Files.exists(other)) throw new IOException("animations-from: no file " + spec.animationsFrom);
            retarget(rig, load(other), spec.retargetTranslate);
        }
        return new Baker(rig, spec, log).run(name);
    }

    // ---- retargeting ----------------------------------------------------------------

    /**
     * Copies {@code source}'s clips onto {@code target}'s skeleton, bone by bone
     * by name: each rotation relative to the source bone's rest becomes the
     * same rotation relative to the target bone's rest, and translations (of
     * the bones named, or all) move by the same offset from rest, turned into
     * the target's frame and scaled by the two rigs' heights.
     */
    static void retarget(Rig target, Rig source, List<String> translate) {
        double k = height(target.positions(target.restLocals())) / Math.max(1e-9, height(source.positions(source.restLocals())));
        Set<String> moving = translate == null ? null : new LinkedHashSet<>(translate);
        for (Rig.Clip clip : source.clips) {
            if (target.clip(clip.name()) != null) continue;
            List<Rig.Channel> channels = new ArrayList<>();
            for (Rig.Channel c : clip.channels()) {
                String name = Rig.strip(source.names[c.node()]);
                int ln = target.node(name);
                if (ln < 0) continue;
                Rig.Local sRest = source.rest[c.node()];
                Rig.Local tRest = target.rest[ln];
                if (c.path().equals("rotation")) {
                    double[] pre = Mat.qmul(tRest.r(), Mat.qinv(sRest.r()));
                    double[][] out = new double[c.output().length][];
                    for (int i = 0; i < out.length; i++) out[i] = Mat.qmul(pre, Mat.normalize(c.output()[i]));
                    channels.add(new Rig.Channel(ln, "rotation", c.input(), out, c.interp()));
                } else if (c.path().equals("translation") && (moving == null || moving.contains(name))) {
                    int sp = source.parent[c.node()], lp = target.parent[ln];
                    double[] toWorld = sp < 0 ? Mat.ident() : Mat.linear(source.restWorldOf(sp));
                    double[] fromWorld = lp < 0 ? Mat.ident() : Mat.invert(Mat.linear(target.restWorldOf(lp)));
                    double[][] out = new double[c.output().length][];
                    for (int i = 0; i < out.length; i++) {
                        double[] d = Mat.sub(c.output()[i], sRest.t());
                        double[] moved = Mat.scale(Mat.rotate(fromWorld, Mat.rotate(toWorld, d)), k);
                        out[i] = Mat.add(tRest.t(), moved);
                    }
                    channels.add(new Rig.Channel(ln, "translation", c.input(), out, c.interp()));
                }
            }
            target.clips.add(new Rig.Clip(clip.name(), clip.length(), channels));
        }
    }

    private static double height(double[][] ps) {
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (double[] p : ps) {
            lo = Math.min(lo, p[1]);
            hi = Math.max(hi, p[1]);
        }
        return hi - lo;
    }

    // ---- the bake ----------------------------------------------------------------------

    private Baked run(String name) throws IOException {
        Rig.Local[] restLocals = rig.restLocals();
        double[][] restAll = rig.positions(restLocals);

        // Bones nothing animates that some vertices hang off (a stray helper
        // bone): those vertices borrow the weights of their nearest neighbour.
        for (String dead : spec.rebind) {
            int node = rig.node(dead);
            if (node < 0) throw new IOException("rebind: no bone called " + dead);
            rebind(node, restAll);
        }

        double[][] restWorld = rig.worldFrom(restLocals);
        for (Spec.Leg l : spec.legs) {
            int[] chain = new int[l.chain().size()];
            for (int i = 0; i < chain.length; i++) chain[i] = bone(l.chain().get(i));
            int target = bone(l.target());
            int last = chain[chain.length - 1];
            double[] endLocal = Mat.xform(Mat.invert(restWorld[last]), Mat.pos(restWorld[target]));
            legs.add(new LegChain(chain, target, last, endLocal));
        }
        for (var e : spec.points.entrySet()) {
            int b = bone(e.getValue().bone());
            points.put(e.getKey(), new int[]{b});
            pointLocal.put(e.getKey(), Mat.xform(Mat.invert(restWorld[b]), e.getValue().at()));
        }

        // States: as written, or one per clip.
        Map<String, Spec.State> states = new LinkedHashMap<>(spec.states);
        if (states.isEmpty()) {
            for (Rig.Clip c : rig.clips) {
                Spec.State st = new Spec.State();
                st.clip = c.name();
                states.put(stateName(c.name(), states.keySet()), st);
            }
            if (states.isEmpty()) states.put("default", new Spec.State());
        }
        for (var e : states.entrySet()) {
            String clip = e.getValue().clip;
            if (clip != null && rig.clip(clip) == null)
                throw new IOException("state " + e.getKey() + ": no clip called " + clip + " (have " + clipNames() + ")");
        }

        // Decimate at rest; the survivors keep their own UVs and weights.
        int before = rig.tris.size();
        List<int[]> tris = Simplifier.simplify(restAll, rig.tris, spec.triangles);
        TreeSet<Integer> keptSet = new TreeSet<>();
        for (int[] t : tris) for (int k = 0; k < 3; k++) keptSet.add(t[k]);
        int[] keep = keptSet.stream().mapToInt(Integer::intValue).toArray();
        Map<Integer, Integer> remap = new HashMap<>();
        for (int i = 0; i < keep.length; i++) remap.put(keep[i], i);
        Atlas atlas = Atlas.build(rig, tris, spec.textureWidth);
        double[][] uvs = new double[keep.length][];
        for (int i = 0; i < keep.length; i++) uvs[i] = atlas.uvOf(keep[i]);
        List<int[]> local = new ArrayList<>();
        for (int[] t : tris) local.add(new int[]{remap.get(t[0]), remap.get(t[1]), remap.get(t[2]), t[3]});
        double[][] restKept = new double[keep.length][];
        for (int i = 0; i < keep.length; i++) restKept[i] = restAll[keep[i]];
        int[][] faces = Quads.build(local, restKept);
        log.accept(String.format("%s: %d triangles -> %d (%d faces), %d vertices", name, before, tris.size(), faces.length, keep.length));

        // Placement: centred, feet on the entity's, facing its way, sized.
        double[][] standing = kept(rig.positions(stateLocals(new Spec.State(), 0)), keep);
        double minX = Double.MAX_VALUE, maxX = -Double.MAX_VALUE, minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE, minZ = Double.MAX_VALUE, maxZ = -Double.MAX_VALUE;
        for (double[] p : standing) {
            minX = Math.min(minX, p[0]);
            maxX = Math.max(maxX, p[0]);
            minY = Math.min(minY, p[1]);
            maxY = Math.max(maxY, p[1]);
            minZ = Math.min(minZ, p[2]);
            maxZ = Math.max(maxZ, p[2]);
        }
        double s = spec.height != null ? spec.height / Math.max(1e-9, maxY - minY) : spec.scale;
        double cx = (minX + maxX) / 2, cz = (minZ + maxZ) / 2, floor = minY;
        // A glTF model faces +Z; the display shows a model's -Z the way the entity faces.
        double yaw = Math.toRadians(180 + spec.yaw), cos = Math.cos(yaw), sin = Math.sin(yaw);
        Placement place = p -> {
            double x = (p[0] - cx) * s, y = (p[1] - floor) * s + LIFT, z = (p[2] - cz) * s;
            return new double[]{x * cos + z * sin + spec.offset[0], y + spec.offset[1], -x * sin + z * cos + spec.offset[2]};
        };

        Manifest manifest = new Manifest();
        manifest.name = name;
        manifest.height = (maxY - minY) * s;
        double radius = 0;
        for (double[] p : standing) {
            double[] q = place.at(p);
            radius = Math.max(radius, Math.hypot(q[0], q[2]));
        }
        manifest.radius = radius;
        manifest.locomotion = spec.locomotion;
        manifest.defaultState = spec.defaultState != null ? spec.defaultState : states.containsKey("idle") ? "idle" : states.keySet().iterator().next();
        if (!states.containsKey(manifest.defaultState)) throw new IOException("default-state " + manifest.defaultState + " is not a state");
        Map<String, ObjCubed.Result> models = new LinkedHashMap<>();

        for (var e : states.entrySet()) {
            Spec.State st = e.getValue();
            double len = stateLength(st);
            boolean still = st.clip == null && st.blend == null;
            int ticks = still ? 1 : Math.max(2, (int) Math.round(len * 20));
            int n = still ? 1 : Math.max(2, (int) Math.round(len * st.fps));
            List<double[][]> frames = new ArrayList<>();
            List<Map<String, double[]>> pts = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                Rig.Local[] l = stateLocals(st, (double) i / n * len);
                frames.add(placed(l, keep, place));
                pts.add(pointsAt(l, place));
            }
            models.put(e.getKey(), encode(manifest, e.getKey(), "loop", frames, ticks, pts, uvs, faces, atlas));
            Manifest.State ms = new Manifest.State();
            ms.model = e.getKey();
            ms.ticks = ticks;
            ms.wait = st.wait;
            if (st.syncAt != null) ms.syncs = new ArrayList<>(st.syncAt);
            else {
                ms.syncs = new ArrayList<>();
                int syncs = Math.max(1, st.syncs);
                for (int k = 0; k < syncs; k++) ms.syncs.add((int) Math.round((double) k * ticks / syncs) % Math.max(1, ticks));
            }
            manifest.states.put(e.getKey(), ms);
        }

        for (Spec.Transition t : spec.transitions) {
            Spec.State a = states.get(t.from()), b = states.get(t.to());
            if (a == null || b == null) throw new IOException("transition " + t.from() + " -> " + t.to() + ": no such state");
            int frames = Math.max(1, t.frames());
            List<double[][]> strip = new ArrayList<>();
            List<Map<String, double[]>> pts = new ArrayList<>();
            List<Manifest.Range> ranges = new ArrayList<>();
            for (int sync : manifest.states.get(t.from()).syncs) {
                int start = strip.size();
                for (int i = 0; i <= frames + PAD; i++) {
                    double tA = (sync + i) / 20.0, tB = (i - frames) / 20.0;
                    double w = i >= frames ? 1 : smooth((double) i / frames);
                    Rig.Local[] l = solveLegs(Rig.blend(stateLocals(a, tA), stateLocals(b, tB), w));
                    strip.add(placed(l, keep, place));
                    pts.add(pointsAt(l, place));
                }
                Manifest.Range r = new Manifest.Range();
                r.sync = sync;
                r.start = start;
                r.length = frames;
                ranges.add(r);
            }
            String id = t.from() + "_to_" + t.to();
            models.put(id, encode(manifest, id, "oneshot", strip, strip.size(), pts, uvs, faces, atlas));
            Manifest.Pair pair = new Manifest.Pair();
            pair.model = id;
            pair.ranges = ranges;
            manifest.transitions.computeIfAbsent(t.from(), k -> new Manifest.Out()).to.put(t.to(), pair);
        }
        long texels = 0;
        for (ObjCubed.Result r : models.values()) texels += (long) r.width() * r.height();
        manifest.texels = texels;
        log.accept(String.format("%s: %d game models, %.1f megatexels", name, models.size(), texels / 1e6));
        return new Baked(manifest, models);
    }

    private ObjCubed.Result encode(Manifest manifest, String id, String kind, List<double[][]> frames, int ticks, List<Map<String, double[]>> pts,
                                   double[][] uvs, int[][] faces, Atlas atlas) {
        // As an icon, fitted to the slot unless the spec says otherwise.
        double icon = spec.iconScale != null ? spec.iconScale : Math.min(4, 0.9 / Math.max(0.05, Math.max(manifest.height, manifest.radius * 2)));
        ObjCubed.Result r = ObjCubed.encode(frames, uvs, faces, atlas.image, ticks, ObjCubed.Clock.TIME, icon);
        Manifest.Model m = new Manifest.Model();
        m.kind = kind;
        m.frames = frames.size();
        m.ticks = ticks;
        for (String p : points.keySet()) {
            List<float[]> list = new ArrayList<>();
            for (Map<String, double[]> f : pts) {
                double[] q = f.get(p);
                list.add(new float[]{(float) q[0], (float) q[1], (float) q[2]});
            }
            m.points.put(p, list);
        }
        manifest.models.put(id, m);
        return r;
    }

    @FunctionalInterface
    private interface Placement {
        double[] at(double[] p);
    }

    private double[][] placed(Rig.Local[] local, int[] keep, Placement place) {
        double[][] all = rig.positions(local);
        double[][] out = new double[keep.length][];
        for (int i = 0; i < keep.length; i++) out[i] = place.at(all[keep[i]]);
        return out;
    }

    private static double[][] kept(double[][] all, int[] keep) {
        double[][] out = new double[keep.length][];
        for (int i = 0; i < keep.length; i++) out[i] = all[keep[i]];
        return out;
    }

    private Map<String, double[]> pointsAt(Rig.Local[] local, Placement place) {
        if (points.isEmpty()) return Map.of();
        double[][] world = rig.worldFrom(local);
        Map<String, double[]> out = new LinkedHashMap<>();
        for (var e : points.entrySet()) out.put(e.getKey(), place.at(Mat.xform(world[e.getValue()[0]], pointLocal.get(e.getKey()))));
        return out;
    }

    private static double smooth(double x) {
        return x * x * (3 - 2 * x);
    }

    private int bone(String name) throws IOException {
        int i = rig.node(name);
        if (i < 0) throw new IOException("No bone called " + name);
        return i;
    }

    private String clipNames() {
        List<String> names = new ArrayList<>();
        for (Rig.Clip c : rig.clips) names.add(c.name());
        return names.isEmpty() ? "none" : String.join(", ", names);
    }

    private static String stateName(String clip, Set<String> taken) {
        String base = clip.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_]+", "_").replaceAll("^_+|_+$", "");
        if (base.isEmpty()) base = "clip";
        String name = base;
        for (int i = 2; taken.contains(name); i++) name = base + "_" + i;
        return name;
    }

    // ---- posing a state --------------------------------------------------------------

    private double stateLength(Spec.State st) {
        Rig.Clip clip = st.clip != null ? rig.clip(st.clip) : null;
        return clip != null && clip.length() > 0 ? clip.length() : 2;
    }

    /** A state's bone pose at time {@code t} (seconds, wrapped to its clip). */
    private Rig.Local[] stateLocals(Spec.State st, double t) throws IOException {
        Rig.Clip clip = st.clip != null ? rig.clip(st.clip) : null;
        double tt = clip != null && clip.length() > 0 ? ((t % clip.length()) + clip.length()) % clip.length() : 0;
        Rig.Local[] local = rig.localPose(clip, tt);
        // A second clip held at one moment: Walk half-way towards Idle is a shuffle.
        if (st.blend != null) {
            Rig.Clip other = rig.clip(st.blend.clip());
            if (other == null) throw new IOException("blend: no clip called " + st.blend.clip());
            local = Rig.blend(local, rig.localPose(other, st.blend.at()), st.blend.weight());
        }
        local = applyPose(local, st.pose);
        return solveLegs(local);
    }

    /** Authored poses: rotations post-multiplied (degrees, XYZ), translations added. */
    private Rig.Local[] applyPose(Rig.Local[] local, Map<String, Spec.PoseBone> pose) throws IOException {
        if (pose.isEmpty()) return local;
        Rig.Local[] out = local.clone();
        for (var e : pose.entrySet()) {
            int i = bone(e.getKey());
            Rig.Local x = out[i];
            double[] t = e.getValue().translate() != null ? Mat.add(x.t(), e.getValue().translate()) : x.t();
            double[] r = e.getValue().rotate() != null ? Mat.normalize(Mat.qmul(x.r(), Mat.euler(e.getValue().rotate()))) : x.r();
            out[i] = new Rig.Local(t, r, x.s(), null);
        }
        return out;
    }

    /**
     * Puts each leg back on its foot (CCD). In rigs whose feet hang off IK
     * bones rather than the legs, a retargeted leg of a different length or a
     * moved hip leaves the leg short of its foot and stretches the skin.
     */
    private Rig.Local[] solveLegs(Rig.Local[] local) {
        if (legs.isEmpty()) return local;
        local = local.clone();
        for (LegChain leg : legs) {
            for (int iter = 0; iter < 12; iter++) {
                double[][] world = rig.worldFrom(local);
                double[] goal = Mat.pos(world[leg.target]);
                double[] end = Mat.xform(world[leg.last], leg.endLocal);
                if (Math.sqrt(Mat.dist2(end, goal)) < 1e-4) break;
                for (int b = leg.chain.length - 1; b >= 0; b--) {
                    int node = leg.chain[b];
                    world = rig.worldFrom(local);
                    end = Mat.xform(world[leg.last], leg.endLocal);
                    double[] p = Mat.pos(world[node]);
                    double[] qd = Mat.fromTo(Mat.sub(end, p), Mat.sub(goal, p));
                    int parent = rig.parent[node];
                    double[] qp = parent < 0 ? new double[]{0, 0, 0, 1} : Mat.matQuat(world[parent]);
                    double[] r = Mat.normalize(Mat.qmul(Mat.qmul(Mat.qmul(Mat.qinv(qp), qd), qp), local[node].r()));
                    Rig.Local x = local[node];
                    local[node] = new Rig.Local(x.t(), r, x.s(), null);
                }
            }
        }
        return local;
    }

    /** Vertices weighted to {@code node} take the weights of the nearest vertex that isn't. */
    private void rebind(int node, double[][] restAll) {
        Set<Integer> slots = new LinkedHashSet<>();
        for (int s = 0; s < rig.slotNode.length; s++) if (rig.slotNode[s] == node) slots.add(s);
        List<Integer> users = new ArrayList<>(), donors = new ArrayList<>();
        for (int i = 0; i < rig.verts.size(); i++) {
            Rig.Vert v = rig.verts.get(i);
            if (v.rigid) continue;
            boolean uses = false;
            for (int k = 0; k < 4; k++) if (v.weights[k] > 0 && slots.contains(v.joints[k])) uses = true;
            (uses ? users : donors).add(i);
        }
        for (int i : users) {
            double best = Double.MAX_VALUE;
            int bi = -1;
            for (int d : donors) {
                double dd = Mat.dist2(restAll[i], restAll[d]);
                if (dd < best) {
                    best = dd;
                    bi = d;
                }
            }
            if (bi < 0) continue;
            rig.verts.get(i).joints = rig.verts.get(bi).joints.clone();
            rig.verts.get(i).weights = rig.verts.get(bi).weights.clone();
        }
    }
}
