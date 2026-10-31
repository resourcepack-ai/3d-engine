package ai.resourcepack.pool3d;

import ai.resourcepack.pool3d.physics.Ball;
import ai.resourcepack.pool3d.physics.Rack;
import ai.resourcepack.pool3d.physics.Simulation;
import ai.resourcepack.pool3d.physics.TableGeometry;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static ai.resourcepack.pool3d.physics.TableGeometry.R;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PhysicsTest {

    private static Simulation table(Ball... balls) {
        Simulation s = new Simulation(TableGeometry.NINE_FOOT);
        s.balls.addAll(List.of(balls));
        return s;
    }

    private static int run(Simulation s, int maxTicks) {
        int t = 0;
        do {
            s.tick();
            t++;
        } while (s.moving() && t < maxTicks);
        return t;
    }

    @Test
    void aStunShotStopsTheCueBallDead() {
        Ball cue = new Ball(0, -0.3, 0), one = new Ball(1, 0, 0);
        Simulation s = table(cue, one);
        // Enough draw to arrive with no spin at all.
        Simulation.strike(cue, 0, 3.0, 0, -0.05);
        // Before the object ball can come back off the far rail.
        for (int t = 0; t < 12; t++) s.tick();
        assertTrue(Math.abs(cue.x - (-2 * R)) < 0.03 && cue.speed() < 0.05, "cue ball stayed put, at " + cue.x + " going " + cue.speed());
        assertTrue(one.x > 0.8, "object ball went on, to " + one.x);
    }

    @Test
    void followRunsOnAndDrawComesBack() {
        Ball cue = new Ball(0, -0.5, 0), one = new Ball(1, 0, 0);
        Simulation.strike(cue, 0, 1.5, 0, 0.4);
        run(table(cue, one), 400);
        assertTrue(cue.x > 0.15, "follow ran on to " + cue.x);

        cue = new Ball(0, -0.5, 0);
        one = new Ball(1, 0, 0);
        Simulation.strike(cue, 0, 2.5, 0, -0.5);
        run(table(cue, one), 400);
        assertTrue(cue.x < -0.25, "draw came back to " + cue.x);
    }

    @Test
    void aCushionReturnsMostOfTheSpeed() {
        Ball b = new Ball(0, 0.5, 0);
        Simulation s = table(b);
        Simulation.strike(b, Math.PI / 2, 1.0, 0, 0.4);
        // Rolling into the rail, measure what comes off.
        double before = 0, after = 0;
        for (int t = 0; t < 40; t++) {
            List<Simulation.Event> ev = s.tick();
            if (ev.stream().anyMatch(e -> e.kind() == Simulation.Kind.CUSHION)) {
                after = -b.vy;
                break;
            }
            before = b.vy;
        }
        assertTrue(after > 0.5 * before && after < 0.9 * before, before + " -> " + after);
    }

    @Test
    void sideSpinWidensTheAngleOffTheRail() {
        double plain = rebound(0), running = rebound(0.4), reverse = rebound(-0.4);
        assertTrue(running > plain + 0.05 && reverse < plain - 0.02, reverse + " / " + plain + " / " + running);
    }

    /** The angle off the far long rail of a ball hit 45 degrees into it, after it has settled into a roll. */
    private static double rebound(double side) {
        Ball b = new Ball(0, -0.3, 0);
        Simulation s = table(b);
        Simulation.strike(b, Math.PI / 4, 2.0, side, 0.2);
        boolean hit = false;
        for (int t = 0; t < 60; t++) {
            if (s.tick().stream().anyMatch(e -> e.kind() == Simulation.Kind.CUSHION)) hit = true;
            else if (hit) return Math.atan2(b.vx, -b.vy);
        }
        throw new AssertionError("never reached the rail");
    }

    @Test
    void aStraightShotIntoTheCornerGoesIn() {
        double x = TableGeometry.HALF_LENGTH - 0.4, y = TableGeometry.HALF_WIDTH - 0.4;
        Ball b = new Ball(0, x, y);
        Simulation s = table(b);
        Simulation.strike(b, Math.PI / 4, 1.2, 0, 0.4);
        run(s, 200);
        assertEquals(Ball.Motion.POCKETED, b.motion);
        assertEquals(3, b.pocket);
    }

    @Test
    void aBallAlongTheRailPastTheSidePocketStaysUp() {
        // Rolling along the long rail, frozen to it, across the side pocket's
        // mouth: it should rattle the points or go in, never leave the table.
        Ball b = new Ball(0, -0.6, TableGeometry.HALF_WIDTH - TableGeometry.CONTACT);
        Simulation s = table(b);
        Simulation.strike(b, 0, 1.5, 0, 0.4);
        run(s, 400);
        assertTrue(!b.onTable() || (Math.abs(b.y) <= TableGeometry.HALF_WIDTH && Math.abs(b.x) <= TableGeometry.HALF_LENGTH));
    }

    @Test
    void breaks() throws Exception {
        Random random = new Random(7);
        int down = 0, runs = 300, longest = 0;
        for (int i = 0; i < runs; i++) {
            Simulation s = new Simulation(TableGeometry.NINE_FOOT);
            s.balls.addAll(Rack.eightBall(random));
            Ball cue = s.ball(0);
            cue.place(TableGeometry.HEAD_STRING - 0.1, (random.nextDouble() - 0.5) * 0.4);
            double aim = Math.atan2(0 - cue.y, TableGeometry.FOOT_SPOT - cue.x) + (random.nextDouble() - 0.5) * 0.01;
            Simulation.strike(cue, aim, 9.5, 0, 0.0);
            int t = run(s, 2400);
            longest = Math.max(longest, t);
            assertTrue(t < 2400, "the break settled");
            for (Ball b : s.balls) {
                if (!b.onTable()) {
                    if (b.number != 0) down++;
                    continue;
                }
                assertTrue(Math.abs(b.x) < TableGeometry.HALF_LENGTH && Math.abs(b.y) < TableGeometry.HALF_WIDTH,
                        "ball " + b.number + " on the table at " + b.x + ", " + b.y);
                for (Ball o : s.balls)
                    if (o != b && o.onTable() && o.number > b.number)
                        assertTrue(Math.hypot(o.x - b.x, o.y - b.y) > 2 * R - 1e-4, "balls apart");
            }
        }
        double perBreak = down / (double) runs;
        System.out.printf("breaks: %.2f balls down a break, longest %.1f s%n", perBreak, longest / 20.0);
        assertTrue(perBreak > 0.6 && perBreak < 4, "a sensible number go down: " + perBreak);
    }

    /** A top-down picture of one break, every ball's path, for looking at. */
    @Test
    void drawsABreak() throws Exception {
        Simulation s = new Simulation(TableGeometry.NINE_FOOT);
        s.balls.addAll(Rack.eightBall(new Random(3)));
        Simulation.strike(s.ball(0), 0, 8.5, 0, 0);
        int scale = 400, pad = 60;
        int w = (int) (TableGeometry.HALF_LENGTH * 2 * scale) + pad * 2, h = (int) (TableGeometry.HALF_WIDTH * 2 * scale) + pad * 2;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(new Color(20, 90, 50));
        g.fillRect(0, 0, w, h);
        g.setColor(Color.WHITE);
        g.setStroke(new BasicStroke(2));
        for (TableGeometry.Segment c : s.table.cushions)
            g.draw(new Line2D.Double(px(c.ax(), scale, pad, w), py(c.ay(), scale, pad, h), px(c.bx(), scale, pad, w), py(c.by(), scale, pad, h)));
        for (TableGeometry.Pocket p : s.table.pockets) {
            double r = p.holeRadius() * scale;
            g.draw(new Ellipse2D.Double(px(p.holeX(), scale, pad, w) - r, py(p.holeY(), scale, pad, h) - r, 2 * r, 2 * r));
        }
        List<double[]>[] paths = new List[16];
        for (int i = 0; i < 16; i++) paths[i] = new ArrayList<>();
        for (int t = 0; t < 2400 && (t == 0 || s.moving()); t++) {
            for (Ball b : s.balls) if (b.onTable()) paths[b.number].add(new double[]{b.x, b.y});
            s.tick();
        }
        g.setStroke(new BasicStroke(1.5f));
        for (Ball b : s.balls) {
            Color c = Color.getHSBColor(b.number / 16f, 0.8f, 1f);
            if (b.number == 0) c = Color.WHITE;
            g.setColor(c);
            List<double[]> p = paths[b.number];
            for (int i = 1; i < p.size(); i++)
                g.draw(new Line2D.Double(px(p.get(i - 1)[0], scale, pad, w), py(p.get(i - 1)[1], scale, pad, h), px(p.get(i)[0], scale, pad, w), py(p.get(i)[1], scale, pad, h)));
            if (b.onTable()) {
                double r = R * scale;
                g.fill(new Ellipse2D.Double(px(b.x, scale, pad, w) - r, py(b.y, scale, pad, h) - r, 2 * r, 2 * r));
                g.setColor(Color.BLACK);
                g.drawString(String.valueOf(b.number), (float) px(b.x, scale, pad, w) - 4, (float) py(b.y, scale, pad, h) + 4);
            }
        }
        new File("build/previews").mkdirs();
        ImageIO.write(img, "png", new File("build/previews/break.png"));
    }

    private static double px(double x, int scale, int pad, int w) {
        return w / 2.0 + x * scale;
    }

    private static double py(double y, int scale, int pad, int h) {
        return h / 2.0 - y * scale;
    }
}
