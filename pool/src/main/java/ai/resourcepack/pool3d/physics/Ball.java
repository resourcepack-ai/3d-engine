package ai.resourcepack.pool3d.physics;

/**
 * One ball, in the table's frame (metres, metres per second, radians per
 * second): where it is, how it moves and how it spins.
 *
 * Spin is the whole of it. {@code wx} and {@code wy} are the roll (or the
 * top and back spin fighting it), {@code wz} is side spin, about the vertical.
 * A ball whose contact point with the cloth is not still is {@link Motion#SLIDING},
 * and the cloth's friction works on that until it rolls.
 */
public final class Ball {

    public enum Motion {STILL, SLIDING, ROLLING, POCKETED}

    public final int number;
    public double x, y;
    public double vx, vy;
    public double wx, wy, wz;
    public Motion motion = Motion.STILL;
    /** The pocket it went down, once it has. */
    public int pocket = -1;
    /** Rotation this tick, accumulated in the table frame (radians about each axis), for drawing. */
    public double turnX, turnY, turnZ;

    public Ball(int number, double x, double y) {
        this.number = number;
        this.x = x;
        this.y = y;
    }

    public boolean onTable() {
        return motion != Motion.POCKETED;
    }

    public boolean moving() {
        return motion == Motion.SLIDING || motion == Motion.ROLLING || (motion == Motion.STILL && wz != 0);
    }

    public double speed() {
        return Math.hypot(vx, vy);
    }

    /** Puts it down still at (x, y). */
    public void place(double x, double y) {
        this.x = x;
        this.y = y;
        vx = vy = wx = wy = wz = 0;
        motion = Motion.STILL;
        pocket = -1;
    }

    /** Velocity of the point touching the cloth, x and y. */
    double slipX() {
        return vx - TableGeometry.R * wy;
    }

    double slipY() {
        return vy + TableGeometry.R * wx;
    }
}
