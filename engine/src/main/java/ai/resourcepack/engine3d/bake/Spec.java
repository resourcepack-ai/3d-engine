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
    /** Parts of the file left out (a ground plane, a backdrop, a camera rig): nodes or bones, with everything under them. */
    public List<String> hide = new ArrayList<>();
    public List<Leg> legs = new ArrayList<>();
    public Map<String, Point> points = new LinkedHashMap<>();
    public Map<String, State> states = new LinkedHashMap<>();
    public List<Transition> transitions = new ArrayList<>();
    public int transitionFrames = 6;
    public String defaultState;
    /** Kept as written, for the runtime (locomotion thresholds and state names). */
    public Map<String, Object> locomotion = new LinkedHashMap<>();
    /**
     * Set when the model is held as an item: per display context
     * ({@code firstperson_righthand}, {@code thirdperson_righthand}, ...) its
     * rotation, translation (in 1/16 block) and scale, as a block model's
     * {@code display}. Contexts it doesn't name use the identity.
     */
    public Map<String, double[][]> item;
    /** Turning to look at players: the head alone (with a bone) or the whole model; null = it doesn't. */
    public Look look;
    /** Places to sit, by name, in the order a right-click fills them. */
    public Map<String, Seat> seats = new LinkedHashMap<>();
    /** Whether it can be clicked and hit at all. */
    public boolean hitbox = true;
    /** Boxes as written; null = fitted to the model. */
    public List<Box> hitboxes;
    /** Placed models fill their boxes with barriers, so nothing walks through them. */
    public boolean solid;
    /** Ridden from a seat: where the rider looks, it goes. Null = it stays put. */
    public Drive drive;
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

    /**
     * Where a model looks. With a {@code bone} the head (that bone and
     * everything below it) turns on the neck while the rest plays on; without
     * one the whole model turns. Angles are the most it turns either way.
     */
    public static final class Look {
        public String bone;
        /** More bones that turn with the head, when the rig hangs them beside it rather than under it. */
        public List<String> with = new ArrayList<>();
        public double yaw = 70, pitch = 35, range = 8, speed = 9;
        /** States it looks in; null = all. */
        public List<String> states;
    }

    /**
     * A seat: {@code at} in blocks from the middle of the model's base (x to
     * its right, y up, z forward), carried along by {@code bone} if it has one.
     */
    public record Seat(double[] at, String bone, boolean sitting) {
    }

    /** A box in blocks: the middle of its bottom face {@code at} (x right, y up, z forward), square {@code width}, {@code height} tall. */
    public record Box(double[] at, double width, double height) {
    }

    public static final class Drive {
        /** The seat whose rider steers; null = the first. */
        public String seat;
        /** Blocks per tick forward, sprinting, and backward. */
        public double speed = 0.2, sprint = 0.32, back = 0.08;
        /** Degrees per tick it turns towards where the rider looks. */
        public double turn = 7;
        /** Upward speed of a jump; 0 = it can't. */
        public double jump = 0.42;
        /** The invisible mob that walks it (its size decides what it fits through); null = by the model's size. */
        public String brain;
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
        if (m.get("hide") instanceof List<?> l) s.hide = strings(l);
        else if (m.get("hide") instanceof String one) s.hide = List.of(one);
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
        if (m.get("item") instanceof Map<?, ?> it) {
            s.item = new LinkedHashMap<>();
            for (var e : it.entrySet()) {
                if (!(e.getValue() instanceof Map<?, ?> d)) continue;
                s.item.put(String.valueOf(e.getKey()), new double[][]{
                        d.containsKey("rotation") ? vec(d.get("rotation")) : new double[]{0, 0, 0},
                        d.containsKey("translation") ? vec(d.get("translation")) : new double[]{0, 0, 0},
                        d.containsKey("scale") ? vec(d.get("scale")) : new double[]{1, 1, 1}});
            }
        } else if (Boolean.TRUE.equals(m.get("item"))) {
            s.item = new LinkedHashMap<>();
        }
        if (m.get("look") instanceof Map<?, ?> lk) {
            Look l = new Look();
            if (lk.get("bone") != null) l.bone = String.valueOf(lk.get("bone"));
            if (lk.get("with") instanceof List<?> w) l.with = strings(w);
            if (lk.containsKey("yaw")) l.yaw = num(lk.get("yaw"));
            if (lk.containsKey("pitch")) l.pitch = num(lk.get("pitch"));
            if (lk.containsKey("range")) l.range = num(lk.get("range"));
            if (lk.containsKey("speed")) l.speed = num(lk.get("speed"));
            if (lk.get("states") instanceof List<?> st) l.states = strings(st);
            s.look = l;
        } else if (Boolean.TRUE.equals(m.get("look"))) {
            s.look = new Look();
        } else if (m.get("look") instanceof String bone) {
            s.look = new Look();
            s.look.bone = bone;
        }
        if (m.get("seats") instanceof Map<?, ?> seats)
            for (var e : seats.entrySet()) {
                if (e.getValue() instanceof Map<?, ?> st)
                    s.seats.put(String.valueOf(e.getKey()), new Seat(st.containsKey("at") ? vec(st.get("at")) : new double[]{0, 0, 0},
                            st.get("bone") != null ? String.valueOf(st.get("bone")) : null, !"standing".equals(String.valueOf(st.get("pose")))));
                else if (e.getValue() instanceof List<?> at)
                    s.seats.put(String.valueOf(e.getKey()), new Seat(vec(at), null, true));
            }
        if (Boolean.FALSE.equals(m.get("hitbox"))) s.hitbox = false;
        else if (m.get("hitbox") instanceof Map<?, ?> hb) s.hitboxes = List.of(box(hb));
        if (m.get("hitboxes") instanceof List<?> l) {
            s.hitboxes = new ArrayList<>();
            for (Object o : l) if (o instanceof Map<?, ?> hb) s.hitboxes.add(box(hb));
        }
        if (Boolean.TRUE.equals(m.get("solid"))) s.solid = true;
        if (m.get("drive") instanceof Map<?, ?> dr) {
            Drive d = new Drive();
            if (dr.get("seat") != null) d.seat = String.valueOf(dr.get("seat"));
            if (dr.containsKey("speed")) d.speed = num(dr.get("speed"));
            d.sprint = dr.containsKey("sprint") ? num(dr.get("sprint")) : Math.max(d.speed, d.speed * 1.6);
            if (dr.containsKey("back")) d.back = num(dr.get("back"));
            if (dr.containsKey("turn")) d.turn = num(dr.get("turn"));
            if (dr.containsKey("jump")) d.jump = num(dr.get("jump"));
            if (dr.get("brain") != null) d.brain = String.valueOf(dr.get("brain"));
            s.drive = d;
        } else if (Boolean.TRUE.equals(m.get("drive"))) {
            s.drive = new Drive();
        }
        if (m.get("locomotion") instanceof Map<?, ?> loco)
            for (var e : loco.entrySet()) s.locomotion.put(String.valueOf(e.getKey()), e.getValue());
        return s;
    }

    private static Box box(Map<?, ?> m) {
        double[] at = m.containsKey("at") ? vec(m.get("at")) : new double[]{0, 0, 0};
        return new Box(at, m.containsKey("width") ? num(m.get("width")) : 1, m.containsKey("height") ? num(m.get("height")) : 1);
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
