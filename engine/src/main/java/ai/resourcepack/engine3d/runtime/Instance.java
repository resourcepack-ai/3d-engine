package ai.resourcepack.engine3d.runtime;

import ai.resourcepack.engine3d.api.Model;
import ai.resourcepack.engine3d.api.ModelInstance;
import ai.resourcepack.engine3d.api.event.ModelDismountEvent;
import ai.resourcepack.engine3d.api.event.ModelMountEvent;
import ai.resourcepack.engine3d.api.event.ModelRemoveEvent;
import ai.resourcepack.engine3d.api.event.ModelStateChangeEvent;
import ai.resourcepack.engine3d.bake.Manifest;
import ai.resourcepack.engine3d.bake.Spec;
import io.papermc.paper.entity.TeleportFlag;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.LivingEntity;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.util.Transformation;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One model in the world: an item display and the animator playing on it, and
 * the parts that go with it.
 * <ul>
 *   <li>a second display for the head, when the head turns on its own: it
 *   plays the same clock as the body and is turned on its neck by its
 *   transformation;</li>
 *   <li>interaction entities, the boxes a player clicks and hits;</li>
 *   <li>barrier blocks filling those boxes, for a solid model that stays put;</li>
 *   <li>a mount per occupied seat, an empty display carried along to wherever
 *   the seat is this tick.</li>
 * </ul>
 * None of the parts is saved: they are made again whenever the model is.
 */
final class Instance implements ModelInstance {

    static final NamespacedKey MODEL = new NamespacedKey("3dengine", "model");
    static final NamespacedKey STATE = new NamespacedKey("3dengine", "state");
    static final NamespacedKey SCALE = new NamespacedKey("3dengine", "scale");
    static final NamespacedKey SOLID = new NamespacedKey("3dengine", "solid");
    static final NamespacedKey NO_HITBOX = new NamespacedKey("3dengine", "no_hitbox");

    /**
     * How far below an entity with no size (a display) a player riding it ends
     * up: a passenger sits at the vehicle's attachment point less its own.
     * Measured on 26.3 with a display mount at y=-59.55 and its rider at -60.15.
     */
    static final double MOUNT_DROP = 0.6;
    /** A sitting player's hips are this far above their feet; a seat's point is where they sit. */
    static final double HIP = 12 / 16.0;
    /** A head that is this close to straight is drawn straight, and stops being re-sent. */
    private static final float STILL = 0.3f;

    final Engine engine;
    LoadedModel model;
    final ItemDisplay display;
    Animator animator;
    private String desired;
    private double scale = 1;
    private boolean removed;
    /** Set when a mover drives this instance. */
    Steering mover;

    private ItemDisplay head;
    private final List<Interaction> boxes = new ArrayList<>();
    /** Per box, in the model's own space: middle x, bottom y, middle z, width, height. */
    private List<double[]> layout = List.of();
    private boolean hitboxOn = true;
    /** Barriers this model put down, as block keys in its world. */
    private long[] solid = new long[0];
    private String laidOut;

    record Seated(String seat, ItemDisplay mount, Entity rider) {
    }

    private final Map<String, Seated> seated = new LinkedHashMap<>();
    /** Set while the engine takes a rider off, so the dismount can't be refused. */
    private boolean forcing;

    private enum Look {NEAREST, ENTITY, POINT, AWAY}

    private Look lookMode = Look.NEAREST;
    private Entity lookEntity;
    private Location lookPoint;
    private float lookYaw, lookPitch;
    private boolean bent;

    Instance(Engine engine, LoadedModel model, ItemDisplay display, String state, long now) {
        this.engine = engine;
        this.model = model;
        this.display = display;
        this.desired = state != null && model.manifest.states.containsKey(state) ? state : model.defaultState();
        PersistentDataContainer pdc = display.getPersistentDataContainer();
        Double s = pdc.get(SCALE, PersistentDataType.DOUBLE);
        if (s != null) scale = s;
        long[] placed = pdc.get(SOLID, PersistentDataType.LONG_ARRAY);
        if (placed != null) solid = placed;
        hitboxOn = !pdc.has(NO_HITBOX);
        this.animator = animator(now);
    }

