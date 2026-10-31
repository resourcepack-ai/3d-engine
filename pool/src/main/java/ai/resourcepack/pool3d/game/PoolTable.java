package ai.resourcepack.pool3d.game;

import ai.resourcepack.engine3d.api.Engine3D;
import ai.resourcepack.engine3d.api.ModelInstance;
import ai.resourcepack.pool3d.Assets;
import ai.resourcepack.pool3d.physics.Ball;
import ai.resourcepack.pool3d.physics.Rack;
import ai.resourcepack.pool3d.physics.Simulation;
import ai.resourcepack.pool3d.physics.TableGeometry;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Display;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;

import static ai.resourcepack.pool3d.physics.TableGeometry.HALF_LENGTH;
import static ai.resourcepack.pool3d.physics.TableGeometry.HALF_WIDTH;
import static ai.resourcepack.pool3d.physics.TableGeometry.R;

/**
 * One table in the world: the model, the balls drawn where the simulation has
 * them, the cue and the aiming guide, and the game played on it.
 *
 * <p>The table's own frame is the physics' (x along, y across, z up, metres =
 * blocks). Its middle stands on the block grid so that its 3 x 2 footprint of
 * barriers fits it: long along world X, or along world Z.
 */
public final class PoolTable {

    /** The cloth's height above the floor. */
    public static final double BED = 0.775;
    /** Half the table's outside length and width (make_models.py's A_OUT, B_OUT). */
    public static final double OUT_X = 1.45, OUT_Y = 0.815;
    /** Where the tip rests behind the cue ball while aiming (Shooterspool's {@code distTipCueball}), and the most it can be drawn back. */
    static final double REST = 0.003, BRIDGE = 0.34;
    /**
     * The shot camera, after Shooterspool's own defaults (its player
     * profile: {@code initialHeadToBallDistance} 1.2, {@code fov} 30): a
     * long lens close behind the cue ball, so the cue runs long up the middle
     * of the screen and the table looks flat. The lens is half the player's
     * own view (35 degrees at Minecraft's 70), the narrowest the still stroke
     * camera ({@link StandIn}) can match, and the eye is brought in to
     * {@link #DISTANCE} so the cue ball is the size it is at 1.2 m through
     * 30 degrees. The wheel moves the eye in and out.
     */
    static final double ZOOM = 0.5;
    static final double DISTANCE = 1.2 * Math.tan(Math.toRadians(15)) / Math.tan(Math.toRadians(17.5));
    static final double DISTANCE_MIN = 0.4, DISTANCE_MAX = 2.0;
    /** The cue ball sits this share of the screen's height below its middle, whatever the tilt. */
    static final double BALL_BELOW = 0.116;
    /** The tilt a new shot starts at: the eye 9 degrees up from the cue ball, as in Shooterspool. */
    static final float START_PITCH = 5.5f;
    /** The overview: looking straight down from this high over the middle of the cloth, at the player's own view. */
    static final double OVERVIEW = 1.45;
    /** Ball in hand moves this far a tick with W, A, S or D held. */
    static final double PLACE_STEP = 0.012;

    enum State {IDLE, PLACE, AIM, STRIKE, ROLLING, SETTLE, OVER}

    /** What the mouse is doing while S or W is held: nothing special, the stroke, or moving the tip. */
    enum Mode {AIM, STROKE, SPIN}

    final Tables tables;
    final Engine3D engine;
    public final String id;
    public final World world;
    public final double cx, cy, cz;
    public final boolean alongZ;

    final Simulation sim = new Simulation(TableGeometry.NINE_FOOT);
    final Random random = new Random();
    EightBall game;
    final List<UUID> players = new ArrayList<>();
    State state = State.IDLE;
    int stateTicks;

    private ModelInstance body;
    private final Map<Integer, BallView> views = new HashMap<>();
    private ModelInstance cue;
    /** The guide, flat on the cloth: the ring, the object ball's shadow and the cue ball's wedge. */
    private final ItemDisplay[] guide = new ItemDisplay[3];
    private final int[] guideColours = {-1, -1, -1};
    private static final String[] GUIDE_MODELS = {"pool:guide_ring", "pool:guide_shadow", "pool:guide_wedge"};
    private BossBar bar;

    // The shot being lined up.
    double aim;
    double side, height;
    double power;
    boolean kitchenOnly;
    private double strikeBack, shotSpeed;
    private ShotRecord record;

    // The shooter's camera and keys.
    private final Map<UUID, ShotCamera> cameras = new HashMap<>();
    /** Shooters who got up (sneak) to walk round the table; a right-click sits them down again. */
    private final java.util.Set<UUID> standing = new java.util.HashSet<>();
    Mode mode = Mode.AIM;
    final double zoom = ZOOM;
    double distance = DISTANCE;
    /** Where the wheel last sent the eye: {@link #distance} glides there. */
    private double distanceTo = DISTANCE;
    /** The still camera the shooter looks through while S or W is held, where the server can manage it. */
    private StandIn standIn;
    private float lastYaw, lastPitch;
    boolean overview;
    private boolean jumpHeld;
    /** The view when S or W went down, given back when it comes up. */
    private float heldYaw, heldPitch;
    /** How far the tip is behind the ball during a stroke, and how far it moved in the last tick. */
    private double gap = REST, gapSpeed;
    private int nudgeTicks;

    private static final class BallView {
        ModelInstance ball, gloss;
        final Quaternionf turn = new Quaternionf();
        double lastX = Double.NaN, lastY, lastH;
        int dropTick = -1;
        double dropX, dropY, holeX, holeY;
    }

    PoolTable(Tables tables, Engine3D engine, String id, World world, double cx, double cy, double cz, boolean alongZ) {
        this.tables = tables;
        this.engine = engine;
        this.id = id;
        this.world = world;
        this.cx = cx;
        this.cy = cy;
        this.cz = cz;
        this.alongZ = alongZ;
        sim.balls.addAll(Rack.eightBall(random));
    }

    // ---- frames ---------------------------------------------------------------------------

    /**
     * A point in the table's frame, {@code h} above the floor, in the world,
     * where it is drawn: turned by {@link #aimOffset} about the cue ball.
     */
    Location at(double x, double y, double h) {
        Location l = raw(x, y, h);
        if (aimOffset == 0) return l;
        double dx = l.getX() - offsetX, dz = l.getZ() - offsetZ;
        l.setX(offsetX + dx * offsetCos - dz * offsetSin);
        l.setZ(offsetZ + dx * offsetSin + dz * offsetCos);
        return l;
    }

    /** A point in the table's frame in the world as it really is: for the player, the seat and the camera. */
    Location raw(double x, double y, double h) {
        return alongZ ? new Location(world, cx + y, cy + h, cz + x) : new Location(world, cx + x, cy + h, cz - y);
    }

    /** A direction in the table's frame (z up) as a world vector, as it is drawn. */
    Vector3f dir(double x, double y, double z) {
        Vector3f v = rawDir(x, y, z);
        if (aimOffset == 0) return v;
        float wx = v.x, wz = v.z;
        v.x = (float) (wx * offsetCos - wz * offsetSin);
        v.z = (float) (wx * offsetSin + wz * offsetCos);
        return v;
    }

    private Vector3f rawDir(double x, double y, double z) {
        return alongZ ? new Vector3f((float) y, (float) z, (float) x) : new Vector3f((float) x, (float) z, (float) -y);
    }

