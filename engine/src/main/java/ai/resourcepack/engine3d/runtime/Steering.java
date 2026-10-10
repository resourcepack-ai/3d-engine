package ai.resourcepack.engine3d.runtime;

import ai.resourcepack.engine3d.api.ModelInstance;
import ai.resourcepack.engine3d.api.Mover;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.util.Vector;

import java.util.List;
import java.util.Map;

/**
 * A {@link Mover}: an invisible mob the engine steers, with a model drawn on it.
 *
 * The game's pathfinder moves block centre to block centre in eight
 * directions, so anything not on one of them is run as a zigzag of diagonals.
 * The brain is steered instead: it runs at the furthest point it can reach in
 * a straight line (the target, or a corner of the pathfinder's path), turning
 * onto it at an animal's rate and easing its speed up and down. The pathfinder
 * only drives when nothing straight gets there.
 */
final class Steering implements Mover {

    private final Instance body;
    private final Mob brain;

    // Tuning, from the model's locomotion block.
    private final String idleState, walkState, runState, turnState;
    private final double walkAbove, runAbove;
    private final float turnStanding, turnMoving;
    private static final float TURNING_ABOVE = 6f;

    private Location last;
    private double speed;
    private float yaw;
    private int turning;

    private Entity follow;
    private double followStart = 6, followStop = 2.5, followRun = 9;
    private boolean following;
    private double leash = 24;
    private Location goTo;
    private double goSpeed, goArrive;
    private Location faceAt;
    private String hold;
    private boolean frozen;

    // This tick's plan.
    private Location goal;
    private double goalSpeed, remaining;
    private Location aim;
    private boolean aimIsTarget, steered;
    private long replanAt;
    private double steerSpeed;
    private boolean removed;

    // Driven: whoever sits in the driving seat, and how it handles.
    private org.bukkit.entity.Player driver;
    private ai.resourcepack.engine3d.bake.Spec.Drive drive;
    private double driveSpeed;

    Steering(Instance body, Mob brain) {
        this.body = body;
        this.brain = brain;
        Map<String, Object> loco = body.model.locomotion();
        idleState = str(loco, "idle", body.model.defaultState());
        walkState = str(loco, "walk", body.model.states().contains("walk") ? "walk" : idleState);
        runState = str(loco, "run", body.model.states().contains("run") ? "run" : walkState);
        turnState = str(loco, "turn", body.model.states().contains("turn") ? "turn" : idleState);
        walkAbove = num(loco, "walk-above", 0.035);
        runAbove = num(loco, "run-above", 0.13);
        turnStanding = (float) num(loco, "turn-standing", 9);
        turnMoving = (float) num(loco, "turn-moving", 14);
        last = brain.getLocation();
        yaw = brain.getLocation().getYaw();
        body.mover = this;
    }

    private static String str(Map<String, Object> m, String k, String fallback) {
        Object v = m.get(k);
        return v != null ? String.valueOf(v) : fallback;
    }

    private static double num(Map<String, Object> m, String k, double fallback) {
        Object v = m.get(k);
        return v instanceof Number n ? n.doubleValue() : fallback;
    }

    /** Clears the brain's own AI and puts the steering goal in. */
    static void prepare(Mob brain, Steering steering, org.bukkit.plugin.Plugin plugin) {
        brain.setInvisible(true);
        brain.setSilent(true);
        brain.setInvulnerable(true);
        brain.setPersistent(false);
        brain.setRemoveWhenFarAway(false);
        brain.setCollidable(false);
        brain.addScoreboardTag("3dengine");
        var range = brain.getAttribute(Attribute.FOLLOW_RANGE);
        if (range != null) range.setBaseValue(48);
        Platform.CURRENT.prepareBrain(brain, plugin, steering.new Legs());
    }

    // ---- the API ----------------------------------------------------------------

    @Override
    public ModelInstance body() {
        return body;
    }

    @Override
    public Mob brain() {
        return brain;
    }

