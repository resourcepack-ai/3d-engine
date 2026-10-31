package ai.resourcepack.pool3d.physics;

import java.util.ArrayList;
import java.util.List;

import static ai.resourcepack.pool3d.physics.TableGeometry.CONTACT;
import static ai.resourcepack.pool3d.physics.TableGeometry.MASS;
import static ai.resourcepack.pool3d.physics.TableGeometry.R;

/**
 * The balls on one table, stepped 2000 times a second.
 *
 * <p>Between collisions a ball either slides or rolls. Sliding, the cloth's
 * friction ({@link #MU_SLIDE}) works against the velocity of the point touching
 * it, which slows the ball and turns its spin towards a roll until the two
 * agree; a stun shot stops dead, a draw shot comes back and a follow shot runs
 * on, all from that one rule. Rolling, only the much smaller rolling
 * resistance acts. Side spin decays on its own.
 *
 * <p>Collisions are impulses. Between two balls: a nearly elastic push along
 * the line of centres, plus friction at the contact that throws the object
 * ball off the line of the cut and passes spin across (Alciatore's
 * speed-dependent friction). Against a cushion: the nose touches the ball
 * above its middle, so the push has a downward part the slate takes up, the
 * restitution falls with speed, and friction at the nose is what makes side
 * spin change the angle off the rail.
 */
public final class Simulation {

    public static final double G = 9.81;
    public static final double MU_SLIDE = 0.2;
    public static final double MU_ROLL = 0.01;
    /** Side spin's decay, rad/s^2. */
    public static final double SPIN_DECEL = 10.9;
    public static final double MU_CUSHION = 0.2;
    public static final double E_BALL = 0.95;
    public static final int SUBSTEPS = 100;
    public static final double DT = 0.05 / SUBSTEPS;
    private static final double INERTIA = 0.4 * MASS * R * R;

    public enum Kind {BALL, CUSHION, POCKET}

    /** Something that happened: two balls met, a ball hit a cushion, or went down a pocket. */
    public record Event(Kind kind, int a, int b, double speed) {
    }

    public final TableGeometry table;
    public final List<Ball> balls = new ArrayList<>();
    private final List<Event> events = new ArrayList<>();

    public Simulation(TableGeometry table) {
        this.table = table;
    }

    public Ball ball(int number) {
        for (Ball b : balls) if (b.number == number) return b;
        return null;
    }

    /** Whether anything is still moving. */
    public boolean moving() {
        for (Ball b : balls) if (b.moving()) return true;
        return false;
    }

    /** One game tick (50 ms): what happened in it. */
    public List<Event> tick() {
        for (Ball b : balls) b.turnX = b.turnY = b.turnZ = 0;
        for (int i = 0; i < SUBSTEPS; i++) step(DT);
        List<Event> out = new ArrayList<>(events);
        events.clear();
        return out;
    }

    /**
     * Strikes the cue ball: {@code angle} the direction in the table's frame
     * (radians from +x towards +y), {@code speed} what the ball leaves the tip
     * at, and where the tip meets it, {@code side} right of centre and
     * {@code height} above, both in ball radii.
     *
     * Hitting off centre sends the ball slightly the other way (squirt), about
     * a degree at half a ball of side.
     */
    public static void strike(Ball cue, double angle, double speed, double side, double height) {
        double dx = Math.cos(angle), dy = Math.sin(angle);
        double squirt = -0.04 * side;
        double sx = Math.cos(angle + squirt), sy = Math.sin(angle + squirt);
        cue.vx = speed * sx;
        cue.vy = speed * sy;
        double k = 5 * speed / (2 * R);
        cue.wx = -k * height * dy;
        cue.wy = k * height * dx;
        cue.wz = k * side;
        cue.motion = Ball.Motion.SLIDING;
    }

    void step(double dt) {
        for (Ball b : balls) if (b.onTable()) move(b, dt);
        for (Ball b : balls) if (b.onTable()) cushions(b);
        collide();
        for (Ball b : balls) if (b.onTable() && b.motion != Ball.Motion.STILL) pockets(b);
    }