    /**
     * The still camera (see {@link StandIn}) can face only 256 ways round.
     * So while the shooter aims, their own view is nudged onto one of those,
     * a little at a time inside their own mouse movement, and the aim is
     * kept where they put it by this: the shot goes {@code aimOffset}
     * degrees of yaw round from the view, and the table is drawn turned the
     * other way by as much about the cue ball, which is just what the camera
     * going round the cue ball by as much would show. When S goes down the
     * view is already one the still camera can match, so nothing moves.
     */
    private double aimOffset, offsetX, offsetZ, offsetCos = 1, offsetSin;
    /** The cue ball the offset turns about, in the table's frame. */
    private double offsetBallX = Double.NaN, offsetBallY;
    private float lastAimYaw = Float.NaN;

    private void aimOffset(double degrees) {
        aimOffset = degrees;
        double t = Math.toRadians(-degrees);
        offsetCos = Math.cos(t);
        offsetSin = Math.sin(t);
    }

    /**
     * Nudges the view towards the nearest turn the still camera can face,
     * by no more than a share of how far the mouse moved it this tick, and
     * takes the nudge into the offset so the aim does not move.
     */
    private void nudgeView(Player p, float yaw) {
        double moved = Float.isNaN(lastAimYaw) ? 0 : Math.abs(Math.IEEEremainder(yaw - lastAimYaw, 360));
        lastAimYaw = yaw;
        double aimYaw = yaw + aimOffset;
        double target = Math.round(aimYaw / StandIn.STEP) * StandIn.STEP;
        double want = Math.IEEEremainder(target - yaw, 360);
        // Small against the mouse's own move, so it is lost in it.
        double cap = Math.min(0.1, 0.15 * moved);
        double step = Math.max(-cap, Math.min(cap, want));
        if (Math.abs(step) < 1e-4) return;
        StandIn.turn(p, (float) step);
        lastAimYaw += (float) step;
        aimOffset(aimOffset - step);
    }

    /** Where the table's model stands. */
    private Location bodyAt() {
        Location at = at(0, 0, 0);
        at.setYaw((float) ((alongZ ? 90 : 0) - aimOffset));
        return at;
    }

    /** The table and everything drawn on it, which the still camera turns with it on the shooter's screen. */
    private List<org.bukkit.entity.Entity> drawn() {
        List<org.bukkit.entity.Entity> out = new ArrayList<>();
        if (body != null && body.valid()) out.add(body.display());
        for (BallView v : views.values()) {
            if (v.ball != null && v.ball.valid()) out.add(v.ball.display());
            if (v.gloss != null && v.gloss.valid()) out.add(v.gloss.display());
        }
        if (cue != null && cue.valid()) out.add(cue.display());
        for (ItemDisplay d : guide) if (d != null && d.isValid()) out.add(d);
        return out;
    }

    /** World (X, Z) in the table's frame. */
    double[] toTable(double wx, double wz) {
        double dx = wx - cx, dz = wz - cz;
        return alongZ ? new double[]{dz, dx} : new double[]{dx, -dz};
    }

    /** A world horizontal direction as an angle in the table's frame. */
    double angleOf(double wx, double wz) {
        return alongZ ? Math.atan2(wx, wz) : Math.atan2(-wz, wx);
    }

    public Location centre() {
        return new Location(world, cx, cy, cz);
    }

    /** The blocks the table stands in: 3 along, 2 across. */
    List<Block> footprint() {
        List<Block> out = new ArrayList<>();
        int by = (int) Math.floor(cy);
        for (int i = -1; i <= 1; i++)
            for (int j = -1; j <= 0; j++) {
                int bx, bz;
                if (alongZ) {
                    bx = (int) Math.floor(cx) + j;
                    bz = (int) Math.floor(cz) + i;
                } else {
                    bx = (int) Math.floor(cx) + i;
                    bz = (int) Math.floor(cz) + j;
                }
                out.add(world.getBlockAt(bx, by, bz));
            }
        return out;
    }

    // ---- drawing ----------------------------------------------------------------------------

    /** (Re)makes whatever is missing: called when the engine is ready and after a reload. */
    void spawn() {
        if (body == null || !body.valid()) {
            body = engine.spawn(Assets.TABLE, bodyAt());
            body.display().setTeleportDuration(0);
        }
        for (Ball b : sim.balls) if (b.onTable()) view(b);
        if (bar == null) {
            bar = Bukkit.createBossBar("", BarColor.WHITE, BarStyle.SOLID);
            bar.setProgress(1);
        }
    }

    void despawn() {
        if (body != null) body.remove();
        body = null;
        for (BallView v : views.values()) removeView(v);
        views.clear();
        hideCue();
        hideGuide();
        for (UUID u : new ArrayList<>(cameras.keySet())) closeCamera(u);
        if (bar != null) bar.removeAll();
    }

    private BallView view(Ball b) {
        BallView v = views.get(b.number);
        if (v != null && v.ball.valid() && v.gloss.valid()) return v;
        if (v != null) removeView(v);
        v = new BallView();
        Location at = at(b.x, b.y, BED + R);
        v.ball = engine.spawn(Assets.ball(b.number), at);
        v.gloss = engine.spawn(Assets.GLOSS, at);
        v.ball.display().setTeleportDuration(1);
        v.gloss.display().setTeleportDuration(1);
        // Each ball lies as it happens to have stopped: numbers every which way.
        v.turn.rotateAxis((float) (random.nextDouble() * Math.PI * 2), randomAxis());
        applyTurn(v, 0);
        v.lastX = b.x;
        v.lastY = b.y;
        v.lastH = BED + R;
        views.put(b.number, v);
        return v;
    }

    private Vector3f randomAxis() {
        Vector3f a = new Vector3f((float) random.nextGaussian(), (float) random.nextGaussian(), (float) random.nextGaussian());
        return a.lengthSquared() < 1e-6 ? new Vector3f(0, 1, 0) : a.normalize();
    }

    private static void removeView(BallView v) {
        if (v.ball != null) v.ball.remove();
        if (v.gloss != null) v.gloss.remove();
    }

    private void applyTurn(BallView v, int ticks) {
        Display d = v.ball.display();
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(ticks);
        d.setTransformation(new Transformation(new Vector3f(), new Quaternionf(v.turn), new Vector3f(1, 1, 1), new Quaternionf()));
    }

    /** Moves and turns every ball to where the simulation has it. */
    /** The aim offset the table and balls were last drawn with. */
    private double drawnOffset;