    @Override
    public void follow(Entity target, double start, double stop, double run) {
        follow = target;
        followStart = start;
        followStop = stop;
        followRun = run;
    }

    @Override
    public void leash(double blocks) {
        leash = blocks;
    }

    @Override
    public void unfollow() {
        follow = null;
        following = false;
    }

    @Override
    public void goTo(Location to, double speed, double arrive) {
        goTo = to.clone();
        goSpeed = speed;
        goArrive = arrive;
        aim = null;
    }

    @Override
    public boolean going() {
        return goTo != null;
    }

    @Override
    public void stop() {
        goTo = null;
        aim = null;
    }

    @Override
    public void face(Location at) {
        faceAt = at == null ? null : at.clone();
    }

    @Override
    public double speed() {
        return speed;
    }

    @Override
    public void hold(String state) {
        hold = state;
    }

    @Override
    public String locomotion() {
        if (speed > runAbove) return runState;
        if (speed > walkAbove) return walkState;
        if (turning > 0) return turnState;
        return idleState;
    }

    @Override
    public void freeze(boolean frozen) {
        this.frozen = frozen;
        if (frozen) {
            Platform.CURRENT.stopPathfinding(brain);
            steerSpeed = 0;
        }
    }

    @Override
    public void teleport(Location at) {
        brain.teleport(at);
        last = at.clone();
        aim = null;
        body.teleport(withYaw(at));
    }

    @Override
    public boolean driven() {
        return driver != null && driver.isValid();
    }

    @Override
    public boolean valid() {
        return !removed && brain.isValid() && body.valid();
    }

    @Override
    public void remove() {
        body.remove();
    }

    /** Gives the controls to {@code driver} (null takes them back). */
    void drive(org.bukkit.entity.Player driver, ai.resourcepack.engine3d.bake.Spec.Drive drive) {
        if (driver == null && this.driver != null) {
            driveSpeed = 0;
            brain.setVelocity(new Vector(0, brain.getVelocity().getY(), 0));
        }
        this.driver = driver;
        this.drive = drive;
    }

    void removeBrain() {
        removed = true;
        brain.remove();
    }

    // ---- each tick --------------------------------------------------------------

    void tick() {
        Location now = brain.getLocation();
        long time = now.getWorld().getGameTime();
        double moved = now.getWorld() == last.getWorld() ? Math.hypot(now.getX() - last.getX(), now.getZ() - last.getZ()) : 0;
        last = now.clone();
        speed = speed * 0.6 + Math.min(moved, 1) * 0.4;

        if (driver != null && driver.isValid()) {
            steerByRider();
            body.state(hold != null ? hold : locomotion());
            body.teleport(withYaw(brain.getLocation()));
            return;
        }

        // Left far behind: catch up.
        if (follow != null && follow.isValid() && leash > 0 && goTo == null && !frozen) {
            Location t = follow.getLocation();
            if (t.getWorld() != now.getWorld() || t.distance(now) > leash) {
                teleport(t);
                now = brain.getLocation();
            }
        }

        goal = frozen ? null : target(now);
        steered = goal != null && plan(now, goal, time);
        if (goal != null) faceAt = null;
        float facing;
        if (steered) facing = (float) Math.toDegrees(Math.atan2(-(aim.getX() - now.getX()), aim.getZ() - now.getZ()));
        else if (faceAt != null && faceAt.getWorld() == now.getWorld())
            facing = (float) Math.toDegrees(Math.atan2(-(faceAt.getX() - now.getX()), faceAt.getZ() - now.getZ()));
        else facing = yaw;
        // A mob swings round in a tick or two; an animal turning on the spot
        // shuffles round over half a second. So the drawn heading chases the
        // wanted one at a capped rate, and while it catches up the legs step.
        float off = wrap(facing - yaw);
        float cap = speed > walkAbove ? turnMoving : turnStanding;
        yaw = wrap(yaw + Math.max(-cap, Math.min(cap, off)));
        if (Math.abs(off) > TURNING_ABOVE) turning = 8;
        else if (turning > 0) turning--;
        steer(steered ? goal : null, off);

        body.state(hold != null ? hold : locomotion());
        body.teleport(withYaw(now));
    }

