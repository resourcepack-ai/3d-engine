package ai.resourcepack.engine3d;

import ai.resourcepack.engine3d.effects.EffectLinker;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Links the flamethrower's flame (and any .glsl beside it) into 26.3's text
 * shader and writes the result to build/effects-out, where
 * tools/compile-effects.mjs compiles it with glslang.
 */
class EffectLinkerTest {

    @Test
    void links() throws Exception {
        Path vsh = Path.of("src/main/resources/shader-src/text.vsh"), fsh = Path.of("src/main/resources/shader-src/text.fsh");
        List<EffectLinker.Effect> effects = new ArrayList<>();
        try (var s = Files.list(Path.of("../flamethrower/src/main/resources/effects"))) {
            for (Path p : s.filter(f -> f.toString().endsWith(".glsl")).sorted().toList()) {
                String glsl = Files.readString(p);
                assertEquals(List.of(), EffectLinker.problems(glsl), p.toString());
                effects.add(new EffectLinker.Effect(p.getFileName().toString(), glsl));
            }
        }
        String v = EffectLinker.vertex(Files.readString(vsh));
        String f = EffectLinker.fragment(Files.readString(fsh), effects);
        assertTrue(v.contains("rpwPos = Position;"));
        assertTrue(f.contains("void rpe_vanilla_main()"));
        assertTrue(f.contains("rpw0_object("), "the effect's entry is namespaced");
        Path out = Path.of("build/effects-out");
        Files.createDirectories(out);
        Files.writeString(out.resolve("text.vsh"), v);
        Files.writeString(out.resolve("text.fsh"), f);
    }
}