    private void drawBalls() {
        if (aimOffset != drawnOffset) {
            drawnOffset = aimOffset;
            if (body != null && body.valid()) body.teleport(bodyAt());
            for (BallView v : views.values()) v.lastX = Double.NaN;
        }
        for (Ball b : sim.balls) {
            BallView v = views.get(b.number);
            if (b.onTable()) {
                if (v == null) v = view(b);
                int glide = state == State.AIM ? 0 : 1;
                if (v.ball.display().getTeleportDuration() != glide) {
                    v.ball.display().setTeleportDuration(glide);
                    v.gloss.display().setTeleportDuration(glide);
                }
                if (b.x != v.lastX || b.y != v.lastY || v.lastH != BED + R) {
                    Location at = at(b.x, b.y, BED + R);
                    v.ball.teleport(at);
                    v.gloss.teleport(at);
                    v.lastX = b.x;
                    v.lastY = b.y;
                    v.lastH = BED + R;
                }
                Vector3f w = dir(b.turnX, b.turnY, b.turnZ);
                float angle = w.length();
                if (angle > 1e-4f) {
                    // At 20 frames a second a fast ball turns more than half a
                    // revolution between frames and would seem to spin
                    // backwards; it is drawn turning forwards at most 150
                    // degrees a frame instead, which reads as fast.
                    float shown = Math.min(angle, (float) Math.toRadians(150));
                    v.turn.premul(new Quaternionf().rotateAxis(shown, w.div(angle)));
                    v.turn.normalize();
                    applyTurn(v, 1);
                }
                b.turnX = b.turnY = b.turnZ = 0;
            } else if (v != null) {
                // Down a pocket: it rolls over the edge towards the hole's middle and drops.
                if (v.dropTick < 0) {
                    TableGeometry.Pocket p = sim.table.pockets.get(b.pocket);
                    v.dropTick = 0;
                    v.dropX = v.lastX;
                    v.dropY = v.lastY;
                    v.holeX = p.holeX() + (p.holeX() - p.mx()) * 0.1;
                    v.holeY = p.holeY() + (p.holeY() - p.my()) * 0.1;
                    v.gloss.remove();
                }
                int t = ++v.dropTick;
                if (t > 7) {
                    removeView(v);
                    views.remove(b.number);
                    continue;
                }
                double f = Math.min(1, t / 4.0);
                double x = v.dropX + (v.holeX - v.dropX) * (1 - (1 - f) * (1 - f));
                double y = v.dropY + (v.holeY - v.dropY) * (1 - (1 - f) * (1 - f));
                double h = BED + R - 0.11 * Math.min(1, (t / 6.0) * (t / 6.0));
                v.ball.teleport(at(x, y, h));
                v.lastX = x;
                v.lastY = y;
                v.lastH = h;
            }
        }
    }

    // ---- the cue and the guide --------------------------------------------------------------

    /** The cue lying on the line of the shot with its tip {@code gap} back from the ball. */
    private void drawCue(double gap) {
        if (facingCamera()) {
            drawCueFacing(gap);
            return;
        }
        Ball c = sim.ball(0);
        double dx = Math.cos(aim), dy = Math.sin(aim);
        // The tip meets the ball at (side, height) radii off centre, seen from behind.
        double sx = dy, sy = -dx;
        double back = Math.sqrt(Math.max(0, 1 - side * side - height * height));
        double px = c.x + R * (-dx * back + sx * side), py = c.y + R * (-dy * back + sy * side);
        double pz = BED + R + R * height;
        double elev = elevation(c, dx, dy);
        double ax = -dx * Math.cos(elev), ay = -dy * Math.cos(elev), az = Math.sin(elev);
        // Stood on the cue ball, as the facing cue is, with the tip set out
        // from there: changing from one to the other is then only its shape.
        Location at = at(c.x, c.y, BED + R);
        if (cue == null || !cue.valid()) {
            cue = engine.spawn(Assets.CUE, at);
            cue.display().setTeleportDuration(0);
        } else if (cue.display().getLocation().distanceSquared(at) > 1e-8) cue.teleport(at);
        Display d = cue.display();
        boolean changed = billboard(d, Display.Billboard.FIXED);
        Vector3f axis = dir(ax, ay, az).normalize();
        Vector3f tip = dir(px - c.x + ax * gap, py - c.y + ay * gap, pz - (BED + R) + az * gap);
        transform(d, new Transformation(tip, new Quaternionf().rotationTo(new Vector3f(0, 1, 0), axis),
                new Vector3f(1, 1, 1), new Quaternionf()), changed ? 0 : mode == Mode.STROKE ? 2 : 1);
    }

    /** Whether the shooter is looking down the cue through the orbit camera. */
    private boolean facingCamera() {
        Player p = shooter();
        return p != null && cameras.containsKey(p.getUniqueId()) && !overview && state != State.PLACE;
    }

    /** Turns a display to face the camera or not, saying whether it changed: a change is drawn at once, not eased. */
    private static boolean billboard(Display d, Display.Billboard b) {
        if (d.getBillboard() == b) return false;
        d.setBillboard(b);
        return true;
    }

    /**
     * The cue as the shooter sees it in the orbit camera. Turned from the
     * server it would trail the mouse by a round trip, as the camera did; so
     * it stands on the cue ball as a display that turns about the vertical to
     * face the camera, which the client does every frame. The camera is
     * always behind the cue ball along the aim, so facing it is lying along
     * the aim, and the cue keeps up with the mouse exactly. Its tilt (the
     * cue's elevation), where the tip meets the ball and the stroke are set
     * in that turned frame, in which +z points at the camera and +x is the
     * shooter's right.
     */
    private void drawCueFacing(double gap) {
        Ball c = sim.ball(0);
        Location at = at(c.x, c.y, BED + R);
        if (cue == null || !cue.valid()) {
            cue = engine.spawn(Assets.CUE, at);
            cue.display().setTeleportDuration(0);
        } else if (cue.display().getLocation().distanceSquared(at) > 1e-8) cue.teleport(at);
        Display d = cue.display();
        boolean changed = billboard(d, Display.Billboard.VERTICAL);
        double elev = elevation(c, Math.cos(aim), Math.sin(aim));
        double back = Math.sqrt(Math.max(0, 1 - side * side - height * height));
        Vector3f along = new Vector3f(0, (float) Math.sin(elev), (float) Math.cos(elev));
        Vector3f tip = new Vector3f((float) (side * R), (float) (height * R), (float) (back * R))
                .add(new Vector3f(along).mul((float) gap));
        // Stroking, the cue follows the mouse a tick at a time, and the mouse
        // reaches the server unevenly (none in one tick, two in the next):
        // easing each step over two ticks smooths that without much delay.
        transform(d, new Transformation(tip, new Quaternionf().rotationTo(new Vector3f(0, 1, 0), along),
                new Vector3f(1, 1, 1), new Quaternionf()), changed ? 0 : mode == Mode.STROKE ? 2 : 1);
    }

    /**
     * How steeply the cue must come down to clear the rail and any ball
     * behind the cue ball: a player jacks up the butt when they have to.
     */
    private double elevation(Ball c, double dx, double dy) {
        double need = Math.toRadians(4.5);
        // Where the line behind the ball leaves the bed.
        double tx = dx == 0 ? 9 : (dx > 0 ? c.x + HALF_LENGTH : HALF_LENGTH - c.x) / Math.abs(dx);
        double ty = dy == 0 ? 9 : (dy > 0 ? c.y + HALF_WIDTH : HALF_WIDTH - c.y) / Math.abs(dy);
        double t = Math.max(0.01, Math.min(tx, ty));
        need = Math.max(need, Math.atan2(0.0425 - R + 0.014, t + R));
        for (Ball b : sim.balls) {
            if (b == c || !b.onTable()) continue;
            double fx = b.x - c.x, fy = b.y - c.y;
            double along = -(fx * dx + fy * dy);
            if (along <= 0 || along > 1.2) continue;
            double off = Math.abs(fx * dy - fy * dx);
            if (off > R + 0.014) continue;
            need = Math.max(need, Math.atan2(R + 0.016, along - R));
        }
        return Math.min(need, Math.toRadians(40));
    }

    /**
     * Gives a display a new transformation, eased over {@code ticks}; and
     * nothing at all when it already has that one. Starting an ease is always
     * sent, changed or not, and the client starts it again from the shape
     * before the last change: sent every tick with nothing changed, the cue
     * flickered back to where it was drawn from, every frame.
     */
    private static void transform(Display d, Transformation t, int ticks) {
        if (same(d.getTransformation(), t)) return;
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(ticks);
        d.setTransformation(t);
    }