    private void move(Ball b, double dt) {
        if (b.wz != 0) {
            double d = SPIN_DECEL * dt;
            b.wz = Math.abs(b.wz) <= d ? 0 : b.wz - Math.signum(b.wz) * d;
        }
        switch (b.motion) {
            case SLIDING -> {
                double ux = b.slipX(), uy = b.slipY(), u = Math.hypot(ux, uy);
                if (u <= 3.5 * MU_SLIDE * G * dt) {
                    // It starts to roll within this step: the contact point
                    // comes to rest with the ball going 2/7 of the slip slower.
                    b.vx -= ux * 2 / 7;
                    b.vy -= uy * 2 / 7;
                    roll(b);
                } else {
                    double f = MU_SLIDE * G * dt / u;
                    b.vx -= f * ux;
                    b.vy -= f * uy;
                    double s = 2.5 / R * f;
                    b.wx -= s * uy;
                    b.wy += s * ux;
                }
            }
            case ROLLING -> {
                double v = b.speed(), d = MU_ROLL * G * dt;
                if (v <= d) {
                    b.vx = b.vy = b.wx = b.wy = 0;
                    b.motion = Ball.Motion.STILL;
                } else {
                    b.vx -= b.vx / v * d;
                    b.vy -= b.vy / v * d;
                    roll(b);
                }
            }
            default -> {
            }
        }
        b.x += b.vx * dt;
        b.y += b.vy * dt;
        b.turnX += b.wx * dt;
        b.turnY += b.wy * dt;
        b.turnZ += b.wz * dt;
    }

    /** Spin to match the velocity: rolling without slipping. */
    private static void roll(Ball b) {
        b.wx = -b.vy / R;
        b.wy = b.vx / R;
        b.motion = b.vx == 0 && b.vy == 0 ? Ball.Motion.STILL : Ball.Motion.ROLLING;
    }

    // ---- cushions -------------------------------------------------------------------

    private void cushions(Ball b) {
        for (TableGeometry.Segment s : table.cushions) {
            double ex = s.bx() - s.ax(), ey = s.by() - s.ay();
            double t = ((b.x - s.ax()) * ex + (b.y - s.ay()) * ey) / (ex * ex + ey * ey);
            t = Math.max(0, Math.min(1, t));
            double cx = s.ax() + ex * t, cy = s.ay() + ey * t;
            double dx = b.x - cx, dy = b.y - cy, d2 = dx * dx + dy * dy;
            if (d2 >= CONTACT * CONTACT || d2 < 1e-18) continue;
            double d = Math.sqrt(d2), nx = dx / d, ny = dy / d;
            b.x = cx + nx * CONTACT;
            b.y = cy + ny * CONTACT;
            double vn = b.vx * nx + b.vy * ny;
            if (vn < 0) cushion(b, nx, ny, vn);
        }
    }

    /**
     * A ball meeting a cushion whose nose is in direction -n from it, at
     * {@code vn} (negative) towards it.
     */
    private void cushion(Ball b, double nx, double ny, double vn) {
        double speed = -vn;
        double e = Math.max(0.6, Math.min(0.82, 0.39 + 0.257 * speed - 0.044 * speed * speed));
        double cos = Math.cos(TableGeometry.NOSE_ANGLE), sin = Math.sin(TableGeometry.NOSE_ANGLE);
        // The contact point from the centre: towards the cushion and up.
        double cx = -R * cos * nx, cy = -R * cos * ny, cz = R * sin;
        // The push, along the line from contact to centre (down into the slate).
        double Nx = cos * nx, Ny = cos * ny, Nz = -sin;
        double j = MASS * (1 + e) * speed / cos;
        // Slip of the contact point before the push.
        double sx = b.vx + (b.wy * cz - b.wz * cy);
        double sy = b.vy + (b.wz * cx - b.wx * cz);
        double sz = (b.wx * cy - b.wy * cx);
        double sn = sx * Nx + sy * Ny + sz * Nz;
        sx -= sn * Nx;
        sy -= sn * Ny;
        sz -= sn * Nz;
        double slip = Math.sqrt(sx * sx + sy * sy + sz * sz);
        b.vx += j * Nx / MASS;
        b.vy += j * Ny / MASS;
        if (slip > 1e-9) {
            double f = Math.min(MU_CUSHION * j, 2 * MASS * slip / 7) / slip;
            double px = -f * sx, py = -f * sy, pz = -f * sz;
            b.vx += px / MASS;
            b.vy += py / MASS;
            b.wx += (cy * pz - cz * py) / INERTIA;
            b.wy += (cz * px - cx * pz) / INERTIA;
            b.wz += (cx * py - cy * px) / INERTIA;
        }
        b.motion = Ball.Motion.SLIDING;
        events.add(new Event(Kind.CUSHION, b.number, -1, speed));
    }

