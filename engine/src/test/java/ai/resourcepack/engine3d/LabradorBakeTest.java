package ai.resourcepack.engine3d;

import ai.resourcepack.engine3d.bake.Baker;
import ai.resourcepack.engine3d.bake.Manifest;
import ai.resourcepack.engine3d.bake.ObjCubed;
import ai.resourcepack.engine3d.bake.Spec;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Bakes the Labrador the pets plugin ships, end to end, and renders previews of what the encoded
 * textures decode to, into build/previews.
 */
class LabradorBakeTest {

    @Test
    void bakes() throws Exception {
        Path dir = Path.of("../pets/src/main/resources/models");
        Assumptions.assumeTrue(Files.exists(dir.resolve("labrador.glb")), "no labrador");
        Spec spec = Spec.parse(Files.readString(Path.of("../pets/src/main/resources/models/labrador.yml")));

        long t0 = System.nanoTime();
        Baker.Baked baked = Baker.bake(dir.resolve("labrador.glb"), spec, "labrador", System.out::println);
        System.out.printf("baked in %.1fs%n", (System.nanoTime() - t0) / 1e9);

        Manifest m = baked.manifest();
        assertEquals(7, m.states.size());
        assertTrue(m.height > 0.8 && m.height < 0.9, "height " + m.height);
        assertEquals("walk", m.nextHop("sit", "run").equals("idle") ? "walk" : "x", "sit -> run goes through idle");
        assertTrue(m.models.get("idle").points.containsKey("mouth"));

        Path out = Path.of("build/previews");
        Files.createDirectories(out);
        for (String id : new String[]{"idle", "walk", "run", "sit", "idle_to_sit"}) {
            ObjCubed.Result r = baked.models().get(id);
            Files.write(out.resolve(id + ".png"), r.png());
            Decoded d = Decoded.read(r.png());
            List<BufferedImage> tiles = new ArrayList<>();
            int step = Math.max(1, d.frames / 4);
            for (int f = 0; f < d.frames && tiles.size() < 4; f += step) tiles.add(d.render(f, 125));
            ImageIO.write(Preview.sheet(tiles, 4), "png", out.resolve("preview-" + id + ".png").toFile());
        }
        System.out.println("previews in " + out.toAbsolutePath());
    }

    /** Reads an obj³ texture back the way the shader does, for previews. */
    record Decoded(int frames, int faces, double[][][] pos, int[][][] corners, double[][] uv, int texTop, int th, int tw, BufferedImage img) {
        static Decoded read(byte[] png) throws Exception {
            BufferedImage img = ImageIO.read(new ByteArrayInputStream(png));
            int tw = img.getWidth();
            int[] h1 = px(img, 1, 0), h2 = px(img, 2, 0), h3 = px(img, 3, 0), h5 = px(img, 5, 0), h7 = px(img, 7, 0);
            int th = h1[2] * 256 + h7[0];
            int nverts = h2[0] * 16777216 + h2[1] * 65536 + h2[2] * 256 + h7[1];
            int frames = Math.max(1, h3[0] * 65536 + h3[1] * 256 + h3[2]);
            int vpH = h5[0] * 256 + h5[1], vtH = h5[2] * 256 + h7[2];
            int faces = nverts / 4, uvH = (faces + tw - 1) / tw;
            int texTop = 2 + uvH, posTop = texTop + th, uvTop = posTop + vpH, vTop = uvTop + vtH;
            int npos = vpH * tw / 3, nuv = vtH * tw / 2;
            double[] p = new double[npos * 3];
            for (int i = 0; i < npos * 3; i++) {
                int[] c = px(img, i % tw, posTop + i / tw);
                p[i] = (c[0] * 65536 + c[1] * 256 + c[2]) / 65536.0 - 128;
            }
            double[][] uv = new double[nuv][2];
            for (int i = 0; i < nuv * 2; i++) {
                int[] c = px(img, i % tw, uvTop + i / tw);
                uv[i / 2][i % 2] = (c[0] * 65536 + c[1] * 256 + c[2]) / 65535.0;
            }
            double[][][] pos = new double[frames][nverts][];
            int[][][] corners = new int[frames][nverts][];
            for (int f = 0; f < frames; f++)
                for (int v = 0; v < nverts; v++) {
                    int q = (f * nverts + v) * 2;
                    int[] a = px(img, q % tw, vTop + q / tw), b = px(img, (q + 1) % tw, vTop + (q + 1) / tw);
                    int pi = a[0] * 65536 + a[1] * 256 + a[2], ui = b[0] * 65536 + b[1] * 256 + b[2];
                    pos[f][v] = new double[]{p[pi * 3], p[pi * 3 + 1] + 0.5, p[pi * 3 + 2]};
                    corners[f][v] = new int[]{pi, ui};
                }
            return new Decoded(frames, faces, pos, corners, uv, texTop, th, tw, img);
        }

        BufferedImage render(int frame, double yaw) {
            List<double[]> positions = new ArrayList<>();
            List<int[]> quads = new ArrayList<>();
            List<int[]> colours = new ArrayList<>();
            for (int fc = 0; fc < faces; fc++) {
                int[] q = new int[4];
                int r = 0, g = 0, b = 0;
                for (int k = 0; k < 4; k++) {
                    q[k] = positions.size();
                    positions.add(pos[frame][fc * 4 + k]);
                    double[] t = uv[corners[frame][fc * 4 + k][1]];
                    int tx = Math.min(tw - 1, (int) (t[0] * tw));
                    int ty = texTop + Math.max(0, Math.min(th - 1, (int) (t[1] * th)));
                    int[] c = px(img, tx, ty);
                    r += c[0];
                    g += c[1];
                    b += c[2];
                }
                quads.add(q);
                colours.add(new int[]{r / 4, g / 4, b / 4});
            }
            return Preview.render(positions, quads, colours, yaw, 320, 240);
        }

        static int[] px(BufferedImage img, int x, int y) {
            int[] out = new int[4];
            img.getRaster().getPixel(x, y, out);
            return out;
        }
    }
}