    private static boolean same(Transformation a, Transformation b) {
        return a.getTranslation().equals(b.getTranslation(), 1e-5f) && a.getScale().equals(b.getScale(), 1e-5f)
                && a.getLeftRotation().equals(b.getLeftRotation(), 1e-5f) && a.getRightRotation().equals(b.getRightRotation(), 1e-5f);
    }

    private void hideCue() {
        if (cue != null) cue.remove();
        cue = null;
    }

    private void drawGuide() {
        Ball c = sim.ball(0);
        Aim.Guide g = Aim.trace(sim, c, aim);
        // Aimed at no ball, nothing is drawn.
        if (g.hit() < 0) {
            hideGuide();
            return;
        }
        double dx = Math.cos(aim), dy = Math.sin(aim);
        // As Shooterspool draws it: a ring on the cloth where the cue ball
        // will be when it meets the ball, a soft shadow in that ball's colour
        // the way it goes, and a pale wedge from the ring's edge the way the
        // cue ball goes on, longer the more of the blow each takes.
        Ball o = sim.ball(g.hit());
        flat(0, g.gx(), g.gy(), dx, dy, 0, 2.15 * R, 0xFFFFFF);
        double full = Math.max(0, dx * g.ox() + dy * g.oy());
        flat(1, o.x, o.y, g.ox(), g.oy(), Math.min(0.16 + 0.22 * full, toCushion(o.x, o.y, g.ox(), g.oy()) + R), 2 * R, shade(o.number));
        double thin = Math.sqrt(Math.max(0, 1 - full * full));
        if (g.tx() == 0 && g.ty() == 0 || thin < 0.05) flat(2, 0, 0, 1, 0, -1, 0, 0);
        else flat(2, g.gx() + g.tx() * R, g.gy() + g.ty() * R, g.tx(), g.ty(),
                Math.max(0, Math.min(0.10 + 0.20 * thin, toCushion(g.gx(), g.gy(), g.tx(), g.ty()))), 1.5 * R, 0xFFFFFF);
    }

    /** How far a ball's centre at (x, y) can go along (dx, dy) before the ball meets a cushion. */
    private static double toCushion(double x, double y, double dx, double dy) {
        double lx = HALF_LENGTH - R, ly = HALF_WIDTH - R;
        double tx = Math.abs(dx) < 1e-9 ? Double.MAX_VALUE : ((dx > 0 ? lx : -lx) - x) / dx;
        double ty = Math.abs(dy) < 1e-9 ? Double.MAX_VALUE : ((dy > 0 ? ly : -ly) - y) / dy;
        return Math.max(0, Math.min(tx, ty));
    }

    /** The colour of a ball's band: its own, a little deepened, as a shadow on the cloth. */
    private static int shade(int number) {
        int[] colours = {0xFFFFFF, 0xF2C200, 0x1E4FC2, 0xD62718, 0x5B2A86, 0xF07A10, 0x147A3A, 0x7A1F1F, 0x202020};
        int c = colours[number <= 8 ? number : number - 8];
        int r = (c >> 16 & 255) * 3 / 4, gr = (c >> 8 & 255) * 3 / 4, b = (c & 255) * 3 / 4;
        return r << 16 | gr << 8 | b;
    }

    /**
     * Guide piece {@code i}, flat on the cloth: from (x, y) along (dx, dy),
     * {@code len} long and {@code width} wide; with a {@code len} of 0, a
     * square {@code width} across centred there; below 0, nothing.
     */
    private void flat(int i, double x, double y, double dx, double dy, double len, double width, int rgb) {
        // Well clear of the cloth: the shot camera's near plane is very
        // close, so depth is coarse, and nearer the two would fight.
        Location at = at(x, y, BED + 0.004 + i * 0.0015);
        ItemDisplay d = guide[i];
        if (d == null || !d.isValid()) {
            d = world.spawn(at, ItemDisplay.class, e -> {
                e.setPersistent(false);
                e.setBrightness(new Display.Brightness(15, 15));
                e.setTeleportDuration(0);
                e.addScoreboardTag("pool3d");
            });
            guide[i] = d;
            guideColours[i] = -1;
        } else if (d.getLocation().distanceSquared(at) > 1e-10) d.teleport(at);
        if (len >= 0 && guideColours[i] != rgb) {
            guideColours[i] = rgb;
            d.setItemStack(Bukkit.getItemFactory().createItemStack("minecraft:paper[minecraft:item_model=\"" + GUIDE_MODELS[i]
                    + "\",minecraft:custom_model_data={colors:[" + rgb + "]}]"));
        }
        Vector3f w = dir(dx, dy, 0).normalize();
        Quaternionf rot = new Quaternionf().rotateY((float) Math.atan2(w.x, w.z));
        Vector3f size = len < 0 ? new Vector3f() : new Vector3f((float) width, 1, (float) (len == 0 ? width : len));
        transform(d, new Transformation(new Vector3f(w).mul((float) (Math.max(0, len) / 2)), rot, size, new Quaternionf()), 1);
    }

    private void hideGuide() {
        for (int i = 0; i < guide.length; i++) {
            if (guide[i] != null) guide[i].remove();
            guide[i] = null;
        }
    }

    // ---- the game -----------------------------------------------------------------------------

    public boolean idle() {
        return state == State.IDLE;
    }

    public boolean has(Player p) {
        return players.contains(p.getUniqueId());
    }

    Player shooter() {
        if (game == null || players.isEmpty()) return null;
        return Bukkit.getPlayer(players.get(game.turn % players.size()));
    }

    List<Player> online() {
        List<Player> out = new ArrayList<>();
        for (UUID u : players) {
            Player p = Bukkit.getPlayer(u);
            if (p != null) out.add(p);
        }
        return out;
    }

    String name(int i) {
        Player p = Bukkit.getPlayer(players.get(i));
        return p != null ? p.getName() : "?";
    }

    /** A fresh rack, and a new game for whoever is here. */
    void rack(int breaker) {
        for (BallView v : views.values()) removeView(v);
        views.clear();
        sim.balls.clear();
        sim.balls.addAll(Rack.eightBall(random));
        for (Ball b : sim.balls) view(b);
        hideCue();
        hideGuide();
        game = players.isEmpty() ? null : new EightBall(players.size(), breaker);
        if (game == null) {
            setState(State.IDLE);
            return;
        }
        kitchenOnly = true;
        place();
    }

    void join(Player p) {
        players.add(p.getUniqueId());
        bar.addPlayer(p);
        if (players.size() == 1) {
            rack(0);
            tables.say(p, "Practice. Another player can join this table to play 8-ball against you.");
        } else {
            int breaker = random.nextInt(2);
            for (Player o : online()) {
                o.sendTitle(ChatColor.WHITE + "8-Ball", ChatColor.GRAY + name(0) + " vs " + name(1) + ChatColor.DARK_GRAY + "  ·  " + ChatColor.GRAY + name(breaker) + " breaks", 5, 50, 15);
                bar.addPlayer(o);
            }
            rack(breaker);
        }
    }

    void leave(Player p) {
        int i = players.indexOf(p.getUniqueId());
        if (i < 0) return;
        players.remove(i);
        closeCamera(p.getUniqueId());
        standing.remove(p.getUniqueId());
        bar.removePlayer(p);
        for (int s = 0; s < p.getInventory().getSize(); s++)
            if (Assets.isCue(p.getInventory().getItem(s))) p.getInventory().setItem(s, null);
        hideCue();
        hideGuide();
        if (players.isEmpty()) {
            game = null;
            setState(State.IDLE);
            return;
        }
        // The one left carries on alone with the balls as they lie.
        EightBall practice = new EightBall(1, 0);
        for (Ball b : sim.balls) if (!b.onTable() && b.number != 0) practice.down.add(b.number);
        practice.breakShot = false;
        game = practice;
        Player rest = Bukkit.getPlayer(players.get(0));
        if (rest != null) tables.say(rest, p.getName() + " left; you're practising.");
        if (!sim.ball(0).onTable()) place();
        else if (state != State.ROLLING && state != State.SETTLE) setState(State.AIM);
    }