    // ---- balls ------------------------------------------------------------------------

    private void collide() {
        int n = balls.size();
        for (int pass = 0; pass < 12; pass++) {
            boolean any = false;
            for (int i = 0; i < n; i++) {
                Ball a = balls.get(i);
                if (!a.onTable()) continue;
                for (int k = i + 1; k < n; k++) {
                    Ball c = balls.get(k);
                    if (!c.onTable()) continue;
                    double dx = c.x - a.x, dy = c.y - a.y, d2 = dx * dx + dy * dy;
                    if (d2 >= 4 * R * R || d2 < 1e-18) continue;
                    double d = Math.sqrt(d2), nx = dx / d, ny = dy / d;
                    double over = (2 * R - d) / 2;
                    a.x -= nx * over;
                    a.y -= ny * over;
                    c.x += nx * over;
                    c.y += ny * over;
                    double vn = (a.vx - c.vx) * nx + (a.vy - c.vy) * ny;
                    if (vn > 1e-9) {
                        hit(a, c, nx, ny, vn);
                        if (pass == 0 || vn > 0.05) events.add(new Event(Kind.BALL, a.number, c.number, vn));
                        any = true;
                    }
                }
            }
            if (!any) break;
        }
    }

    private static void hit(Ball a, Ball c, double nx, double ny, double vn) {
        double jn = MASS * (1 + E_BALL) / 2 * vn;
        // Velocity of each ball's surface at the contact point (a's at +R n, c's at -R n).
        double rx = R * nx, ry = R * ny;
        double ax = a.vx - a.wz * ry, ay = a.vy + a.wz * rx, az = a.wx * ry - a.wy * rx;
        double cx = c.vx + c.wz * ry, cy = c.vy - c.wz * rx, cz = -(c.wx * ry - c.wy * rx);
        double sx = ax - cx, sy = ay - cy, sz = az - cz;
        double sn = sx * nx + sy * ny;
        sx -= sn * nx;
        sy -= sn * ny;
        double slip = Math.sqrt(sx * sx + sy * sy + sz * sz);
        a.vx -= jn * nx / MASS;
        a.vy -= jn * ny / MASS;
        c.vx += jn * nx / MASS;
        c.vy += jn * ny / MASS;
        if (slip > 1e-9) {
            double mu = 9.951e-3 + 0.108 * Math.exp(-1.088 * slip);
            double f = Math.min(mu * jn, MASS * slip / 7) / slip;
            double fx = -f * sx, fy = -f * sy, fz = -f * sz;
            a.vx += fx / MASS;
            a.vy += fy / MASS;
            c.vx -= fx / MASS;
            c.vy -= fy / MASS;
            // r x F, the same turn for both balls.
            double tx = ry * fz / INERTIA, ty = -rx * fz / INERTIA, tz = (rx * fy - ry * fx) / INERTIA;
            a.wx += tx;
            a.wy += ty;
            a.wz += tz;
            c.wx += tx;
            c.wy += ty;
            c.wz += tz;
        }
        a.motion = Ball.Motion.SLIDING;
        c.motion = Ball.Motion.SLIDING;
    }

    // ---- pockets ----------------------------------------------------------------------

    private void pockets(Ball b) {
        for (TableGeometry.Pocket p : table.pockets) {
            if (p.past(b.x, b.y) > p.shelf()) {
                drop(b, p.index());
                return;
            }
        }
        // Nothing should get past the cushions; if a ball ever does, it went down the nearest hole.
        if (Math.abs(b.x) > TableGeometry.HALF_LENGTH + 0.12 || Math.abs(b.y) > TableGeometry.HALF_WIDTH + 0.12) {
            TableGeometry.Pocket near = null;
            double best = Double.MAX_VALUE;
            for (TableGeometry.Pocket p : table.pockets) {
                double d = Math.hypot(b.x - p.holeX(), b.y - p.holeY());
                if (d < best) {
                    best = d;
                    near = p;
                }
            }
            drop(b, near.index());
        }
    }

    private void drop(Ball b, int pocket) {
        events.add(new Event(Kind.POCKET, b.number, pocket, b.speed()));
        b.motion = Ball.Motion.POCKETED;
        b.pocket = pocket;
        b.vx = b.vy = b.wx = b.wy = b.wz = 0;
    }
}