    static void setUp(ItemDisplay d, boolean persistent) {
        d.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.NONE);
        d.setTeleportDuration(2);
        d.setPersistent(persistent);
        d.addScoreboardTag("3dengine");
    }

    private Animator animator(long now) {
        Animator a = new Animator(model, display, desired, now);
        a.changed = (from, to) -> engine.call(new ModelStateChangeEvent(this, from, to));
        return a;
    }

    /** Makes the parts: called once the engine holds the instance. */
    void build() {
        layout = boxesOf(model);
        if (model.manifest.lookBone != null && head == null) {
            head = display.getWorld().spawn(display.getLocation(), ItemDisplay.class, x -> {
                setUp(x, false);
                x.setTransformation(scaled());
            });
            engine.own(head, this);
        }
        animator.head = head;
        animator.refresh();
        applyBody();
        laidOut = null;
        layOut();
    }

    /** Swaps in a re-baked model, keeping where it is and what it was asked to do. */
    void rebind(LoadedModel fresh, long now) {
        model = fresh;
        if (!fresh.manifest.states.containsKey(desired)) desired = fresh.defaultState();
        for (String seat : new ArrayList<>(seated.keySet())) if (!fresh.spec.seats.containsKey(seat)) release(seat, true);
        if (fresh.manifest.lookBone == null && head != null) {
            engine.disown(head);
            head.remove();
            head = null;
        }
        lookYaw = lookPitch = 0;
        bent = false;
        animator = animator(now);
        build();
    }

    void tick(long now) {
        animator.tick(desired, now);
        look(now);
        layOut();
        carry(now);
        drive();
    }

    // ---- placement --------------------------------------------------------------------

    /** The way the model faces: the display's yaw, less any turn of the whole model towards what it looks at. */
    private float facing(boolean withLook) {
        float yaw = display.getLocation().getYaw();
        return withLook && head == null ? yaw - lookYaw : yaw;
    }

    /** A point in the model's own space (blocks, front -z, y from half a block below the entity) in the world. */
    Location toWorld(double[] m, boolean withLook) {
        // The model faces -z and the display turns it by its yaw: forward (-z
        // in the model) is (-sin, cos) in the world, the model's +x is
        // (-cos, -sin); and the display draws the model's y=0 half a block down.
        Location at = display.getLocation();
        double yaw = Math.toRadians(facing(withLook));
        double forward = -m[2], side = m[0];
        double x = forward * -Math.sin(yaw) + side * -Math.cos(yaw);
        double z = forward * Math.cos(yaw) + side * -Math.sin(yaw);
        return at.add(x * scale, (m[1] - 0.5) * scale, z * scale);
    }

    private Transformation scaled() {
        float s = (float) scale;
        return new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(s, s, s), new Quaternionf());
    }

    /** The body's transformation: its scale, turned on the spot when the whole model looks. */
    private void applyBody() {
        float s = (float) scale;
        Quaternionf turn = head == null ? new Quaternionf().rotateY((float) Math.toRadians(lookYaw)) : new Quaternionf();
        display.setInterpolationDelay(0);
        display.setInterpolationDuration(2);
        display.setTransformation(new Transformation(new Vector3f(), turn, new Vector3f(s, s, s), new Quaternionf()));
    }

    // ---- hitboxes ---------------------------------------------------------------------

    /** The boxes as written, or fitted: one square box, or a row of them along a long model. */
    static List<double[]> boxesOf(LoadedModel m) {
        Spec spec = m.spec;
        if (!spec.hitbox) return List.of();
        List<double[]> out = new ArrayList<>();
        if (spec.hitboxes != null) {
            for (Spec.Box b : spec.hitboxes) out.add(new double[]{b.at()[0], b.at()[1] + 0.5, -b.at()[2], b.width(), b.height()});
            return out;
        }
        double[] b = m.manifest.bounds;
        if (b == null) {
            double r = Math.max(0.3, m.manifest.radius);
            out.add(new double[]{0, 0.5, 0, r * 2, Math.max(0.3, m.manifest.height)});
            return out;
        }
        double ex = b[3] - b[0], ez = b[5] - b[2], h = Math.max(0.2, b[4] - b[1]);
        double cx = (b[0] + b[3]) / 2, cz = (b[2] + b[5]) / 2;
        boolean alongX = ex >= ez;
        double longSide = Math.max(ex, ez), shortSide = Math.max(0.4, Math.min(ex, ez));
        int n = (int) Math.min(6, Math.max(1, Math.ceil(longSide / shortSide - 0.25)));
        if (n == 1) {
            out.add(new double[]{cx, b[1], cz, Math.max(0.4, longSide), h});
            return out;
        }
        double w = Math.max(shortSide, longSide / n);
        double first = (alongX ? b[0] : b[2]) + w / 2, step = (longSide - w) / (n - 1);
        for (int i = 0; i < n; i++) {
            double along = first + step * i;
            out.add(alongX ? new double[]{along, b[1], cz, w, h} : new double[]{cx, b[1], along, w, h});
        }
        return out;
    }

    /** Puts the boxes (and barriers) where the model is, when it has moved, turned or changed size. */
    private void layOut() {
        boolean on = hitboxOn && !layout.isEmpty();
        Location at = display.getLocation();
        String key = on ? String.format("%s %.3f %.3f %.3f %.1f %.3f", at.getWorld().getName(), at.getX(), at.getY(), at.getZ(), facing(true), scale) : "off";
        if (key.equals(laidOut)) return;
        laidOut = key;
        if (!on) {
            for (Interaction b : boxes) {
                engine.disown(b);
                b.remove();
            }
            boxes.clear();
            clearSolid();
            return;
        }
        while (boxes.size() > layout.size()) {
            Interaction b = boxes.remove(boxes.size() - 1);
            engine.disown(b);
            b.remove();
        }
        for (int i = 0; i < layout.size(); i++) {
            double[] box = layout.get(i);
            Location where = toWorld(box, true);
            if (i < boxes.size() && boxes.get(i).isValid()) boxes.get(i).teleport(where);
            else {
                Interaction b = where.getWorld().spawn(where, Interaction.class, x -> {
                    x.setPersistent(false);
                    x.setResponsive(true);
                    x.addScoreboardTag("3dengine");
                });
                engine.own(b, this);
                for (Seated s : seated.values()) if (s.rider instanceof Player p) p.hideEntity(engine.plugin(), b);
                if (i < boxes.size()) boxes.set(i, b);
                else boxes.add(b);
            }
            boxes.get(i).setInteractionWidth((float) (box[3] * scale));
            boxes.get(i).setInteractionHeight((float) (box[4] * scale));
        }
        if (model.spec.solid && display.isPersistent() && mover == null) fillSolid();
        else clearSolid();
    }

    /** Barriers in every empty block whose middle is inside a box, leaving room for whoever sits in it. */
    private void fillSolid() {
        clearSolid();
        World w = display.getWorld();
        List<Long> placed = new ArrayList<>();
        List<double[]> riders = new ArrayList<>();
        for (String seat : model.spec.seats.keySet()) riders.add(seatSpot(seat, w.getGameTime()));
        for (double[] box : layout) {
            Location c = toWorld(box, false);
            double half = box[3] * scale / 2, h = box[4] * scale;
            for (int x = (int) Math.floor(c.getX() - half); x <= Math.floor(c.getX() + half); x++)
                for (int y = (int) Math.floor(c.getY()); y <= Math.floor(c.getY() + h); y++)
                    for (int z = (int) Math.floor(c.getZ() - half); z <= Math.floor(c.getZ() + half); z++) {
                        double mx = x + 0.5, my = y + 0.5, mz = z + 0.5;
                        if (Math.abs(mx - c.getX()) > half || Math.abs(mz - c.getZ()) > half || my < c.getY() || my > c.getY() + h) continue;
                        boolean seat = false;
                        for (double[] r : riders)
                            if (Math.abs(mx - r[0]) < 0.8 && Math.abs(mz - r[2]) < 0.8 && my > r[1] - 1 && my < r[1] + 2) seat = true;
                        if (seat) continue;
                        Block b = w.getBlockAt(x, y, z);
                        if (!b.getType().isAir()) continue;
                        b.setType(Material.BARRIER, false);
                        placed.add(b.getBlockKey());
                    }
        }
        solid = placed.stream().mapToLong(Long::longValue).toArray();
        engine.solid(this, solid);
        display.getPersistentDataContainer().set(SOLID, PersistentDataType.LONG_ARRAY, solid);
    }

    void clearSolid() {
        if (solid.length == 0) return;
        World w = display.getWorld();
        for (long k : solid) {
            Block b = w.getBlockAtKey(k);
            if (b.getType() == Material.BARRIER) b.setType(Material.AIR, false);
        }
        engine.solid(this, new long[0]);
        solid = new long[0];
        if (display.isValid()) display.getPersistentDataContainer().remove(SOLID);
    }

    long[] solidBlocks() {
        return solid;
    }

    @Override
    public Collection<Entity> hitboxes() {
        return Collections.unmodifiableList(new ArrayList<>(boxes));
    }

    @Override
    public void hitbox(boolean enabled) {
        hitboxOn = enabled;
        if (display.isPersistent()) {
            if (enabled) display.getPersistentDataContainer().remove(NO_HITBOX);
            else display.getPersistentDataContainer().set(NO_HITBOX, PersistentDataType.BYTE, (byte) 1);
        }
        laidOut = null;
        layOut();
    }

    // ---- looking ----------------------------------------------------------------------

    /** Eases the head (or the whole model) towards what it should be looking at. */
    private void look(long now) {
        Spec.Look spec = model.spec.look;
        if (spec == null) return;
        boolean whole = head == null;
        float wantYaw = 0, wantPitch = 0;
        boolean allowed = spec.states == null || spec.states.contains(animator.current());
        // A whole model that walks or carries somebody faces where it is going.
        if (whole && (mover != null || !seated.isEmpty())) allowed = false;
        Location from = whole ? toWorld(new double[]{0, eyeHeight(), 0}, false) : pivot(now);
        Location eye = allowed && from != null ? target(from, spec.range) : null;
        if (eye != null) {
            double dx = eye.getX() - from.getX(), dy = eye.getY() - from.getY(), dz = eye.getZ() - from.getZ();
            double y = Math.toRadians(display.getLocation().getYaw());
            double lx = dx * Math.cos(y) + dz * Math.sin(y), lz = -dx * Math.sin(y) + dz * Math.cos(y);
            float a = (float) Math.toDegrees(Math.atan2(lx, lz));
            float b = (float) Math.toDegrees(Math.atan2(dy, Math.hypot(lx, lz)));
            // Behind it, well past where it can turn: it loses interest.
            if (Math.abs(a) <= spec.yaw + 45) {
                wantYaw = (float) Math.max(-spec.yaw, Math.min(spec.yaw, a));
                wantPitch = whole ? 0 : (float) Math.max(-spec.pitch, Math.min(spec.pitch, b));
            }
        }
        float step = (float) spec.speed;
        float yaw = lookYaw + Math.max(-step, Math.min(step, wantYaw - lookYaw));
        float pitch = lookPitch + Math.max(-step * 0.7f, Math.min(step * 0.7f, wantPitch - lookPitch));
        if (Math.abs(yaw) < STILL && wantYaw == 0) yaw = 0;
        if (Math.abs(pitch) < STILL && wantPitch == 0) pitch = 0;
        boolean changed = yaw != lookYaw || pitch != lookPitch;
        lookYaw = yaw;
        lookPitch = pitch;
        if (whole) {
            if (changed) applyBody();
            return;
        }
        boolean turned = lookYaw != 0 || lookPitch != 0;
        if (!turned && !bent) return;
        bent = turned;
        float s = (float) scale;
        Quaternionf r = new Quaternionf().rotateY((float) Math.toRadians(lookYaw)).rotateX((float) Math.toRadians(-lookPitch));
        Vector3f t = new Vector3f();
        if (turned) {
            float[] m = animator.point(Manifest.LOOK_PIVOT, now + 1);
            if (m != null) {
                // The display turns the model half a turn and draws its y=0 half a
                // block down: that, scaled, is where the neck is to the transformation.
                Vector3f p = new Vector3f(-m[0] * s, (m[1] - 0.5f) * s, -m[2] * s);
                t = new Vector3f(p).sub(new Vector3f(p).rotate(r));
            }
        }
        head.setInterpolationDelay(0);
        head.setInterpolationDuration(2);
        head.setTransformation(new Transformation(t, turned ? r : new Quaternionf(), new Vector3f(s, s, s), new Quaternionf()));
    }

    private double eyeHeight() {
        double[] b = model.manifest.bounds;
        return b == null ? 0.5 + model.manifest.height * 0.85 : b[1] + (b[4] - b[1]) * 0.85;
    }

    private Location pivot(long now) {
        float[] m = animator.point(Manifest.LOOK_PIVOT, now);
        return m == null ? null : toWorld(new double[]{m[0], m[1], m[2]}, false);
    }

    private Location target(Location from, double range) {
        return switch (lookMode) {
            case AWAY -> null;
            case POINT -> lookPoint != null && lookPoint.getWorld() == from.getWorld() && lookPoint.distanceSquared(from) <= range * range * 4 ? lookPoint : null;
            case ENTITY -> lookEntity != null && lookEntity.isValid() && lookEntity.getWorld() == from.getWorld() ? eyeOf(lookEntity) : null;
            case NEAREST -> {
                Player best = null;
                double bestD = range * range;
                for (Player p : from.getWorld().getPlayers()) {
                    if (p.getGameMode() == GameMode.SPECTATOR || riding(p)) continue;
                    double d = p.getEyeLocation().distanceSquared(from);
                    if (d < bestD) {
                        bestD = d;
                        best = p;
                    }
                }
                yield best == null ? null : best.getEyeLocation();
            }
        };
    }

    private static Location eyeOf(Entity e) {
        return e instanceof LivingEntity l ? l.getEyeLocation() : e.getLocation();
    }

    @Override
    public void lookAt(Entity target) {
        lookMode = target == null ? Look.NEAREST : Look.ENTITY;
        lookEntity = target;
    }

    @Override
    public void lookAt(Location at) {
        lookMode = at == null ? Look.NEAREST : Look.POINT;
        lookPoint = at == null ? null : at.clone();
    }

    @Override
    public void lookAround() {
        lookMode = Look.NEAREST;
    }

    @Override
    public void lookAway() {
        lookMode = Look.AWAY;
    }

    // ---- seats ------------------------------------------------------------------------

    /** Seat {@code name} in the model's own space at {@code now}. */
    private double[] seatLocal(String name, long now) {
        Spec.Seat seat = model.spec.seats.get(name);
        if (seat.bone() != null) {
            float[] m = animator.point(Manifest.SEAT + name, now);
            if (m != null) return new double[]{m[0], m[1], m[2]};
        }
        return new double[]{seat.at()[0], seat.at()[1] + 0.5, -seat.at()[2]};
    }

    /** Where a seat's sitter is, in the world: x, y, z. */
    private double[] seatSpot(String name, long now) {
        Location l = toWorld(seatLocal(name, now), true);
        return new double[]{l.getX(), l.getY(), l.getZ()};
    }

    /** Where a seat's mount goes so its rider's hips (or feet, standing) land on the seat. */
    private Location mountAt(String name, long now) {
        Location l = toWorld(seatLocal(name, now), true);
        boolean sitting = model.spec.seats.get(name).sitting();
        l.add(0, (sitting ? -HIP : 0) + MOUNT_DROP + engine.seatNudge(), 0);
        l.setYaw(0);
        l.setPitch(0);
        return l;
    }

    @Override
    public Map<String, Entity> riders() {
        Map<String, Entity> out = new LinkedHashMap<>();
        for (Seated s : seated.values()) out.put(s.seat, s.rider);
        return out;
    }

    boolean riding(Entity e) {
        for (Seated s : seated.values()) if (s.rider.equals(e)) return true;
        return false;
    }

    /** The nearest free seat to {@code from}, or null. */
    String freeSeat(Location from) {
        String best = null;
        double bestD = Double.MAX_VALUE;
        long now = display.getWorld().getGameTime();
        for (String name : model.spec.seats.keySet()) {
            if (seated.containsKey(name)) continue;
            double[] p = seatSpot(name, now);
            double d = Math.pow(p[0] - from.getX(), 2) + Math.pow(p[1] - from.getY(), 2) + Math.pow(p[2] - from.getZ(), 2);
            if (d < bestD) {
                bestD = d;
                best = name;
            }
        }
        return best;
    }

    @Override
    public boolean mount(Entity rider, String seat) {
        if (removed || rider == null || !rider.isValid() || rider.isInsideVehicle() || riding(rider)) return false;
        if (seat == null) seat = freeSeat(rider.getLocation());
        if (seat == null || !model.spec.seats.containsKey(seat) || seated.containsKey(seat)) return false;
        if (!engine.call(new ModelMountEvent(this, rider, seat))) return false;
        long now = display.getWorld().getGameTime();
        Location at = mountAt(seat, now);
        ItemDisplay mount = at.getWorld().spawn(at, ItemDisplay.class, x -> {
            x.setPersistent(false);
            x.setTeleportDuration(2);
            x.addScoreboardTag("3dengine");
        });
        // Turned to face the way the model does as they sit; after that they look where they like.
        Location face = rider.getLocation();
        face.setYaw(facing(true));
        rider.teleport(face);
        if (!mount.addPassenger(rider)) {
            mount.remove();
            return false;
        }
        engine.own(mount, this);
        seated.put(seat, new Seated(seat, mount, rider));
        if (rider instanceof Player p) for (Interaction b : boxes) p.hideEntity(engine.plugin(), b);
        if (mover == null && model.spec.solid) {
            laidOut = null; // the barriers move out of the way of a seat as it fills
        }
        return true;
    }

    @Override
    public void dismount(Entity rider) {
        for (Seated s : new ArrayList<>(seated.values())) if (s.rider.equals(rider)) release(s.seat, true);
    }

    /**
     * Called as a rider leaves a mount (they sneaked, or something took them
     * off); returns true if the dismount is to be refused.
     */
    boolean leaving(Entity rider, boolean cancellable) {
        for (Seated s : seated.values()) {
            if (!s.rider.equals(rider)) continue;
            if (forcing) return false;
            ModelDismountEvent e = new ModelDismountEvent(this, rider, s.seat, cancellable);
            engine.callEvent(e);
            if (e.isCancelled()) return true;
            seated.remove(s.seat);
            finish(s);
            return false;
        }
        return false;
    }

    /** Takes whoever is in {@code seat} out of it. */
    void release(String seat, boolean tell) {
        Seated s = seated.remove(seat);
        if (s == null) return;
        if (tell) engine.callEvent(new ModelDismountEvent(this, s.rider, seat, false));
        forcing = true;
        try {
            s.mount.removePassenger(s.rider);
        } finally {
            forcing = false;
        }
        finish(s);
    }

    /** Clears up after a rider has left: the mount goes, and they are put down beside the model. */
    private void finish(Seated s) {
        engine.disown(s.mount);
        Location beside = beside(s.seat);
        Location left = s.mount.getLocation();
        engine.later(() -> {
            if (s.mount.isValid()) s.mount.remove();
            // Unless something else has moved them since (a teleport out of the seat).
            boolean there = s.rider.getWorld() == left.getWorld() && s.rider.getLocation().distanceSquared(left) < 9;
            if (s.rider.isValid() && !s.rider.isInsideVehicle() && beside != null && there)
                s.rider.teleport(beside.setDirection(s.rider.getLocation().getDirection()));
            if (s.rider instanceof Player p) for (Interaction b : boxes) p.showEntity(engine.plugin(), b);
        });
        if (model.spec.solid) laidOut = null;
    }

    /** Off the side of the model the seat is on, on the ground, or on top of it if the side is blocked. */
    private Location beside(String seat) {
        if (!display.isValid()) return null;
        double[] b = model.manifest.bounds;
        double[] at = seatLocal(seat, display.getWorld().getGameTime());
        double side = b == null ? (at[0] >= 0 ? 1 : -1) * (model.manifest.radius + 0.6) : at[0] >= 0 ? b[3] + 0.6 / scale : b[0] - 0.6 / scale;
        Location l = toWorld(new double[]{side, 0.5, at[2]}, true);
        Block feet = l.getBlock();
        for (int up = 0; up < 3; up++) {
            Block f = feet.getRelative(0, up, 0);
            if (f.isPassable() && f.getRelative(0, 1, 0).isPassable()) {
                Location out = l.clone();
                out.setY(f.getY() + (up == 0 ? Math.max(0, l.getY() - f.getY()) : 0));
                return out;
            }
        }
        return toWorld(new double[]{0, b == null ? 0.5 + model.manifest.height : b[4], 0}, true);
    }

    /** Moves each occupied seat's mount to where its seat is now. */
    private void carry(long now) {
        if (seated.isEmpty()) return;
        for (Seated s : new ArrayList<>(seated.values())) {
            if (!s.rider.isValid() || !s.mount.isValid() || !s.mount.equals(s.rider.getVehicle())) {
                release(s.seat, true);
                continue;
            }
            Location to = mountAt(s.seat, now + 1);
            if (to.distanceSquared(s.mount.getLocation()) > 1e-6) s.mount.teleport(to, TeleportFlag.EntityState.RETAIN_PASSENGERS);
        }
    }

    /** Hands the driving seat's rider the controls, or takes them back. */
    private void drive() {
        Spec.Drive d = model.drive();
        if (d == null) return;
        Seated s = seated.get(model.driverSeat());
        Player driver = s != null && s.rider instanceof Player p ? p : null;
        if (driver != null && mover == null) {
            clearSolid();
            engine.mover(this, engine.brainFor(this, d));
            laidOut = null;
        }
        if (mover != null) mover.drive(driver, d);
    }

    /** Every part but the body: riders off, mounts, head and boxes gone. Barriers stay. */
    void removeParts() {
        for (String seat : new ArrayList<>(seated.keySet())) {
            Seated s = seated.remove(seat);
            engine.disown(s.mount);
            forcing = true;
            try {
                s.mount.removePassenger(s.rider);
            } finally {
                forcing = false;
            }
            s.mount.remove();
            if (s.rider instanceof Player p) for (Interaction b : boxes) p.showEntity(engine.plugin(), b);
        }
        for (Interaction b : boxes) {
            engine.disown(b);
            b.remove();
        }
        boxes.clear();
        if (head != null) {
            engine.disown(head);
            head.remove();
            head = null;
            animator.head = null;
        }
        laidOut = null;
    }

    // ---- the rest of the API ----------------------------------------------------------

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
        if (head != null) head.teleport(to);
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
        return toWorld(new double[]{m[0], m[1], m[2]}, true);
    }

    @Override
    public double scale() {
        return scale;
    }

    @Override
    public void scale(double s) {
        scale = Math.max(0.01, s);
        applyBody();
        if (head != null && !bent) head.setTransformation(scaled());
        if (display.isPersistent()) display.getPersistentDataContainer().set(SCALE, PersistentDataType.DOUBLE, scale);
        layOut();
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
        if (removed) return;
        engine.callEvent(new ModelRemoveEvent(this));
        removed = true;
        removeParts();
        clearSolid();
        if (mover != null) mover.removeBrain();
        display.remove();
    }

    boolean removed() {
        return removed;
    }
}