    private void setState(State s) {
        state = s;
        stateTicks = 0;
        if (s == State.AIM || s == State.PLACE) {
            mode = Mode.AIM;
            gap = REST;
            power = 0;
            // Whoever is not up stands back from the table to watch.
            Player up = shooter();
            for (UUID u : new ArrayList<>(cameras.keySet()))
                if (up == null || !u.equals(up.getUniqueId())) closeCamera(u);
        }
        if (s == State.PLACE) {
            overview = true;
            placeLook = true;
        }
        if (s == State.IDLE || s == State.OVER) for (UUID u : new ArrayList<>(cameras.keySet())) closeCamera(u);
    }

    // ---- the camera ---------------------------------------------------------------------------

    /** The camera {@code p} looks through, seating them if they are not standing by choice. */
    private ShotCamera camera(Player p) {
        if (standing.contains(p.getUniqueId())) return null;
        return cameras.computeIfAbsent(p.getUniqueId(), u -> {
            // The orbit is the third-person view, which only the player can turn on.
            p.sendTitle("", ChatColor.WHITE + "Press F5" + ChatColor.GRAY + " for the shot camera", 5, 60, 15);
            SelfClickFilter.install(p);
            Hotbar.fill(p);
            return new ShotCamera(p);
        });
    }

    ShotCamera cameraOf(Player p) {
        return cameras.get(p.getUniqueId());
    }

    private void closeCamera(UUID u) {
        if (standIn != null && standIn.viewer().getUniqueId().equals(u)) {
            standIn.remove();
            standIn = null;
            mode = Mode.AIM;
        }
        ShotCamera c = cameras.remove(u);
        if (c != null) {
            c.close(standSpot(c.eye()));
            SelfClickFilter.remove(c.player);
            Hotbar.restore(c.player);
        }
    }

    /** Somewhere on the floor beside the table, nearest {@code from}. */
    private Location standSpot(Location from) {
        if (from == null) return null;
        double[] xy = toTable(from.getX(), from.getZ());
        double ax = OUT_X + 0.45, ay = OUT_Y + 0.45;
        double x = xy[0], y = xy[1];
        if (Math.abs(x) < ax && Math.abs(y) < ay) {
            if (ax - Math.abs(x) < ay - Math.abs(y)) x = Math.copySign(ax, x == 0 ? 1 : x);
            else y = Math.copySign(ay, y == 0 ? 1 : y);
        }
        Location l = raw(x, y, 0);
        Location c = raw(0, 0, 0);
        l.setDirection(c.toVector().subtract(l.toVector()));
        return l;
    }

    /** A player got off their seat by sneaking: they walk round the table until they right-click. */
    void gotUp(Player p) {
        ShotCamera c = cameras.get(p.getUniqueId());
        if (c == null) return;
        standing.add(p.getUniqueId());
        cancelStroke();
        Bukkit.getScheduler().runTask(tables.plugin, () -> closeCamera(p.getUniqueId()));
    }

    /** The world yaw (degrees) that looks along {@code angle} in the table's frame. */
    float yawOf(double angle) {
        Vector3f w = rawDir(Math.cos(angle), Math.sin(angle), 0);
        return (float) Math.toDegrees(Math.atan2(-w.x, w.z));
    }

    /** What the camera goes round: the cue ball, or while the balls run, where it was struck. */
    private double pivotX, pivotY;

    /**
     * Where the player's eyes go for the orbit: just above the pivot, so
     * that from {@link #DISTANCE} back the pivot sits {@link #BALL_BELOW}
     * of the screen under its middle.
     */
    private Location pivotEye() {
        double below = Math.atan(2 * BALL_BELOW * Math.tan(Math.toRadians(35 * zoom)));
        return raw(pivotX, pivotY, BED + R + distance * Math.tan(below));
    }

    /**
     * The lowest the player may tilt: any lower and the camera, which goes
     * down as they look up, would be under the cloth.
     */
    private float lowestPitch() {
        double eyeOverCloth = pivotEye().getY() - (cy + BED);
        return (float) -Math.toDegrees(Math.asin(Math.min(1, Math.max(0, eyeOverCloth - 0.025) / distance)));
    }

    /** The orbit's angle in the table's frame from the way the player faces. */
    private double angleFromYaw(float yaw) {
        return angleOf(-Math.sin(Math.toRadians(yaw)), Math.cos(Math.toRadians(yaw)));
    }

    /** The overview's eyes: on the cloth in the middle, the camera {@link #OVERVIEW} straight up from them. */
    private Location overviewEye() {
        return raw(0, 0, BED + 0.01);
    }

    /** Moves {@code cam} to {@code eye}: quickly when it is near, gliding when it has far to go. */
    private static void glide(ShotCamera cam, Location eye) {
        Location was = cam.eye();
        double d = was == null || !was.getWorld().equals(eye.getWorld()) ? 99 : was.distance(eye);
        cam.to(eye, d > 0.25 ? (int) Math.min(14, 6 + d * 3) : 1);
    }

    /** Ball in hand: the cue ball goes where the shooter moves it. */
    void place() {
        Ball c = sim.ball(0);
        if (!c.onTable()) {
            double[] spot = free(TableGeometry.HEAD_STRING - 0.25, 0, -1);
            c.place(spot[0], spot[1]);
            view(c);
        }
        hideCue();
        hideGuide();
        setState(State.PLACE);
    }

    /** The nearest free spot to (x, y), stepping along the long string in direction {@code step}. */
    double[] free(double x, double y, int step) {
        for (int k = 0; k < 200; k++) {
            double tx = x + step * k * 0.01;
            if (Math.abs(tx) > HALF_LENGTH - R) {
                step = -step;
                x = tx + step * 0.01;
                continue;
            }
            if (clear(tx, y, -1)) return new double[]{tx, y};
        }
        return new double[]{x, y};
    }

    boolean clear(double x, double y, int except) {
        for (Ball b : sim.balls)
            if (b.onTable() && b.number != except && Math.hypot(b.x - x, b.y - y) < 2 * R + 0.0005) return false;
        return true;
    }

    // ---- input --------------------------------------------------------------------------------

    /** The mouse wheel: brings the shot camera in and takes it out. */
    void scroll(Player p, int delta) {
        if (mode != Mode.AIM) return;
        distanceTo = Math.max(DISTANCE_MIN, Math.min(DISTANCE_MAX, distanceTo * (delta > 0 ? 1 / 1.08 : 1.08)));
    }

    void centreSpin(Player p) {
        side = height = 0;
        p.playSound(p.getLocation(), "minecraft:ui.button.click", SoundCategory.MASTER, 0.25f, 1.2f);
    }

    /** A left-click during the stroke: the cue goes back to rest and the shot is off. */
    void cancelStroke() {
        if (mode == Mode.STROKE) endHold(shooter());
    }

