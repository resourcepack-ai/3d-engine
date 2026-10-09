package ai.resourcepack.engine3d;

import ai.resourcepack.engine3d.bake.Baker;
import ai.resourcepack.engine3d.bake.Manifest;
import ai.resourcepack.engine3d.bake.Spec;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;

/**
 * Bakes any model file named by {@code -Dsample=path} (with its .yml beside it,
 * if any) and renders it into build/previews, for trying a file without a server.
 */
class SampleBakeTest {

    @Test
    void bakes() throws Exception {
        String sample = System.getProperty("sample", "");
        Assumptions.assumeTrue(!sample.isEmpty(), "no -Dsample");
        Path f = Path.of(sample);
        String base = f.getFileName().toString().replaceAll("\\.[^.]+$", "");
        Path yml = f.resolveSibling(base + ".yml");
        Spec spec = Spec.parse(Files.exists(yml) ? Files.readString(yml) : "");
        Baker.Baked baked = Baker.bake(f, spec, base, System.out::println);
        Manifest m = baked.manifest();
        System.out.printf("%s: height %.2f radius %.2f bounds %s states %s%n", base, m.height, m.radius, Arrays.toString(m.bounds), m.states.keySet());
        // Where the tallest part is: for a chair, its back. The model's front is -z.
        String still = m.states.get(m.defaultState).model;
        LabradorBakeTest.Decoded top = LabradorBakeTest.Decoded.read(baked.models().get(still).png());
        double z = 0;
        int n = 0;
        for (double[] p : top.pos()[0])
            if (p[1] > m.bounds[1] + (m.bounds[4] - m.bounds[1]) * 0.85) {
                z += p[2];
                n++;
            }
        System.out.printf("%s: top 15%% sits at z %.2f (front is -z)%n", base, z / Math.max(1, n));
        Path out = Path.of("build/previews");
        Files.createDirectories(out);
        String id = m.states.get(m.defaultState).model;
        LabradorBakeTest.Decoded d = LabradorBakeTest.Decoded.read(baked.models().get(id).png());
        ImageIO.write(Preview.sheet(List.of(d.render(0, 30), d.render(0, 120), d.render(0, 210)), 3), "png", out.resolve("sample-" + base + ".png").toFile());
    }
}
