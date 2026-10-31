package ai.resourcepack.pool3d.physics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

import static ai.resourcepack.pool3d.physics.TableGeometry.R;

/** Racks for 8-ball. */
public final class Rack {

    private Rack() {
    }

    /**
     * The fifteen balls in a triangle on the foot spot, apex towards the head:
     * the 8 in the middle of the third row, a stripe and a solid in the back
     * corners, the rest shuffled. Balls in a real rack are never perfectly
     * frozen, so each sits a hair's breadth from its neighbours, a different
     * hair each time: that is what makes no two breaks alike.
     */
    public static List<Ball> eightBall(Random random) {
        List<Integer> rest = new ArrayList<>();
        for (int i = 1; i <= 15; i++) if (i != 8) rest.add(i);
        Collections.shuffle(rest, random);
        int solidCorner = -1, stripeCorner = -1;
        for (int n : rest) {
            if (n < 8 && solidCorner < 0) solidCorner = n;
            if (n > 8 && stripeCorner < 0) stripeCorner = n;
        }
        rest.remove(Integer.valueOf(solidCorner));
        rest.remove(Integer.valueOf(stripeCorner));
        boolean solidLeft = random.nextBoolean();
        List<Ball> out = new ArrayList<>();
        int k = 0;
        double row = Math.sqrt(3) * R + 0.0002;
        for (int r = 0; r < 5; r++) {
            for (int c = 0; c <= r; c++) {
                int number;
                if (r == 2 && c == 1) number = 8;
                else if (r == 4 && c == 0) number = solidLeft ? solidCorner : stripeCorner;
                else if (r == 4 && c == 4) number = solidLeft ? stripeCorner : solidCorner;
                else number = rest.get(k++);
                double gap = 0.00005 + random.nextDouble() * 0.0002;
                double x = TableGeometry.FOOT_SPOT + r * row + random.nextDouble() * 0.0001;
                double y = (c - r / 2.0) * (2 * R + gap);
                out.add(new Ball(number, x, y));
            }
        }
        out.add(0, new Ball(0, TableGeometry.HEAD_STRING - 0.25, 0));
        return out;
    }
}