    /**
     * A right-click: puts the cue ball down where it is shown, or sits a
     * shooter who got up back down at the shot.
     */
    public void click(Player p) {
        if (!p.equals(shooter()) || !Assets.isCue(p.getInventory().getItemInMainHand())) return;
        if (standing.remove(p.getUniqueId())) {
            p.playSound(p.getLocation(), "minecraft:ui.button.click", SoundCategory.MASTER, 0.25f, 1.0f);
            return;
        }
        if (state == State.PLACE) {
            if (placeValid) {
                setState(State.AIM);
                overview = false;
                // Down behind the ball, facing up the table for the break.
                Ball c = sim.ball(0);
                double a = kitchenOnly ? Math.atan2(-c.y, TableGeometry.FOOT_SPOT - c.x) : Math.atan2(-c.y, -c.x);
                p.setRotation(yawOf(a), START_PITCH);
                aim = a;
                p.playSound(p.getLocation(), "pool:cushion", SoundCategory.PLAYERS, 0.4f, 1.6f);
            } else {
                p.playSound(p.getLocation(), "minecraft:block.note_block.bass", SoundCategory.MASTER, 0.5f, 0.6f);
            }
        }
    }

    private boolean placeValid;

    /** Holds S for {@code p} for two seconds, whatever their keys say: for testing the still camera from the console. */
    public void testHold(Player p) {
        if (state == State.AIM && mode == Mode.AIM) {
            startHold(p, Mode.STROKE);
            testHold = 40;
        }
    }

    private int testHold;

    /** Puts the cue ball at (x, y) in the table's frame: for testing from the console. */
    public void placeCue(double x, double y) {
        Ball c = sim.ball(0);
        c.place(x, y);
        view(c);
    }

    /** Sets ball {@code n}'s drawn orientation (a world quaternion): for testing from the console. */
    public void pose(int n, float x, float y, float z, float w) {
        BallView v = views.get(n);
        if (v == null) return;
        v.turn.set(x, y, z, w).normalize();
        applyTurn(v, 0);
    }

    /** A shot without a player at the table: for testing from the console. */
    public void shoot(double degrees, double power, double side, double height) {
        if (state == State.ROLLING || state == State.SETTLE || state == State.STRIKE) return;
        if (!sim.ball(0).onTable()) {
            double[] spot = free(TableGeometry.HEAD_STRING - 0.25, 0, -1);
            sim.ball(0).place(spot[0], spot[1]);
        }
        aim = Math.toRadians(degrees);
        this.power = Math.max(0.02, Math.min(1, power));
        this.side = side;
        this.height = height;
        strikeBack = REST + this.power * 0.30;
        shotSpeed = 0.12 + 9.3 * Math.pow(this.power, 1.65);
        setState(State.STRIKE);
    }

    // ---- the tick -----------------------------------------------------------------------------

    void tick() {
        stateTicks++;
        for (ShotCamera c : cameras.values()) c.refresh();
        Player shooter = shooter();
        if (shotHold) afterShot(shooter);
        switch (state) {
            case PLACE -> placing(shooter);
            case AIM -> aiming(shooter);
            case STRIKE -> striking();
            case ROLLING -> rolling();
            case SETTLE -> {
                rolling();
                if (stateTicks > 8 && !sim.moving() && dropsDone()) judge();
            }
            case OVER -> {
                if (stateTicks > 90) rack(game == null ? 0 : (game.turn + (players.size() > 1 ? 1 : 0)) % Math.max(1, players.size()));
            }
            default -> {
            }
        }
        drawBalls();
        hud(shooter);
    }

    private boolean dropsDone() {
        for (BallView v : views.values()) if (v.dropTick >= 0) return false;
        return true;
    }

    /**
     * Ball in hand, from the overview: W, A, S and D move the cue ball across
     * the cloth (up, left, down and right on the screen), and a right-click
     * puts it down.
     */
    private void placing(Player p) {
        if (p == null || !holding(p)) {
            hint(p, "Hold your cue to place the cue ball");
            return;
        }
        ShotCamera cam = camera(p);
        if (cam == null) return;
        if (placeLook) p.setRotation(overviewYaw(), 90);
        placeLook = false;
        cam.lens(OVERVIEW, 1);
        glide(cam, overviewEye());
        placeValid = clear(sim.ball(0).x, sim.ball(0).y, 0);
        org.bukkit.Input in = p.getCurrentInput();
        int up = (in.isForward() ? 1 : 0) - (in.isBackward() ? 1 : 0);
        int right = (in.isRight() ? 1 : 0) - (in.isLeft() ? 1 : 0);
        if (up == 0 && right == 0) return;
        double a = angleFromYaw(p.getLocation().getYaw());
        double step = PLACE_STEP * (in.isSprint() ? 2.5 : 1);
        // Right on the screen, looking down with the top of the screen along a.
        double dx = Math.cos(a) * up + Math.sin(a) * right, dy = Math.sin(a) * up - Math.cos(a) * right;
        double len = Math.hypot(dx, dy);
        Ball c = sim.ball(0);
        double maxX = kitchenOnly ? TableGeometry.HEAD_STRING : HALF_LENGTH - TableGeometry.CONTACT;
        double x = Math.max(-HALF_LENGTH + TableGeometry.CONTACT, Math.min(maxX, c.x + dx / len * step));
        double y = Math.max(-HALF_WIDTH + TableGeometry.CONTACT, Math.min(HALF_WIDTH - TableGeometry.CONTACT, c.y + dy / len * step));
        if (clear(x, y, 0)) {
            c.x = x;
            c.y = y;
        }
        placeValid = clear(c.x, c.y, 0);
    }

    private boolean holding(Player p) {
        return Assets.isCue(p.getInventory().getItemInMainHand());
    }

