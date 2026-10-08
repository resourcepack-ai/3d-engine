package ai.resourcepack.engine3d.bake;

import org.yaml.snakeyaml.Yaml;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * How to turn one model file into game models: the optional {@code <name>.yml}
 * beside it. Every key is optional; a bare file becomes one looping state per
 * animation clip (or one still state if it has none), at its own size.
 */
public final class Spec {

    /** Blocks per file unit; ignored when {@link #height} is set. */
    public double scale = 1;
    /** The model's height in blocks at rest, if it should be resized to that. */
    public Double height;
    /** Degrees to turn the model about Y so that its front faces the entity's way (+Z in the file is "behind"). */
    public double yaw;
    public double[] offset = {0, 0, 0};
    public int triangles = 4000;
    public int textureWidth = 256;
    /** How big the model is drawn as an inventory icon (1 = a block fills the slot); null = fitted. */
    public Double iconScale;
    /** Another file whose clips drive this one (same bone names). */
    public String animationsFrom;
    /** Bones whose translations are carried over when retargeting; null = all. */
    public List<String> retargetTranslate;
    public List<String> rebind = new ArrayList<>();
    public List<Leg> legs = new ArrayList<>();
    public Map<String, Point> points = new LinkedHashMap<>();
    public Map<String, State> states = new LinkedHashMap<>();
    public List<Transition> transitions = new ArrayList<>();
    public int transitionFrames = 6;
    public String defaultState;
    /** Kept as written, for the runtime (locomotion thresholds and state names). */
    public Map<String, Object> locomotion = new LinkedHashMap<>();
    /** The rest of the file, for plugins that keep their own settings beside a model. */
    public Map<String, Object> raw = new LinkedHashMap<>();

    public record Leg(List<String> chain, String target) {
    }

    public record Point(String bone, double[] at) {
    }

    public record Blend(String clip, double weight, double at) {
    }

    public record PoseBone(double[] rotate, double[] translate) {
    }

    public static final class State {
        public String clip;
        public double fps = 10;
        public int syncs = 1;
        public List<Integer> syncAt;
        public boolean wait;
        public Blend blend;
        public Map<String, PoseBone> pose = new LinkedHashMap<>();
    }

    public record Transition(String from, String to, int frames) {
    }

    public static Spec parse(String yaml) {
        Spec s = new Spec();
        if (yaml == null || yaml.isBlank()) return s;
        Object loaded = new Yaml().load(yaml);
        if (!(loaded instanceof Map<?, ?> root)) return s;
        @SuppressWarnings("unchecked") Map<String, Object> m = (Map<String, Object>) root;
        s.raw = m;
        if (m.containsKey("scale")) s.scale = num(m.get("scale"));
        if (m.containsKey("height")) s.height = num(m.get("height"));
        if (m.containsKey("yaw")) s.yaw = num(m.get("yaw"));
        if (m.containsKey("offset")) s.offset = vec(m.get("offset"));
        if (m.containsKey("triangles")) s.triangles = (int) num(m.get("triangles"));
        if (m.containsKey("texture-width")) s.textureWidth = (int) num(m.get("texture-width"));
        if (m.containsKey("icon-scale")) s.iconScale = num(m.get("icon-scale"));
        if (m.containsKey("animations-from")) s.animationsFrom = String.valueOf(m.get("animations-from"));
        if (m.get("retarget") instanceof Map<?, ?> r && r.get("translate") instanceof List<?> l) s.retargetTranslate = strings(l);
        if (m.get("rebind") instanceof List<?> l) s.rebind = strings(l);
        if (m.get("legs") instanceof List<?> l)
            for (Object o : l)
                if (o instanceof Map<?, ?> leg) s.legs.add(new Leg(strings((List<?>) leg.get("chain")), String.valueOf(leg.get("target"))));
        if (m.get("points") instanceof Map<?, ?> pts)
            for (var e : pts.entrySet())
                if (e.getValue() instanceof Map<?, ?> p) s.points.put(String.valueOf(e.getKey()), new Point(String.valueOf(p.get("bone")), vec(p.get("at"))));
        if (m.containsKey("transition-frames")) s.transitionFrames = (int) num(m.get("transition-frames"));
        if (m.get("states") instanceof Map<?, ?> sts)
            for (var e : sts.entrySet()) s.states.put(String.valueOf(e.getKey()), state(e.getValue()));
        if (m.get("transitions") instanceof List<?> l)
            for (Object o : l) s.transitions.addAll(transition(o, s.transitionFrames));
        if (m.containsKey("default-state")) s.defaultState = String.valueOf(m.get("default-state"));
        if (m.get("locomotion") instanceof Map<?, ?> loco)
            for (var e : loco.entrySet()) s.locomotion.put(String.valueOf(e.getKey()), e.getValue());
        return s;
    }

    private static State state(Object o) {
        State st = new State();
        if (o instanceof String clip) {
            st.clip = clip;
            return st;
        }
        if (!(o instanceof Map<?, ?> m)) return st;
        if (m.get("clip") != null) st.clip = String.valueOf(m.get("clip"));
        if (m.containsKey("fps")) st.fps = num(m.get("fps"));
        if (m.containsKey("syncs")) st.syncs = (int) num(m.get("syncs"));
        if (m.get("sync-at") instanceof List<?> l) {
            st.syncAt = new ArrayList<>();
            for (Object x : l) st.syncAt.add((int) num(x));
        }
        if (m.containsKey("wait")) st.wait = Boolean.TRUE.equals(m.get("wait"));
        if (m.get("blend") instanceof Map<?, ?> b)
            st.blend = new Blend(String.valueOf(b.get("clip")), b.containsKey("weight") ? num(b.get("weight")) : 0.5, b.containsKey("at") ? num(b.get("at")) : 0);
        if (m.get("pose") instanceof Map<?, ?> pose)
            for (var e : pose.entrySet())
                if (e.getValue() instanceof Map<?, ?> p)
                    st.pose.put(String.valueOf(e.getKey()), new PoseBone(p.containsKey("rotate") ? vec(p.get("rotate")) : null, p.containsKey("translate") ? vec(p.get("translate")) : null));
        return st;
    }

    /** {@code {from, to, frames}}, or a string "a -> b" / "a <-> b" (both ways). */
    private static List<Transition> transition(Object o, int defaultFrames) {
        List<Transition> out = new ArrayList<>();
        if (o instanceof String text) {
            boolean both = text.contains("<->");
            String[] parts = text.split("<->|->");
            if (parts.length != 2) return out;
            String a = parts[0].trim(), b = parts[1].trim();
            out.add(new Transition(a, b, defaultFrames));
            if (both) out.add(new Transition(b, a, defaultFrames));
        } else if (o instanceof Map<?, ?> m) {
            int frames = m.containsKey("frames") ? (int) num(m.get("frames")) : defaultFrames;
            String a = String.valueOf(m.get("from")), b = String.valueOf(m.get("to"));
            out.add(new Transition(a, b, frames));
            if (Boolean.TRUE.equals(m.get("both-ways"))) out.add(new Transition(b, a, frames));
        }
        return out;
    }

    static double num(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        return Double.parseDouble(String.valueOf(o));
    }

    static double[] vec(Object o) {
        if (o instanceof List<?> l) {
            double[] v = new double[l.size()];
            for (int i = 0; i < v.length; i++) v[i] = num(l.get(i));
            return v;
        }
        double x = num(o);
        return new double[]{x, x, x};
    }

    static List<String> strings(List<?> l) {
        List<String> out = new ArrayList<>();
        if (l != null) for (Object o : l) out.add(String.valueOf(o));
        return out;
    }
}