    /**
     * As a horse is ridden: it turns towards where the rider looks while they
     * hold forward (or back), sprints when they sprint and jumps when they jump.
     */
    private void steerByRider() {
        org.bukkit.Input in = driver.getCurrentInput();
        double want = in.isForward() ? (in.isSprint() || driver.isSprinting() ? drive.sprint : drive.speed) : in.isBackward() ? -drive.back : 0;
        float off = wrap(driver.getLocation().getYaw() - yaw);
        float cap = (float) drive.turn;
        if (want != 0 || Math.abs(driveSpeed) > 0.02) yaw = wrap(yaw + Math.max(-cap, Math.min(cap, off)));
        if (want != 0 && Math.abs(off) > TURNING_ABOVE) turning = 8;
        else if (turning > 0) turning--;
        double v = driveSpeed;
        v = want > v ? Math.min(want, v + 0.025) : Math.max(want, v - 0.04);
        driveSpeed = v;
        double r = Math.toRadians(yaw);
        double fx = -Math.sin(r), fz = Math.cos(r);
        double vy = brain.getVelocity().getY();
        if (brain.isOnGround()) {
            double dir = v >= 0 ? 0.6 + Math.abs(v) * 2 : -0.6 - Math.abs(v) * 2;
            Block ahead = brain.getLocation().add(fx * dir, 0, fz * dir).getBlock();
            if (Math.abs(v) > 0.02 && !clear(ahead) && height(ahead) <= 1.0 && clear(ahead.getRelative(0, 1, 0)) && clear(ahead.getRelative(0, 2, 0))) vy = 0.42;
            if (in.isJump() && drive.jump > 0) vy = drive.jump;
        }
        brain.setVelocity(new Vector(fx * v, vy, fz * v));
        brain.setRotation(yaw, 0);
    }

    private Location withYaw(Location at) {
        Location to = at.clone();
        to.setYaw(yaw);
        to.setPitch(0);
        return to;
    }

    private static float wrap(float degrees) {
        degrees %= 360f;
        if (degrees >= 180f) degrees -= 360f;
        if (degrees < -180f) degrees += 360f;
        return degrees;
    }

    /** Where it wants to be this tick, or null to stay put. */
    private Location target(Location at) {
        Location t = null;
        double sp = 0, arrive = 0;
        if (goTo != null) {
            t = goTo;
            sp = goSpeed;
            arrive = goArrive;
        } else if (follow != null && follow.isValid()) {
            Location f = follow.getLocation();
            double d = f.getWorld() == at.getWorld() ? f.distance(at) : Double.MAX_VALUE;
            if (d > followStart) following = true;
            else if (d < followStop) following = false;
            if (following) {
                t = f;
                sp = d > followRun ? 0.24 : 0.09;
                arrive = followStop;
            }
        }
        if (t == null || t.getWorld() != at.getWorld()) return null;
        double d = Math.hypot(t.getX() - at.getX(), t.getZ() - at.getZ());
        if (d <= arrive) {
            if (t == goTo) goTo = null;
            return null;
        }
        goalSpeed = sp;
        remaining = d - arrive;
        return t;
    }

    /** Picks the point to run straight at; false when there is none and the pathfinder has to do it. */
    private boolean plan(Location at, Location target, long time) {
        boolean reached = aim != null && Math.hypot(aim.getX() - at.getX(), aim.getZ() - at.getZ()) < 0.6;
        if (aim != null && !reached && time < replanAt) {
            if (aimIsTarget) aim = target.clone();
            return true;
        }
        replanAt = time + 5;
        aim = null;
        aimIsTarget = false;
        if (walkable(at, target)) {
            aim = target.clone();
            aimIsTarget = true;
            return true;
        }
        List<Location> points = Platform.CURRENT.path(brain, target);
        if (points == null) return false;
        for (int i = points.size() - 1; i >= 0; i--) {
            Location p = points.get(i).clone().add(0.5, 0, 0.5);
            double d = Math.hypot(p.getX() - at.getX(), p.getZ() - at.getZ());
            if (d > 24 || d < 0.6) continue;
            if (walkable(at, p)) {
                aim = p;
                return true;
            }
        }
        return false;
    }