    /**
     * Lining up, the Shooterspool way. The mouse aims (left and right, the
     * camera going round the cue ball) and tilts the view (up and down, the
     * camera rising over it), all of it the client's own third-person view.
     * Holding S, the mouse strokes the cue instead: back draws it, forward
     * sends it, and how fast it is going when the tip reaches the ball is the
     * shot. Holding E's stand-in W, the mouse moves where the tip will meet
     * the ball. A and D turn the aim a hair at a time, space looks down on
     * the whole table, and the wheel zooms.
     */
    private void aiming(Player p) {
        if (p == null || shotHold) return;
        if (!holding(p)) {
            if (mode != Mode.AIM) endHold(p);
            hideCue();
            hideGuide();
            return;
        }
        ShotCamera cam = camera(p);
        if (cam == null) {
            hideCue();
            hideGuide();
            return;
        }
        org.bukkit.Input in = p.getCurrentInput();
        Location view = p.getLocation();
        float yaw = view.getYaw(), pitch = view.getPitch();

        switch (mode) {
            case AIM -> {
                if (in.isJump() && !jumpHeld) overview(p, !overview);
                if (in.isBackward() && !overview) startHold(p, Mode.STROKE);
                else if (in.isForward() && !overview) startHold(p, Mode.SPIN);
                else if (!overview) {
                    int turn = (in.isRight() ? 1 : 0) - (in.isLeft() ? 1 : 0);
                    float low = lowestPitch();
                    if (turn != 0) {
                        // A twentieth of a degree a tick, faster the longer it is held.
                        nudgeTicks++;
                        yaw += (float) (turn * Math.min(1.5, 0.05 + nudgeTicks * 0.012));
                        p.setRotation(yaw, Math.max(low, pitch));
                    } else {
                        nudgeTicks = 0;
                        if (pitch < low - 0.5f) p.setRotation(yaw, low);
                        else if (standIn(p) != null) nudgeView(p, yaw);
                    }
                    aim = angleFromYaw((float) (yaw + aimOffset));
                }
                jumpHeld = in.isJump();
            }
            case STROKE -> {
                if (!in.isBackward() && testHold-- <= 0) {
                    endHold(p);
                    break;
                }
                // The mouse turned the view this far since the last tick; it is
                // turned straight back, so the camera holds still.
                double moved = held(p, yaw, pitch)[1];
                double g = Math.min(BRIDGE, gap + moved * tables.strokeScale);
                if (g <= 0 && g < gap) {
                    // The tip has reached the ball, going (gap - g) in this tick.
                    double cueSpeed = (gap - g) / 0.05;
                    // A cue weighs about three balls, and the tip gives back most of the blow.
                    shotSpeed = Math.min(11, cueSpeed * 1.37 * tables.strokePower);
                    power = Math.min(1, shotSpeed / 9.4);
                    if (shotSpeed < 0.04) {
                        endHold(p);
                        break;
                    }
                    // The mouse is still going forward, following through: the
                    // camera stays still until S comes up.
                    mode = Mode.AIM;
                    gap = REST;
                    testHold = 0;
                    shotHold = standIn != null && standIn.shown();
                    strikeBack = 0;
                    setState(State.STRIKE);
                    stateTicks = 2;
                    striking();
                    return;
                }
                // The speed is smoothed: the mouse reaches the server unevenly,
                // and leading by a raw tick's move would twitch at rest.
                gapSpeed = gapSpeed * 0.5 + (Math.max(0, g) - gap) * 0.5;
                if (Math.abs(gapSpeed) < 0.002) gapSpeed = 0;
                gap = Math.max(0, g);
            }
            case SPIN -> {
                if (!in.isForward()) {
                    endHold(p);
                    break;
                }
                double[] moved = held(p, yaw, pitch);
                double s = side + moved[0] * tables.spinScale;
                double h = height - moved[1] * tables.spinScale;
                double len = Math.hypot(s, h);
                if (len > 0.5) {
                    s *= 0.5 / len;
                    h *= 0.5 / len;
                }
                side = s;
                height = h;
            }
        }

        Ball c = sim.ball(0);
        pivotX = c.x;
        pivotY = c.y;
        if (c.x != offsetBallX || c.y != offsetBallY) {
            offsetBallX = c.x;
            offsetBallY = c.y;
            Location b = raw(c.x, c.y, 0);
            offsetX = b.getX();
            offsetZ = b.getZ();
            aimOffset(0);
            lastAimYaw = Float.NaN;
        }
        if (overview) {
            cam.lens(OVERVIEW, 1);
            glide(cam, overviewEye());
        } else {
            // The wheel's steps are taken a share at a time, so a zoom
            // glides and a fast turn of the wheel runs on smoothly. Not while
            // the still camera is up: it was made for one distance.
            if (mode == Mode.AIM) {
                distance += (distanceTo - distance) * 0.3;
                if (Math.abs(distanceTo - distance) < 1e-3) distance = distanceTo;
            }
            cam.lens(distance, zoom);
            glide(cam, pivotEye());
            // Ready for the stroke before it comes.
            if (mode == Mode.AIM && standIn(p) != null) {
                standIn.prepare(pivotEye(), ShotCamera.SCALE, distance / ShotCamera.SCALE);
                standIn.face(pivotEye(), yaw, ShotCamera.SCALE, distance / ShotCamera.SCALE);
            }
        }
        // Looked at from under the cue's line, the cue would hang across the
        // view from above: it is put away until the camera is over it again.
        double camUp = Math.sin(Math.toRadians(mode == Mode.AIM ? pitch : heldPitch)) * distance
                + (pivotEye().getY() - (cy + BED + R));
        double cueUp = Math.tan(elevation(c, Math.cos(aim), Math.sin(aim))) * distance;
        // Stroking, the cue is drawn a tick ahead of where the mouse last put
        // it, at the speed it is going: the mouse takes a tick or two to reach
        // the server, and this is most of that back.
        double shown = mode == Mode.STROKE ? Math.max(0, Math.min(BRIDGE, gap + gapSpeed)) : REST;
        if (mode == Mode.STROKE || overview || camUp > cueUp + 0.02) drawCue(shown);
        else hideCue();
        if (tables.guide && !overview) drawGuide();
        else hideGuide();
    }

    /**
     * While S or W is held the mouse works the cue, and the view must not
     * move: what it turned since the last tick (yaw, pitch, degrees). With
     * the stand-in up the camera is not the player's, so their view is free
     * to turn; without it, the view is put back where it was held, which
     * shakes a little.
     */
    private double[] held(Player p, float yaw, float pitch) {
        if (standIn != null && standIn.shown()) {
            double dy = Math.IEEEremainder(yaw - lastYaw, 360), dp = pitch - lastPitch;
            lastYaw = yaw;
            lastPitch = pitch;
            return new double[]{dy, dp};
        }
        double dy = Math.IEEEremainder(yaw - heldYaw, 360), dp = pitch - heldPitch;
        if (dy != 0 || dp != 0) p.setRotation(heldYaw, heldPitch);
        return new double[]{dy, dp};
    }

    /** S or W went down: the camera holds still and the mouse works the cue. */
    private void startHold(Player p, Mode m) {
        mode = m;
        gapSpeed = 0;
        Location view = p.getLocation();
        heldYaw = lastYaw = view.getYaw();
        heldPitch = lastPitch = view.getPitch();
        aim = angleFromYaw((float) (heldYaw + aimOffset));
        gap = REST;
        if (standIn(p) != null) standIn.show(pivotEye(), heldYaw, heldPitch, ShotCamera.SCALE, distance / ShotCamera.SCALE, drawn());
    }

    /** The shooter's stand-in, where the server can make one. */
    private StandIn standIn(Player p) {
        if (!StandIn.available(tables.plugin.getLogger())) return null;
        if (standIn != null && standIn.viewer() != p) {
            standIn.remove();
            standIn = null;
        }
        if (standIn == null) standIn = new StandIn(p);
        return standIn;
    }

    /** S or W came up (or the shot went): the cue goes back to rest, the camera back to the player as it was. */
    private void endHold(Player p) {
        mode = Mode.AIM;
        gap = REST;
        testHold = 0;
        if (standIn != null) standIn.hide(new float[]{heldYaw, heldPitch}, drawn());
    }

    /** Whether the camera is still held after the shot, until S comes up. */
    private boolean shotHold;

    /** S came up after the shot (or the camera went): the camera back to the player as it was. */
    private void afterShot(Player p) {
        if (p != null && p.getCurrentInput().isBackward() && standIn != null && standIn.shown()) return;
        shotHold = false;
        if (standIn != null) standIn.hide(new float[]{heldYaw, heldPitch}, drawn());
    }

    private float overviewYaw, overviewPitch;
    private boolean placeLook;

    /** Looking straight down with the table's length across the screen. */
    private float overviewYaw() {
        return yawOf(Math.PI / 2);
    }

    /** Space: up over the middle of the table looking straight down, and back. */
    private void overview(Player p, boolean on) {
        overview = on;
        if (on) {
            Location v = p.getLocation();
            overviewYaw = v.getYaw();
            overviewPitch = v.getPitch();
            p.setRotation(overviewYaw(), 90);
        } else {
            p.setRotation(overviewYaw, overviewPitch);
        }
    }

    private void striking() {
        if (stateTicks <= 1) {
            drawCue(strikeBack * 0.45);
            return;
        }
        if (stateTicks == 2) {
            drawCue(0);
            hideGuide();
            Ball c = sim.ball(0);
            pivotX = c.x;
            pivotY = c.y;
            double speed = shotSpeed;
            Simulation.strike(c, aim, speed, side, height);
            record = new ShotRecord();
            Location at = at(c.x, c.y, BED + R);
            world.playSound(at, "pool:cue", SoundCategory.PLAYERS, (float) Math.min(1, 0.25 + speed / 5), (float) (0.9 + random.nextDouble() * 0.15));
            kitchenOnly = false;
            state = State.ROLLING;
            stateTicks = 0;
        }
    }

