package ai.resourcepack.engine3d.runtime;

import ai.resourcepack.engine3d.api.Model;
import ai.resourcepack.engine3d.api.ModelInstance;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

/** One model in the world: an item display and the animator playing on it. */
final class Instance implements ModelInstance {

    static final NamespacedKey MODEL = new NamespacedKey("3dengine", "model");
    static final NamespacedKey STATE = new NamespacedKey("3dengine", "state");
    static final NamespacedKey SCALE = new NamespacedKey("3dengine", "scale");

    LoadedModel model;
    final ItemDisplay display;
    Animator animator;
    private String desired;
    private double scale = 1;
    private boolean removed;
    /** Set when a mover drives this instance. */
    Steering mover;

    Instance(LoadedModel model, ItemDisplay display, String state, long now) {
        this.model = model;
        this.display = display;
        this.desired = state != null && model.manifest.states.containsKey(state) ? state : model.defaultState();
        PersistentDataContainer pdc = display.getPersistentDataContainer();
        Double s = pdc.get(SCALE, PersistentDataType.DOUBLE);
        if (s != null) scale = s;
        this.animator = new Animator(model, display, desired, now);
    }

    static void setUp(ItemDisplay d, boolean persistent) {
        d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
        d.setTeleportDuration(2);
        d.setPersistent(persistent);
        d.addScoreboardTag("3dengine");
    }

    /** Swaps in a re-baked model, keeping where it is and what it was asked to do. */
    void rebind(LoadedModel fresh, long now) {
        model = fresh;
        if (!fresh.manifest.states.containsKey(desired)) desired = fresh.defaultState();
        animator = new Animator(fresh, display, desired, now);
    }

    void tick(long now) {
        animator.tick(desired, now);
    }

    @Override
    public Model model() {
        return model;
    }

    @Override
    public ItemDisplay display() {
        return display;
    }

    @Override
    public Location location() {
        return display.getLocation();
    }

    @Override
    public void teleport(Location at) {
        Location to = at.clone();
        to.setPitch(0);
        display.teleport(to);
    }

    @Override
    public void state(String state) {
        if (state == null || !model.manifest.states.containsKey(state)) return;
        desired = state;
        if (display.isPersistent()) display.getPersistentDataContainer().set(STATE, PersistentDataType.STRING, state);
    }

    @Override
    public String state() {
        return animator.state();
    }

    @Override
    public boolean settledIn(String state) {
        return animator.settledIn(state);
    }

    @Override
    public long phase() {
        return animator.loopPhase(display.getWorld().getGameTime());
    }

    @Override
    public Location point(String name, int leadTicks) {
        float[] m = animator.point(name, display.getWorld().getGameTime() + leadTicks);
        if (m == null) return null;
        // The model faces -z and the display turns it by its yaw: forward (-z
        // in the model) is (-sin, cos) in the world, the model's +x is
        // (-cos, -sin); and the display draws the model's y=0 half a block down.
        Location at = display.getLocation();
        double yaw = Math.toRadians(at.getYaw());
        double forward = -m[2], side = m[0];
        double x = forward * -Math.sin(yaw) + side * -Math.cos(yaw);
        double z = forward * Math.cos(yaw) + side * -Math.sin(yaw);
        return at.add(x * scale, (m[1] - 0.5) * scale, z * scale);
    }

    @Override
    public double scale() {
        return scale;
    }

    @Override
    public void scale(double s) {
        scale = Math.max(0.01, s);
        display.setTransformation(new Transformation(new Vector3f(), new Quaternionf(), new Vector3f((float) scale, (float) scale, (float) scale), new Quaternionf()));
        if (display.isPersistent()) display.getPersistentDataContainer().set(SCALE, PersistentDataType.DOUBLE, scale);
    }

    @Override
    public boolean persistent() {
        return display.isPersistent();
    }

    @Override
    public boolean valid() {
        return !removed && display.isValid();
    }

    @Override
    public void remove() {
        removed = true;
        if (mover != null) mover.removeBrain();
        display.remove();
    }

    boolean removed() {
        return removed;
    }
}
