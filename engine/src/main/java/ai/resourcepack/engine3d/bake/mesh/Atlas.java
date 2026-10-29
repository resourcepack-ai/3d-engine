package ai.resourcepack.engine3d.bake.mesh;

import ai.resourcepack.engine3d.bake.ObjCubed;
import ai.resourcepack.engine3d.bake.rig.Rig;

import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * One texture for the whole model, {@code width} wide: each material's
 * base-colour image (times its colour factor) resampled into a square cell,
 * and untextured materials as flat swatches in one shared cell. obj³ draws a
 * model from a single texture, and a small one: it sits in the client's item
 * atlas beside every other model's.
 */
public final class Atlas {

    public final ObjCubed.Image image;
    private final double[][] uv;

    private Atlas(ObjCubed.Image image, double[][] uv) {
        this.image = image;
        this.uv = uv;
    }

    /** Vertex {@code v}'s UV in the atlas, v up (OBJ convention, as obj³ wants). */
    public double[] uvOf(int v) {
        return uv[v];
    }

    public static Atlas build(Rig rig, List<int[]> tris, int width) throws IOException {
        List<Rig.Material> mats = rig.materials;
        Set<Integer> used = new LinkedHashSet<>();
        for (int[] t : tris) used.add(t[3]);
        // Materials sharing an image share a cell.
        List<byte[]> images = new ArrayList<>();
        Map<Integer, Integer> cellOfMaterial = new HashMap<>();
        List<Integer> swatches = new ArrayList<>();
        for (int mi : used) {
            byte[] img = mats.get(mi).image();
            if (img == null) {
                swatches.add(mi);
                continue;
            }
            int found = -1;
            for (int k = 0; k < images.size(); k++) if (images.get(k) == img) found = k;
            if (found < 0) {
                found = images.size();
                images.add(img);
            }
            cellOfMaterial.put(mi, found);
        }
        int cells = images.size() + (swatches.isEmpty() ? 0 : 1);
        int per = (int) Math.ceil(Math.sqrt(cells));
        int cell = width / per;
        int rows = (cells + per - 1) / per;
        int height = Math.max(1, rows * cell);
        byte[] data = new byte[width * height * 4];
        java.util.Arrays.fill(data, (byte) 255);

        for (int k = 0; k < images.size(); k++) {
            BufferedImage src = ImageIO.read(new MemoryCacheImageInputStream(new ByteArrayInputStream(images.get(k))));
            if (src == null) throw new IOException("A texture isn't a PNG or JPEG");
            int[] px = resample(src, cell, cell);
            double[] f = {1, 1, 1};
            for (var e : cellOfMaterial.entrySet())
                if (e.getValue() == k) {
                    double[] fac = mats.get(e.getKey()).factor();
                    for (int c = 0; c < 3; c++) f[c] = fac[c] >= 0.999 ? 1 : toSrgb(fac[c]) / 255.0;
                    break;
                }
            int ox = (k % per) * cell, oy = (k / per) * cell;
            for (int y = 0; y < cell; y++)
                for (int x = 0; x < cell; x++) {
                    int rgb = px[y * cell + x];
                    int d = ((oy + y) * width + ox + x) * 4;
                    data[d] = (byte) Math.round(((rgb >> 16) & 255) * f[0]);
                    data[d + 1] = (byte) Math.round(((rgb >> 8) & 255) * f[1]);
                    data[d + 2] = (byte) Math.round((rgb & 255) * f[2]);
                    data[d + 3] = (byte) 255;
                }
        }
        Map<Integer, double[]> swatchAt = new HashMap<>();
        if (!swatches.isEmpty()) {
            int k = images.size();
            int ox = (k % per) * cell, oy = (k / per) * cell;
            int grid = (int) Math.ceil(Math.sqrt(swatches.size()));
            int sub = Math.max(1, cell / grid);
            for (int s = 0; s < swatches.size(); s++) {
                double[] fac = mats.get(swatches.get(s)).factor();
                int sx = ox + (s % grid) * sub, sy = oy + (s / grid) * sub;
                for (int y = 0; y < sub; y++)
                    for (int x = 0; x < sub; x++) {
                        int d = ((sy + y) * width + sx + x) * 4;
                        for (int c = 0; c < 3; c++) data[d + c] = (byte) toSrgb(fac[c]);
                        data[d + 3] = (byte) 255;
                    }
                swatchAt.put(swatches.get(s), new double[]{sx + sub / 2.0, sy + sub / 2.0});
            }
        }

        // A vertex belongs to one primitive, so to one material.
        int[] matOf = new int[rig.verts.size()];
        for (int[] t : tris) for (int k = 0; k < 3; k++) matOf[t[k]] = t[3];
        double inset = 0.5;
        double[][] uv = new double[rig.verts.size()][];
        for (int v = 0; v < uv.length; v++) {
            int mi = matOf[v];
            double px, py;
            Integer c = cellOfMaterial.get(mi);
            if (c == null) {
                double[] at = swatchAt.getOrDefault(mi, new double[]{0.5, 0.5});
                px = at[0];
                py = at[1];
            } else {
                int ox = (c % per) * cell, oy = (c / per) * cell;
                double[] src = rig.verts.get(v).uv;
                double cu = Math.min(1, Math.max(0, src[0])), cv = Math.min(1, Math.max(0, src[1]));
                px = ox + inset + cu * (cell - 2 * inset);
                py = oy + inset + cv * (cell - 2 * inset);
            }
            uv[v] = new double[]{px / width, 1 - py / height};
        }
        return new Atlas(new ObjCubed.Image(width, height, data), uv);
    }

    /** Area-average resample to w x h, as packed RGB ints. */
    private static int[] resample(BufferedImage img, int w, int h) {
        int iw = img.getWidth(), ih = img.getHeight();
        int[] src = img.getRGB(0, 0, iw, ih, null, 0, iw);
        int[] out = new int[w * h];
        for (int y = 0; y < h; y++) {
            int y0 = (int) Math.floor((double) y * ih / h), y1 = Math.max(y0 + 1, (int) Math.floor((double) (y + 1) * ih / h));
            for (int x = 0; x < w; x++) {
                int x0 = (int) Math.floor((double) x * iw / w), x1 = Math.max(x0 + 1, (int) Math.floor((double) (x + 1) * iw / w));
                long r = 0, g = 0, b = 0;
                int n = 0;
                for (int sy = y0; sy < y1 && sy < ih; sy++)
                    for (int sx = x0; sx < x1 && sx < iw; sx++) {
                        int p = src[sy * iw + sx];
                        r += (p >> 16) & 255;
                        g += (p >> 8) & 255;
                        b += p & 255;
                        n++;
                    }
                if (n == 0) n = 1;
                out[y * w + x] = (int) (Math.round((double) r / n) << 16 | Math.round((double) g / n) << 8 | Math.round((double) b / n));
            }
        }
        return out;
    }

    private static int toSrgb(double c) {
        double v = c <= 0.0031308 ? 12.92 * c : 1.055 * Math.pow(c, 1 / 2.4) - 0.055;
        return (int) Math.round(255 * Math.min(1, Math.max(0, v)));
    }
}