    private void rolling() {
        List<Simulation.Event> events = sim.tick();
        for (Simulation.Event e : events) record.add(e);
        sounds(events);
        if (state == State.ROLLING) {
            // The cue follows through, then is put away.
            if (stateTicks == 1) drawCueFollow();
            if (stateTicks == 10) hideCue();
            if (!sim.moving() && dropsDone() && stateTicks > 2) {
                state = State.SETTLE;
                stateTicks = 0;
            }
        }
    }

    private void drawCueFollow() {
        if (cue == null || !cue.valid()) return;
        // Through the ball, on the same line: the cue only slides along it.
        Transformation t = cue.display().getTransformation();
        Vector3f along = new Quaternionf(t.getLeftRotation()).transform(new Vector3f(0, 1, 0));
        Display d = cue.display();
        d.setInterpolationDelay(0);
        d.setInterpolationDuration(2);
        d.setTransformation(new Transformation(new Vector3f(t.getTranslation()).sub(along.mul(0.06f)), t.getLeftRotation(),
                t.getScale(), t.getRightRotation()));
    }

    private void sounds(List<Simulation.Event> events) {
        // The loudest few each tick: a break is a hundred clicks in a second.
        events.sort((a, b) -> Double.compare(b.speed(), a.speed()));
        int n = 0;
        for (Simulation.Event e : events) {
            if (n >= 5) break;
            Ball b = sim.ball(e.a());
            Location at = at(b.x, b.y, BED + R);
            switch (e.kind()) {
                case BALL -> {
                    if (e.speed() < 0.03) continue;
                    world.playSound(at, "pool:ball", SoundCategory.PLAYERS, (float) Math.min(1.0, 0.08 + e.speed() / 2.5),
                            (float) (0.92 + random.nextDouble() * 0.16));
                }
                case CUSHION -> {
                    if (e.speed() < 0.05) continue;
                    world.playSound(at, "pool:cushion", SoundCategory.PLAYERS, (float) Math.min(1.0, 0.1 + e.speed() / 3),
                            (float) (0.9 + random.nextDouble() * 0.2));
                }
                case POCKET -> {
                    TableGeometry.Pocket p = sim.table.pockets.get(e.b());
                    world.playSound(at(p.holeX(), p.holeY(), BED), "pool:pocket", SoundCategory.PLAYERS, 0.9f, (float) (0.9 + random.nextDouble() * 0.2));
                }
            }
            n++;
        }
    }

    private void judge() {
        if (game == null) {
            setState(State.IDLE);
            return;
        }
        int shot = game.turn;
        EightBall.Verdict v = game.judge(record);
        if (v.spotEight()) {
            Ball eight = sim.ball(8);
            double[] spot = free(TableGeometry.FOOT_SPOT, 0, 1);
            eight.place(spot[0], spot[1]);
            BallView old = views.remove(8);
            if (old != null) removeView(old);
            view(eight);
        }
        for (Player o : online()) {
            if (v.winner() >= 0) {
                String who = game.practice() ? "" : name(v.winner()) + " wins";
                o.sendTitle(ChatColor.WHITE + v.headline(), ChatColor.GRAY + (who.isEmpty() ? v.detail() : who + "  ·  " + v.detail()), 5, 70, 20);
            } else if (v.headline() != null) {
                String sub = v.detail() == null ? "" : v.detail();
                if (!game.practice() && v.foul()) sub += "  " + name(game.turn) + " has ball in hand.";
                o.sendTitle((v.foul() ? ChatColor.RED : ChatColor.WHITE) + v.headline(), ChatColor.GRAY + sub, 3, 40, 12);
            } else if (v.detail() != null) {
                o.sendTitle("", ChatColor.GRAY + v.detail(), 3, 30, 10);
            }
        }
        if (v.winner() >= 0) {
            for (Player p : online()) p.playSound(p.getLocation(), "minecraft:ui.toast.challenge_complete", SoundCategory.MASTER, 0.6f, 1f);
            setState(State.OVER);
            return;
        }
        if (!game.practice() && game.turn != shot) {
            Player next = shooter();
            if (next != null) next.playSound(next.getLocation(), "minecraft:block.note_block.pling", SoundCategory.MASTER, 0.5f, 1.4f);
        }
        side = height = 0;
        if (v.inHand() == EightBall.InHand.ANYWHERE || !sim.ball(0).onTable()) place();
        else setState(State.AIM);
    }

    // ---- the HUD ------------------------------------------------------------------------------

    private void hud(Player shooter) {
        if (bar != null) bar.setTitle(barTitle());
        if (game == null) return;
        for (Player p : online()) {
            if (!p.equals(shooter)) {
                Hud.actionBar(p, ChatColor.GRAY + (shooter == null ? "Waiting" : shooter.getName() + " is shooting"));
                continue;
            }
            switch (state) {
                case PLACE -> {
                    if (!holding(p)) Hud.actionBar(p, ChatColor.GRAY + "Hold your cue to place the cue ball");
                    else Hud.actionBar(p, ChatColor.WHITE + "Ball in hand" + ChatColor.GRAY + (kitchenOnly ? " behind the head string" : "")
                            + "  ·  W/A/S/D move it (sprint for faster), right-click to put it down" + (placeValid ? "" : ChatColor.RED + "  (touching a ball)"));
                }
                case AIM -> {
                    if (!holding(p)) Hud.actionBar(p, ChatColor.GRAY + "Hold your cue to shoot");
                    else if (standing.contains(p.getUniqueId())) Hud.actionBar(p, ChatColor.GRAY + "Right-click to take your shot");
                    else Hud.shot(p, side, height, mode.name(), overview);
                }
                default -> {
                }
            }
        }
    }

    private String barTitle() {
        if (game == null) return "";
        if (game.practice()) {
            int left = 0;
            for (Ball b : sim.balls) if (b.number != 0 && b.onTable()) left++;
            return ChatColor.WHITE + "Practice" + ChatColor.DARK_GRAY + "  ·  " + ChatColor.GRAY + left + " balls left"
                    + ChatColor.DARK_GRAY + "  ·  " + ChatColor.GRAY + "/pool rack, /pool leave";
        }
        StringBuilder s = new StringBuilder();
        for (int i = 0; i < 2 && i < players.size(); i++) {
            boolean up = game.turn == i;
            if (i == 1) s.append(ChatColor.DARK_GRAY).append("     vs     ");
            s.append(up ? ChatColor.YELLOW + "▶ " : "").append(up ? ChatColor.WHITE : ChatColor.GRAY).append(name(i));
            EightBall.Group g = game.groupOf(i);
            if (g != EightBall.Group.NONE) {
                s.append(ChatColor.DARK_GRAY).append(" (").append(ChatColor.GRAY).append(g.label).append(" ")
                        .append(game.onEight(i) ? "· on the 8" : "· " + game.left(i)).append(ChatColor.DARK_GRAY).append(")");
            }
        }
        if (game.open() && !game.breakShot) s.append(ChatColor.DARK_GRAY).append("   ·   ").append(ChatColor.GRAY).append("open table");
        return s.toString();
    }

    private void hint(Player p, String text) {
        if (p != null) Hud.actionBar(p, ChatColor.GRAY + text);
    }
}
