package ai.resourcepack.engine3d;

import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.util.List;

/** A tiny flat-shaded rasteriser for looking at bakes in tests. */
final class Preview {

    private Preview() {
    }

    static BufferedImage render(List<double[]> positions, List<int[]> quads, List<int[]> colours, double yawDeg, int w, int h) {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        for (int y = 0; y < h; y++) for (int x = 0; x < w; x++) img.setRGB(x, y, 0xc8d6e6);
        double[] zb = new double[w * h];
        java.util.Arrays.fill(zb, Double.MAX_VALUE);
        double minY = Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (double[] p : positions) {
            minY = Math.min(minY, p[1]);
            maxY = Math.max(maxY, p[1]);
        }
        double height = Math.max(0.5, maxY - minY), mid = (minY + maxY) / 2;
        double ya = Math.toRadians(yawDeg), pa = Math.toRadians(15);
        double k = h * 0.8 / (height * 1.6);
        double[][] v = new double[positions.size()][];
        for (int i = 0; i < v.length; i++) {
            double[] p = positions.get(i);
            double x = p[0] * Math.cos(ya) - p[2] * Math.sin(ya);
            double z = p[0] * Math.sin(ya) + p[2] * Math.cos(ya);
            double y = p[1] - mid;
            double y2 = y * Math.cos(pa) - z * Math.sin(pa), z2 = y * Math.sin(pa) + z * Math.cos(pa);
            v[i] = new double[]{w / 2.0 + x * k, h / 2.0 - y2 * k, z2};
        }
        double[] light = {0.4, 0.8, 0.45};
        double ll = Math.sqrt(light[0] * light[0] + light[1] * light[1] + light[2] * light[2]);
        for (int f = 0; f < quads.size(); f++) {
            int[] q = quads.get(f);
            int[][] tris = {{0, 1, 2}, {2, 3, 0}};
            for (int[] t : tris) {
                double[] A = positions.get(q[t[0]]), B = positions.get(q[t[1]]), C = positions.get(q[t[2]]);
                double[] u = {B[0] - A[0], B[1] - A[1], B[2] - A[2]}, s = {C[0] - A[0], C[1] - A[1], C[2] - A[2]};
                double[] n = {u[1] * s[2] - u[2] * s[1], u[2] * s[0] - u[0] * s[2], u[0] * s[1] - u[1] * s[0]};
                double nl = Math.sqrt(n[0] * n[0] + n[1] * n[1] + n[2] * n[2]);
                if (nl < 1e-12) continue;
                double shade = 0.45 + 0.55 * Math.abs((n[0] * light[0] + n[1] * light[1] + n[2] * light[2]) / (nl * ll));
                int[] c = colours.get(f);
                int rgb = (clamp(c[0] * shade) << 16) | (clamp(c[1] * shade) << 8) | clamp(c[2] * shade);
                tri(img, zb, v[q[t[0]]], v[q[t[1]]], v[q[t[2]]], rgb);
            }
        }
        return img;
    }

    private static int clamp(double x) {
        return (int) Math.max(0, Math.min(255, Math.round(x)));
    }

    private static void tri(BufferedImage img, double[] zb, double[] a, double[] b, double[] c, int rgb) {
        int w = img.getWidth(), h = img.getHeight();
        int minx = Math.max(0, (int) Math.floor(Math.min(a[0], Math.min(b[0], c[0])))), maxx = Math.min(w - 1, (int) Math.ceil(Math.max(a[0], Math.max(b[0], c[0]))));
        int miny = Math.max(0, (int) Math.floor(Math.min(a[1], Math.min(b[1], c[1])))), maxy = Math.min(h - 1, (int) Math.ceil(Math.max(a[1], Math.max(b[1], c[1]))));
        double area = (b[0] - a[0]) * (c[1] - a[1]) - (b[1] - a[1]) * (c[0] - a[0]);
        if (Math.abs(area) < 1e-9) return;
        for (int y = miny; y <= maxy; y++)
            for (int x = minx; x <= maxx; x++) {
                double px = x + 0.5, py = y + 0.5;
                double w0 = ((b[0] - px) * (c[1] - py) - (b[1] - py) * (c[0] - px)) / area;
                double w1 = ((c[0] - px) * (a[1] - py) - (c[1] - py) * (a[0] - px)) / area;
                double w2 = 1 - w0 - w1;
                if (w0 < 0 || w1 < 0 || w2 < 0) continue;
                double z = w0 * a[2] + w1 * b[2] + w2 * c[2];
                int i = y * w + x;
                if (z >= zb[i]) continue;
                zb[i] = z;
                img.setRGB(x, y, rgb);
            }
    }

    static BufferedImage sheet(List<BufferedImage> tiles, int cols) {
        int w = tiles.get(0).getWidth(), h = tiles.get(0).getHeight(), rows = (tiles.size() + cols - 1) / cols;
        BufferedImage out = new BufferedImage(w * Math.min(cols, tiles.size()), h * rows, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = out.createGraphics();
        for (int i = 0; i < tiles.size(); i++) g.drawImage(tiles.get(i), (i % cols) * w, (i / cols) * h, null);
        g.dispose();
        return out;
    }
}