    /** Runs the brain along the drawn heading at an eased speed; slows right down to turn. */
    private void steer(Location target, float off) {
        double want = 0;
        if (target != null) {
            want = Math.min(goalSpeed, 0.04 + remaining * 0.3);
            want *= Math.max(0, Math.cos(Math.toRadians(off)));
        }
        double v = steerSpeed;
        v = want > v ? Math.min(want, v + 0.02) : Math.max(want, v - 0.04);
        steerSpeed = v;
        if (v < 0.005 || frozen) return;
        double r = Math.toRadians(yaw);
        double fx = -Math.sin(r), fz = Math.cos(r);
        double vy = brain.getVelocity().getY();
        if (brain.isOnGround()) {
            // A block in the way that it can hop up: hop.
            Block ahead = brain.getLocation().add(fx * 0.6, 0, fz * 0.6).getBlock();
            if (!clear(ahead) && height(ahead) <= 1.0 && clear(ahead.getRelative(0, 1, 0)) && clear(ahead.getRelative(0, 2, 0))) vy = 0.42;
        }
        brain.setVelocity(new Vector(fx * v, vy, fz * v));
        brain.setRotation(yaw, 0);
    }

    /** Something an animal walks through or straight over (air, grass, a carpet, a slab). */
    static boolean clear(Block b) {
        return !b.isLiquid() && (b.isPassable() || height(b) <= 0.6);
    }

    static double height(Block b) {
        double top = 0;
        for (var box : b.getCollisionShape().getBoundingBoxes()) top = Math.max(top, box.getMaxY());
        return top;
    }

    /**
     * Whether it can run straight from {@code from} to {@code to}: following
     * the ground, up single steps, down drops of up to three, no water, and
     * with room for its shoulders either side.
     */
    static boolean walkable(Location from, Location to) {
        var world = from.getWorld();
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        double len = Math.hypot(dx, dz);
        if (len < 0.01) return true;
        double ux = dx / len, uz = dz / len;
        int y = from.getBlockY();
        int steps = (int) Math.ceil(len / 0.4);
        for (int i = 1; i <= steps; i++) {
            double t = Math.min(len, i * 0.4);
            double x = from.getX() + ux * t, z = from.getZ() + uz * t;
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
            if (!world.isChunkLoaded(bx >> 4, bz >> 4)) return false;
            Block b = world.getBlockAt(bx, y, bz);
            if (!clear(b)) {
                if (height(b) > 1.0 || !clear(b.getRelative(0, 1, 0)) || !clear(b.getRelative(0, 2, 0))) return false;
                y++;
            } else {
                int drop = 0;
                while (drop < 4 && clear(world.getBlockAt(bx, y - 1, bz))) {
                    y--;
                    drop++;
                }
                if (drop == 4) return false;
            }
            if (world.getBlockAt(bx, y - 1, bz).isLiquid()) return false;
            for (double s : new double[]{-0.3, 0.3}) {
                Block side = world.getBlockAt((int) Math.floor(x - uz * s), y, (int) Math.floor(z + ux * s));
                if (!clear(side) && !clear(side.getRelative(0, 1, 0))) return false;
            }
        }
        return true;
    }

    /** What the platform's legs read each tick: whether the steering has the brain, and where to walk when not. */
    private final class Legs implements Platform.Legs {
        @Override
        public boolean steering() {
            return frozen || steered || goal == null;
        }

        @Override
        public Location goal() {
            return goal;
        }

        @Override
        public double speed() {
            return goalSpeed;
        }
    }
}
