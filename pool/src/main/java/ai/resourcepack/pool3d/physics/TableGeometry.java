package ai.resourcepack.pool3d.physics;

import java.util.ArrayList;
import java.util.List;

/**
 * A 9 ft pool table in its own frame, in metres: x along the length, y across
 * it, z up from the cloth, the middle of the bed at the origin. The head of the
 * table (where you break from) is at -x.
 *
 * Sizes are the WPA's: a 100 x 50 in playing surface measured nose to nose,
 * 2 1/4 in balls, and Gold Crown pockets: 4 7/8 in across the points at the
 * corners, 5 3/8 in at the sides,
 * the cushion nose 63.5% of a ball's height above the cloth, corner jaws cut at
 * 142 degrees and sides at 104.
 *
 * The cushions are what a ball can touch: a straight nose between each pair of
 * pockets and a jaw running back from each point. A ball touches rubber with a
 * point above its middle, so in plan its centre stops {@link #CONTACT} from the
 * nose rather than a whole radius.
 */
public final class TableGeometry {

    public static final double R = 0.028575;
    public static final double MASS = 0.17;
    public static final double HALF_LENGTH = 1.27;
    public static final double HALF_WIDTH = 0.635;
    /** Cushion nose height above the cloth. */
    public static final double NOSE = 0.0363;
    /** The angle above the ball's equator at which it meets the nose. */
    public static final double NOSE_ANGLE = Math.asin((NOSE - R) / R);
    /** How close a ball's centre comes to a cushion nose, in plan. */
    public static final double CONTACT = R * Math.cos(NOSE_ANGLE);

    public static final double CORNER_MOUTH = 0.124;
    public static final double SIDE_MOUTH = 0.137;
    /** How far past the line between a pocket's points the slate ends and a ball drops. */
    public static final double CORNER_SHELF = 0.025;
    public static final double SIDE_SHELF = 0.010;
    public static final double CORNER_HOLE = 0.064;
    public static final double SIDE_HOLE = 0.068;
    static final double JAW_LENGTH = 0.085;

    /** The head string: the line a breaker places the cue ball behind. */
    public static final double HEAD_STRING = -HALF_LENGTH / 2;
    public static final double FOOT_SPOT = HALF_LENGTH / 2;

    /** A straight piece of cushion nose, from (ax, ay) to (bx, by). */
    public record Segment(double ax, double ay, double bx, double by) {
    }

    /**
     * A pocket: the middle of the line between its points, the way out of the
     * table across it, how far past that line the slate ends, and the hole.
     */
    public record Pocket(int index, boolean corner, double mx, double my, double nx, double ny, double shelf,
                         double holeX, double holeY, double holeRadius) {
        /** How far past the points a centre at (x, y) is. */
        public double past(double x, double y) {
            return (x - mx) * nx + (y - my) * ny;
        }
    }

    public final List<Segment> cushions = new ArrayList<>();
    public final List<Pocket> pockets = new ArrayList<>();
    /** The two points of each pocket, in pocket order: {x0, y0, x1, y1}. */
    public final List<double[]> points = new ArrayList<>();

    public static final TableGeometry NINE_FOOT = new TableGeometry();

    private TableGeometry() {
        double a = HALF_LENGTH, b = HALF_WIDTH;
        double mc = CORNER_MOUTH / Math.sqrt(2), ms = SIDE_MOUTH / 2;
        // Rail noses: the long rails in two halves either side of the side
        // pocket, the short rails whole.
        for (int sy : new int[]{-1, 1}) {
            cushions.add(new Segment(-a + mc, sy * b, -ms, sy * b));
            cushions.add(new Segment(ms, sy * b, a - mc, sy * b));
        }
        for (int sx : new int[]{-1, 1}) cushions.add(new Segment(sx * a, -b + mc, sx * a, b - mc));

        // Pockets in a fixed order: the four corners, then the two sides.
        int i = 0;
        for (int sx : new int[]{-1, 1})
            for (int sy : new int[]{-1, 1}) {
                double lx = sx * (a - mc), ly = sy * b; // point on the long rail
                double sxp = sx * a, syp = sy * (b - mc); // point on the short rail
                // A jaw leaves its point 142 degrees round from the nose it ends,
                // back into the rubber: 38 degrees off the long rail towards the
                // pocket, 52 off it from the short rail.
                double j1 = Math.toRadians(38), j2 = Math.toRadians(52);
                jaw(lx, ly, sx * Math.cos(j1), sy * Math.sin(j1));
                jaw(sxp, syp, sx * Math.cos(j2), sy * Math.sin(j2));
                double n = Math.sqrt(0.5);
                double mx = (lx + sxp) / 2, my = (ly + syp) / 2;
                double nx = sx * n, ny = sy * n;
                pockets.add(new Pocket(i++, true, mx, my, nx, ny, CORNER_SHELF,
                        mx + nx * (CORNER_SHELF + CORNER_HOLE), my + ny * (CORNER_SHELF + CORNER_HOLE), CORNER_HOLE));
                points.add(new double[]{lx, ly, sxp, syp});
            }
        for (int sy : new int[]{-1, 1}) {
            double j = Math.toRadians(104);
            jaw(ms, sy * b, Math.cos(j), sy * Math.sin(j));
            jaw(-ms, sy * b, -Math.cos(j), sy * Math.sin(j));
            pockets.add(new Pocket(i++, false, 0, sy * b, 0, sy, SIDE_SHELF,
                    0, sy * (b + SIDE_SHELF + SIDE_HOLE), SIDE_HOLE));
            points.add(new double[]{ms, sy * b, -ms, sy * b});
        }
    }

    private void jaw(double x, double y, double dx, double dy) {
        cushions.add(new Segment(x, y, x + dx * JAW_LENGTH, y + dy * JAW_LENGTH));
    }

    /** Whether a ball centred at (x, y) would sit on the cloth clear of every cushion. */
    public boolean onCloth(double x, double y) {
        return Math.abs(x) <= HALF_LENGTH - R && Math.abs(y) <= HALF_WIDTH - R;
    }
}
