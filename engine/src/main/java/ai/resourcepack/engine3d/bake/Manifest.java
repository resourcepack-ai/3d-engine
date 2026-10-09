package ai.resourcepack.engine3d.bake;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a baked model is, for the runtime: its looping states, the transitions
 * between them (a strip of ranges, one per sync point of the source), and per
 * game model its frame count, length and the attachment points frame by frame.
 * Written beside the baked files as {@code manifest.json}.
 */
public final class Manifest {

    /** Bumped whenever the bake's output changes, so old caches are rebuilt. */
    public static final int BAKE_VERSION = 4;

    public String name;
    public String hash;
    public int version = BAKE_VERSION;
    /** Size at rest in blocks: height, and the largest distance from the centre line. */
    public double height;
    public double radius;
    public String defaultState;
    public Map<String, State> states = new LinkedHashMap<>();
    public Map<String, Out> transitions = new LinkedHashMap<>();
    public Map<String, Model> models = new LinkedHashMap<>();
    public Map<String, Object> locomotion = new LinkedHashMap<>();
    /** Held as an item: every game model has a model per hand and slot (see ObjCubed.Result#slotModel). */
    public boolean item;
    /**
     * Its box at rest, in the model's own space (blocks, y up from half a block
     * below the entity, front -z): min x, y, z, then max x, y, z.
     */
    public double[] bounds;
    /**
     * Set when the head turns on its own: every game model {@code id} then
     * draws the body only, {@code id + LOOK} the head, and the point
     * {@link #LOOK_PIVOT} is the neck it turns on.
     */
    public String lookBone;
    /** The whole model at rest, for an icon, when the game models are split. */
    public String icon;
    /** The texture height of every game model, summed: what the model costs the client's item atlas. */
    public long texels;

    public static final String LOOK = ".look";
    public static final String LOOK_PIVOT = "_look";
    /** A seat that rides a bone is the point {@code SEAT + its name}. */
    public static final String SEAT = "_seat:";

    public static final class State {
        public String model;
        /** One loop, in ticks. */
        public int ticks;
        /** Ticks into the loop at which a transition out may start. */
        public List<Integer> syncs;
        /** Wait for the next sync point (locomotion) rather than take the nearest. */
        public boolean wait;
    }

    public static final class Out {
        public Map<String, Pair> to = new LinkedHashMap<>();
    }

    public static final class Pair {
        public String model;
        public List<Range> ranges;
    }

    public static final class Range {
        /** The sync point of the source state this range starts on. */
        public int sync;
        /** First frame of the range in the strip. */
        public int start;
        /** Ticks until the strip shows the target's frame 0. */
        public int length;
    }

    public static final class Model {
        /** "loop", "oneshot", "look" (a head) or "icon". */
        public String kind;
        public int frames;
        public int ticks;
        /** Attachment points in the model's own space (blocks, y up, front -z), per frame. */
        public Map<String, List<float[]>> points = new LinkedHashMap<>();
    }

    private static final Gson GSON = new GsonBuilder().create();

    public String toJson() {
        return GSON.toJson(this);
    }

    public static Manifest fromJson(String json) {
        return GSON.fromJson(json, Manifest.class);
    }

    /** The first state to move to on the way from {@code from} to {@code to}, or null if there is no way. */
    public String nextHop(String from, String to) {
        if (from.equals(to)) return null;
        Map<String, String> came = new HashMap<>();
        ArrayDeque<String> queue = new ArrayDeque<>();
        queue.add(from);
        came.put(from, from);
        while (!queue.isEmpty()) {
            String at = queue.poll();
            Out out = transitions.get(at);
            if (out == null) continue;
            for (String next : out.to.keySet()) {
                if (came.containsKey(next)) continue;
                came.put(next, at);
                if (next.equals(to)) {
                    String step = next;
                    while (!came.get(step).equals(from)) step = came.get(step);
                    return step;
                }
                queue.add(next);
            }
        }
        return null;
    }
}
