package ai.resourcepack.engine3d.runtime;

import ai.resourcepack.engine3d.bake.Manifest;
import ai.resourcepack.engine3d.pack.PackBuilder;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.components.CustomModelDataComponent;

import java.util.List;

/**
 * Plays a model's game models on one item display, frame-accurately.
 *
 * obj³ reads its clock from the item's tint colour (here the first
 * {@code custom_model_data} colour):
 * <ul>
 *   <li>a LOOP is autoplay with an offset: the client shows frame 0 whenever
 *   {@code (gameTime % 24000 - offset) % ticks == 0}, so picking the offset
 *   starts it on frame 0 now, and the server always knows the frame;</li>
 *   <li>a TRANSITION strip is play-once (bit 15): the client shows frame
 *   {@code gameTime % 24000 - start}, one per tick, so a start of
 *   {@code now - range.start} begins that range now.</li>
 * </ul>
 * A switch out of a loop waits for one of its sync points (or takes the
 * nearest, for states that don't say to wait), plays the range that begins on
 * that pose, and starts the target loop on frame 0 the tick the range lands.
 */
final class Animator {

    private final LoadedModel model;
    private final ItemDisplay display;
    private final Manifest manifest;

    private String state;
    private long loopStart;
    /** Non-null while a transition strip is playing. */
    private String toward;
    private Manifest.Pair pair;
    private Manifest.Range range;
    private long transitionStart;
    private String shownModel;
    private int shownColour = -1;
    /** The head's own display, when the head turns on its own; null otherwise. */
    ItemDisplay head;
    /** Told (from, to) as each new loop starts. */
    java.util.function.BiConsumer<String, String> changed;

    Animator(LoadedModel model, ItemDisplay display, String initial, long now) {
        this.model = model;
        this.manifest = model.manifest;
        this.display = display;
        if (!manifest.states.containsKey(initial)) initial = manifest.defaultState;
        startLoop(initial, now);
    }

    String state() {
        return toward != null ? state + ">" + toward : state;
    }

    String current() {
        return state;
    }

    boolean settledIn(String s) {
        return toward == null && state.equals(s);
    }

    long loopPhase(long now) {
        return Math.floorMod(now - loopStart, manifest.states.get(state).ticks);
    }

    /** Called every tick with the state wanted. */
    void tick(String desired, long now) {
        if (toward != null) {
            if (now < transitionStart + range.length) return;
            startLoop(toward, now);
            toward = null;
        }
        Manifest.State current = manifest.states.get(state);
        long phase = loopPhase(now);
        // obj³'s clock is game time mod 24000, not a multiple of the loop:
        // re-anchor the offset the tick it wraps, or the loop would jump.
        if (Math.floorMod(now, 24000) == 0) show(current.model, loopColour(now, phase, current.ticks));
        if (desired == null || desired.equals(state) || !manifest.states.containsKey(desired)) return;

        String next = manifest.nextHop(state, desired);
        if (next == null) {
            // No transition authored: cut straight to it.
            startLoop(desired, now);
            return;
        }
        Manifest.Pair p = manifest.transitions.get(state).to.get(next);
        Manifest.Range chosen = null;
        if (current.wait) {
            for (Manifest.Range r : p.ranges) if (r.sync == phase) chosen = r;
            if (chosen == null) return; // the next sync point comes round within a fraction of a cycle
        } else {
            long best = Long.MAX_VALUE;
            for (Manifest.Range r : p.ranges) {
                long d = Math.floorMod(r.sync - phase, current.ticks);
                d = Math.min(d, current.ticks - d);
                if (d < best) {
                    best = d;
                    chosen = r;
                }
            }
        }
        int start = Math.floorMod(Math.floorMod(now, 24000) - chosen.start, 24000);
        show(p.model, 32768 + start);
        toward = next;
        pair = p;
        range = chosen;
        transitionStart = now;
    }

    /** Point {@code name} in the model's own space as the client draws it at {@code now}, or null. */
    float[] point(String name, long now) {
        Manifest.Model m;
        double frame;
        boolean loop;
        if (toward != null) {
            m = manifest.models.get(pair.model);
            frame = Math.min(range.start + (now - transitionStart), m.frames - 1);
            loop = false;
        } else {
            Manifest.State s = manifest.states.get(state);
            m = manifest.models.get(s.model);
            frame = (double) loopPhase(now) * m.frames / s.ticks;
            loop = true;
        }
        List<float[]> track = m.points.get(name);
        if (track == null || track.isEmpty()) return null;
        int a = (int) Math.floor(frame);
        int b = loop ? (a + 1) % m.frames : Math.min(a + 1, m.frames - 1);
        float t = (float) (frame - a);
        float[] p = track.get(Math.min(a, track.size() - 1)), q = track.get(Math.min(b, track.size() - 1));
        return new float[]{p[0] + (q[0] - p[0]) * t, p[1] + (q[1] - p[1]) * t, p[2] + (q[2] - p[2]) * t};
    }

    private void startLoop(String name, long now) {
        String was = state;
        state = name;
        loopStart = now;
        toward = null;
        Manifest.State s = manifest.states.get(name);
        show(s.model, loopColour(now, 0, s.ticks));
        if (was != null && !was.equals(name) && changed != null) changed.accept(was, name);
    }

    private static int loopColour(long now, long phase, int ticks) {
        return (int) Math.floorMod(Math.floorMod(now, 24000) - phase, ticks);
    }

    /** Re-sends what is showing (after the display was re-created or re-skinned). */
    void refresh() {
        String m = shownModel;
        int c = shownColour;
        shownModel = null;
        show(m, c);
    }

    private void show(String id, int colour) {
        if (id.equals(shownModel) && colour == shownColour) return;
        shownModel = id;
        shownColour = colour;
        display.setItemStack(item(model.name(), id, colour));
        String h = model.head(id);
        if (head != null && h != null) head.setItemStack(item(model.name(), h, colour));
    }

    /** An item showing game model {@code id} of {@code model} with the clock at {@code colour}. */
    static ItemStack item(String model, String id, int colour) {
        ItemStack item = new ItemStack(Material.PAPER);
        ItemMeta meta = item.getItemMeta();
        String key = PackBuilder.itemModel(model, id);
        meta.setItemModel(NamespacedKey.fromString(key));
        CustomModelDataComponent data = meta.getCustomModelDataComponent();
        data.setColors(List.of(Color.fromRGB(colour & 0xffffff)));
        meta.setCustomModelDataComponent(data);
        item.setItemMeta(meta);
        return item;
    }
}
