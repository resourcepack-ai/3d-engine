package ai.resourcepack.pool3d.game;

import ai.resourcepack.pool3d.physics.Ball;
import ai.resourcepack.pool3d.physics.Simulation;
import ai.resourcepack.pool3d.physics.TableGeometry;

import static ai.resourcepack.pool3d.physics.TableGeometry.CONTACT;
import static ai.resourcepack.pool3d.physics.TableGeometry.R;

/**
 * The aiming guide: where the cue ball goes along the line of the shot, and
 * what it meets first, a ball or a cushion. For a ball, the ghost ball (where
 * the cue ball will be at contact), the line the object ball leaves on and the
 * tangent line the cue ball would take with no spin.
 */
public final class Aim {

    /**
     * {@code hit} is the ball met, or -1 for a cushion; (gx, gy) the ghost
     * ball; (ox, oy) the object ball's way out (a unit vector) and (tx, ty) the
     * cue ball's tangent; for a cushion, (ox, oy) is the bounce.
     */
    public record Guide(double gx, double gy, int hit, double ox, double oy, double tx, double ty) {
    }

    private Aim() {
    }

    public static Guide trace(Simulation sim, Ball cue, double angle) {
        double dx = Math.cos(angle), dy = Math.sin(angle);
        // The first ball: a ray against circles of radius 2R.
        double best = Double.MAX_VALUE;
        Ball first = null;
        for (Ball b : sim.balls) {
            if (b == cue || !b.onTable()) continue;
            double fx = b.x - cue.x, fy = b.y - cue.y;
            double along = fx * dx + fy * dy;
            if (along <= 0) continue;
            double off2 = fx * fx + fy * fy - along * along;
            double r2 = 4 * R * R;
            if (off2 > r2) continue;
            double t = along - Math.sqrt(r2 - off2);
            if (t < best) {
                best = t;
                first = b;
            }
        }
        // The first cushion: the ray against each nose pushed out by the contact distance.
        double wall = Double.MAX_VALUE, wnx = 0, wny = 0;
        for (TableGeometry.Segment s : sim.table.cushions) {
            double ex = s.bx() - s.ax(), ey = s.by() - s.ay(), len = Math.hypot(ex, ey);
            double nx = -ey / len, ny = ex / len;
            // The side of the segment the cue ball is on.
            if ((cue.x - s.ax()) * nx + (cue.y - s.ay()) * ny < 0) {
                nx = -nx;
                ny = -ny;
            }
            double denom = dx * nx + dy * ny;
            if (denom >= -1e-9) continue;
            double ax = s.ax() + nx * CONTACT, ay = s.ay() + ny * CONTACT;
            double t = ((ax - cue.x) * nx + (ay - cue.y) * ny) / denom;
            if (t <= 0 || t >= wall) continue;
            double px = cue.x + dx * t, py = cue.y + dy * t;
            double u = ((px - ax) * ex + (py - ay) * ey) / (len * len);
            if (u < 0 || u > 1) continue;
            wall = t;
            wnx = nx;
            wny = ny;
        }
        if (first != null && best <= wall) {
            double gx = cue.x + dx * best, gy = cue.y + dy * best;
            double ox = first.x - gx, oy = first.y - gy, ol = Math.hypot(ox, oy);
            ox /= ol;
            oy /= ol;
            // The tangent: the shot's direction less its part along the line of centres.
            double along = dx * ox + dy * oy;
            double tx = dx - along * ox, ty = dy - along * oy, tl = Math.hypot(tx, ty);
            if (tl > 1e-6) {
                tx /= tl;
                ty /= tl;
            } else {
                tx = ty = 0;
            }
            return new Guide(gx, gy, first.number, ox, oy, tx, ty);
        }
        if (wall == Double.MAX_VALUE) {
            // Straight through a pocket's mouth: to where it leaves the bed.
            double tx = dx == 0 ? Double.MAX_VALUE : ((dx > 0 ? TableGeometry.HALF_LENGTH : -TableGeometry.HALF_LENGTH) - cue.x) / dx;
            double ty = dy == 0 ? Double.MAX_VALUE : ((dy > 0 ? TableGeometry.HALF_WIDTH : -TableGeometry.HALF_WIDTH) - cue.y) / dy;
            double t = Math.max(0, Math.min(tx, ty));
            return new Guide(cue.x + dx * t, cue.y + dy * t, -2, 0, 0, 0, 0);
        }
        double gx = cue.x + dx * wall, gy = cue.y + dy * wall;
        double dot = dx * wnx + dy * wny;
        return new Guide(gx, gy, -1, dx - 2 * dot * wnx, dy - 2 * dot * wny, 0, 0);
    }
}
