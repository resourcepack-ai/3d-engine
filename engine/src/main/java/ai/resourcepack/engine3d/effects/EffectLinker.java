package ai.resourcepack.engine3d.effects;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Links shader effects into the game's text shader.
 *
 * An effect is a GLSL function from a ray to a colour,
 * {@code vec4 object(vec3 ro, vec3 rd, float t0, float t1, float time, vec3 light, inout float depth)}:
 * a fireball, a puff of flame, a black hole. It is drawn in the world through
 * a billboarded text display showing one glyph in a signature colour; the text
 * shader recognises the colour and raymarches the effect inside the glyph's
 * quad instead of drawing the glyph. The quad's texels store their own
 * coordinates, so the shader finds the effect's centre and size from the quad
 * itself: the display's position is the effect's centre and its scale is the
 * effect's diameter in blocks.
 *
 * The same contract, and the same marker, as the 3D shader objects
 * resourcepack.ai's Studio writes (its {@code lib/shaders/world.ts}); this is
 * that linker ported, pointed at 26.3's text shader (explicit varying locations,
 * and the order-independent transparency passes, which the effect's colour goes
 * through like any other text's). An effect may also read two things Studio's
 * do not have: {@code rpw_param}, a number 0..1 set per display through its
 * text opacity (a flame's age), and {@code rpw_world}, the effect's centre in
 * world blocks, to vary itself from one copy to the next.
 */
public final class EffectLinker {

    /** The red and blue of the mark; green is the effect's index. */
    public static final int SIGNATURE_RED = 253, SIGNATURE_BLUE = 250;
    /** The marker glyph's texture is this many texels a side, each storing its own coordinate. */
    public static final int GLYPH_TEXELS = 16;
    /** Font pixels: a text display draws 40 to a block, so the quad is two blocks and the effect one across at scale 1. */
    public static final int GLYPH_HEIGHT = 80;
    /** Where the glyph hangs from its baseline so the effect's centre is the display's position (measured by Studio). */
    public static final int GLYPH_ASCENT = 38;
    /** Text opacity below this is not drawn at all; 27..255 carry rpw_param 0..1. */
    public static final int PARAM_FLOOR = 27;

    private EffectLinker() {
    }

    public record Effect(String name, String glsl) {
    }

    /** What an effect may not do, checked before it goes anywhere near a GPU. */
    public static List<String> problems(String glsl) {
        String text = stripComments(glsl);
        List<String> out = new ArrayList<>();
        if (Pattern.compile("\\bwhile\\b|\\bdo\\s*\\{").matcher(text).find()) out.add("no while/do loops: use a for with a constant bound");
        if (Pattern.compile("#\\s*\\w+").matcher(text).find()) out.add("no preprocessor directives: use const values");
        if (Pattern.compile("\\buniform\\b|\\bsampler\\w*\\b|\\btexture\\w*\\s*\\(").matcher(text).find()) out.add("no uniforms, samplers or textures");
        if (Pattern.compile("\\bgl_\\w+").matcher(text).find()) out.add("no gl_ built-ins");
        if (Pattern.compile("\\bmain\\s*\\(").matcher(text).find()) out.add("write object(), not main()");
        if (Pattern.compile("\\bdiscard\\b").matcher(text).find()) out.add("don't discard: return alpha 0");
        Matcher loop = Pattern.compile("\\bfor\\s*\\(([^;]*);([^;]*);([^)]*)\\)").matcher(text);
        while (loop.find()) {
            Matcher bound = Pattern.compile("<=?\\s*(\\d+)\\s*$").matcher(loop.group(2).trim());
            if (!bound.find()) out.add("a loop needs a literal upper bound, like i < 64");
            else if (Integer.parseInt(bound.group(1)) > 160) out.add("a loop runs more than 160 times");
        }
        if (!Pattern.compile("\\bvec4\\s+object\\s*\\(").matcher(text).find()) out.add("there's no vec4 object(...) function");
        if (text.length() > 20000) out.add("the source is over 20,000 characters");
        return out;
    }

    // ---- the vertex and fragment shaders --------------------------------------------

    /** 26.3's text.vsh with the effect varyings added. */
    public static String vertex(String vanilla) {
        String decl = "layout(location = 4) out vec4 rawColor;\nlayout(location = 5) out vec3 rpwPos;\nlayout(location = 6) flat out mat4 rpwToClip;\n";
        String s = insertBefore(vanilla, "void main()", decl);
        return s.replaceFirst("void main\\(\\) \\{\\n", Matcher.quoteReplacement(
                "void main() {\n    // 3D Engine effects: the raw colour (the gate), and camera-relative world position.\n"
                        + "    rawColor = Color;\n    rpwPos = Position;\n    rpwToClip = ProjMat * ModelViewMat;\n"));
    }

    /** 26.3's text.fsh: vanilla's main kept under another name, the effects and a new main appended. */
    public static String fragment(String vanilla, List<Effect> effects) {
        String decl = "layout(location = 4) in vec4 rawColor;\nlayout(location = 5) in vec3 rpwPos;\nlayout(location = 6) flat in mat4 rpwToClip;\n#include <minecraft:globals.glsl>\n";
        String s = insertBefore(vanilla, "uniform sampler2D Sampler0;", decl);
        s = s.replaceFirst("void main\\(\\)", "void rpe_vanilla_main()");
        return s + "\n" + core(effects) + MAIN;
    }

    private static String insertBefore(String text, String marker, String insert) {
        int at = text.indexOf(marker);
        if (at < 0) throw new IllegalStateException("The text shader isn't the shape 3D Engine was written against (no " + marker + ")");
        return text.substring(0, at) + insert + text.substring(at);
    }

    private static final String MAIN = """

            void main() {
                // A shader that writes gl_FragDepth anywhere must write it on every path.
                gl_FragDepth = gl_FragCoord.z;
                if (rpw_is_ours()) {
                    vec4 color = rpw_main();
                    if (color.a < 0.004) discard;
            #ifdef OIT_ALPHA_ONLY
                    executeAlphaOnlyPhase(gl_FragDepth, color.a);
            #else
                    fragColor = calculateFinalColor(color);
            #endif
                    return;
                }
                rpe_vanilla_main();
            }
            """;

    private static final String LIBRARY = """
            // ---- shared by every effect ----
            float rpw_hash(vec3 p) {
                p = fract(p * 0.3183099 + vec3(0.1, 0.2, 0.3));
                p *= 17.0;
                return fract(p.x * p.y * p.z * (p.x + p.y + p.z));
            }
            float rpw_noise(vec3 x) {
                vec3 i = floor(x);
                vec3 f = fract(x);
                f = f * f * (3.0 - 2.0 * f);
                return mix(mix(mix(rpw_hash(i + vec3(0, 0, 0)), rpw_hash(i + vec3(1, 0, 0)), f.x),
                               mix(rpw_hash(i + vec3(0, 1, 0)), rpw_hash(i + vec3(1, 1, 0)), f.x), f.y),
                           mix(mix(rpw_hash(i + vec3(0, 0, 1)), rpw_hash(i + vec3(1, 0, 1)), f.x),
                               mix(rpw_hash(i + vec3(0, 1, 1)), rpw_hash(i + vec3(1, 1, 1)), f.x), f.y), f.z);
            }
            float rpw_fbm(vec3 p) {
                float v = 0.0;
                float a = 0.5;
                for (int i = 0; i < 5; i++) {
                    v += a * rpw_noise(p);
                    p = p * 2.02 + vec3(1.7, 9.2, 3.1);
                    a *= 0.5;
                }
                return v;
            }
            mat2 rpw_rot(float a) {
                float c = cos(a);
                float s = sin(a);
                return mat2(c, -s, s, c);
            }
            vec3 rpw_sky(vec3 d) {
                float h = clamp(d.y, -1.0, 1.0);
                vec3 top = vec3(0.35, 0.55, 0.95);
                vec3 horizon = vec3(0.78, 0.86, 0.98);
                vec3 ground = vec3(0.32, 0.30, 0.27);
                return h > 0.0 ? mix(horizon, top, pow(h, 0.6)) : mix(horizon, ground, pow(-h, 0.4));
            }
            // Set per display before the effect runs: its parameter, and its centre in world blocks.
            float rpw_param = 1.0;
            vec3 rpw_world = vec3(0.0);
            """;

    private static String core(List<Effect> effects) {
        StringBuilder modules = new StringBuilder();
        StringBuilder dispatch = new StringBuilder("vec4 rpw_dispatch(int id, vec3 ro, vec3 rd, float t0, float t1, float time, vec3 light, inout float depth) {\n");
        for (int i = 0; i < effects.size(); i++) {
            Effect e = effects.get(i);
            modules.append("\n// ---- ").append(e.name().replaceAll("[\\r\\n]", " ")).append(" (effect ").append(i).append(") ----\n");
            modules.append(namespace(e.glsl(), "rpw" + i + "_")).append('\n');
            dispatch.append("    if (id == ").append(i).append(") return rpw").append(i).append("_object(ro, rd, t0, t1, time, light, depth);\n");
        }
        dispatch.append("    return vec4(0.0);\n}\n");
        return "\n// ======== 3D Engine effects ========\n"
                + "const float RPW_GLYPH = " + GLYPH_TEXELS + ".0;\n"
                + "const float RPW_BOUND = 0.25;\n"
                + String.format(java.util.Locale.ROOT, "const float RPW_SIG_R = %.6f;\nconst float RPW_SIG_B = %.6f;\n", SIGNATURE_RED / 255.0, SIGNATURE_BLUE / 255.0)
                + LIBRARY + modules + dispatch + TRACE;
    }

    private static final String TRACE = """

            bool rpw_is_ours() {
                return abs(rawColor.r - RPW_SIG_R) < 0.5 / 255.0 && abs(rawColor.b - RPW_SIG_B) < 0.5 / 255.0;
            }

            // The effect in its sphere of radius R at C (camera-relative world space), for the
            // fragment at P: its colour, and gl_FragDepth written.
            vec4 rpw_trace(int id, vec3 P, vec3 C, float R, vec3 light) {
                vec3 rd = normalize(P);
                vec3 ro = -C / R;
                float b = dot(ro, rd);
                float c = dot(ro, ro) - 1.0;
                float h = b * b - c;
                if (h < 0.0) return vec4(0.0);
                h = sqrt(h);
                float t0 = max(-b - h, 0.0);
                float t1 = -b + h;
                if (t1 <= 0.0) return vec4(0.0);
                rpw_world = C + vec3(CameraBlockPos) - CameraOffset;
                float depth = t0;
                vec4 color = rpw_dispatch(id, ro, rd, t0, t1, GameTime * 1200.0, light, depth);
                if (color.a < 0.004) return vec4(0.0);
                // Depth of the surface the effect reported, so blocks in front hide it and it
                // hides what is behind. The quad's own fragment says which depth convention
                // (OpenGL's -1..1 or Vulkan's 0..1) this backend uses.
                vec3 hit = rd * (clamp(depth, t0, t1) * R);
                vec4 clip = rpwToClip * vec4(hit, 1.0);
                vec4 quadClip = rpwToClip * vec4(P, 1.0);
                float ndcQuad = quadClip.z / quadClip.w;
                bool halfRange = abs(gl_FragCoord.z - (ndcQuad * 0.5 + 0.5)) <= abs(gl_FragCoord.z - ndcQuad);
                float ndc = clip.z / clip.w;
                gl_FragDepth = clamp(halfRange ? ndc * 0.5 + 0.5 : ndc, 0.0, 1.0);
                return vec4(color.rgb, clamp(color.a, 0.0, 1.0));
            }

            vec4 rpw_main() {
                int id = int(rawColor.g * 255.0 + 0.5);
                rpw_param = clamp((rawColor.a * 255.0 - %FLOOR%.0) / (255.0 - %FLOOR%.0), 0.0, 1.0);
                // Where on the quad this fragment is: coarse from the texel's stored coordinate,
                // fine from the atlas coordinate's fraction.
                vec2 atlas = vec2(textureSize(Sampler0, 0));
                vec4 texel = texture(Sampler0, texCoord0);
                vec2 q = (floor(texel.rg * 255.0 + 0.5) + fract(texCoord0 * atlas)) / RPW_GLYPH;
                // The quad's edge vectors, from how position and quad coordinate change across
                // the screen; its centre and size follow.
                vec2 qx = dFdx(texCoord0) * atlas / RPW_GLYPH;
                vec2 qy = dFdy(texCoord0) * atlas / RPW_GLYPH;
                vec3 px = dFdx(rpwPos);
                vec3 py = dFdy(rpwPos);
                float det = qx.x * qy.y - qy.x * qx.y;
                if (abs(det) < 1e-12) return vec4(0.0);
                vec3 U = (px * qy.y - py * qx.y) / det;
                vec3 V = (py * qx.x - px * qy.x) / det;
                vec3 C = rpwPos - U * (q.x - 0.5) - V * (q.y - 0.5);
                // Light: vanilla multiplied the vertex colour by the lightmap; dividing the mark
                // back out leaves the light.
                vec3 light = clamp(vertexColor.rgb / max(rawColor.rgb, vec3(1.0 / 255.0)), 0.0, 1.0);
                return rpw_trace(id, rpwPos, C, length(U) * RPW_BOUND, vec3(light.r));
            }
            """.replace("%FLOOR%", String.valueOf(PARAM_FLOOR));

    // ---- namespacing ------------------------------------------------------------------

    public static String stripComments(String glsl) {
        Matcher m = Pattern.compile("/\\*[\\s\\S]*?\\*/").matcher(glsl);
        StringBuilder sb = new StringBuilder();
        while (m.find()) m.appendReplacement(sb, Matcher.quoteReplacement(m.group().replaceAll("[^\\n]", " ")));
        m.appendTail(sb);
        return sb.toString().replaceAll("//[^\\n]*", "");
    }

    /** Every name the source declares at the top level: functions, constants and structs. */
    static Set<String> topLevelNames(String glsl) {
        Set<String> names = new LinkedHashSet<>();
        String text = stripComments(glsl);
        int depth = 0, start = 0;
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '{' || ch == '(') {
                if (depth == 0 && ch == '{') collect(text.substring(start, i), names);
                if (depth == 0 && ch == '(') {
                    String head = text.substring(start, i).trim();
                    Matcher fn = Pattern.compile("(\\w+)\\s*$").matcher(head);
                    if (fn.find() && head.matches("(?:(?:const|highp|mediump|lowp)\\s+)*\\w+\\s+\\w+")) names.add(fn.group(1));
                }
                depth++;
            } else if (ch == '}' || ch == ')') {
                depth--;
                if (depth == 0 && ch == '}') start = i + 1;
            } else if (ch == ';' && depth == 0) {
                collect(text.substring(start, i), names);
                start = i + 1;
            }
        }
        return names;
    }

    private static void collect(String statement, Set<String> names) {
        String s = statement.trim();
        Matcher struct = Pattern.compile("^struct\\s+(\\w+)").matcher(s);
        if (struct.find()) {
            names.add(struct.group(1));
            return;
        }
        Matcher decl = Pattern.compile("^const\\s+(?:highp\\s+|mediump\\s+|lowp\\s+)?\\w+\\s+([\\s\\S]*)$").matcher(s);
        if (decl.find()) {
            String rest = decl.group(1);
            int depth = 0, from = 0;
            List<String> parts = new ArrayList<>();
            for (int i = 0; i < rest.length(); i++) {
                char c = rest.charAt(i);
                if (c == '(') depth++;
                else if (c == ')') depth--;
                else if (c == ',' && depth == 0) {
                    parts.add(rest.substring(from, i));
                    from = i + 1;
                }
            }
            parts.add(rest.substring(from));
            for (String part : parts) {
                Matcher n = Pattern.compile("^\\s*(\\w+)").matcher(part);
                if (n.find()) names.add(n.group(1));
            }
        }
    }

    /** The source with every top-level name it declares made unique to it. */
    static String namespace(String glsl, String prefix) {
        Set<String> names = topLevelNames(glsl);
        String text = stripComments(glsl);
        if (names.isEmpty()) return text;
        StringBuilder alt = new StringBuilder();
        for (String n : names) alt.append(alt.length() == 0 ? "" : "|").append(Pattern.quote(n));
        return Pattern.compile("\\b(" + alt + ")\\b").matcher(text).replaceAll(r -> Matcher.quoteReplacement(prefix + r.group(1)));
    }

    /** The marker glyph: each texel stores its own coordinate (red across, green down), nearly transparent. */
    public static byte[] markerTexture() {
        byte[] rgba = new byte[GLYPH_TEXELS * GLYPH_TEXELS * 4];
        for (int y = 0; y < GLYPH_TEXELS; y++)
            for (int x = 0; x < GLYPH_TEXELS; x++) {
                int i = (y * GLYPH_TEXELS + x) * 4;
                rgba[i] = (byte) x;
                rgba[i + 1] = (byte) y;
                rgba[i + 2] = 0;
                rgba[i + 3] = 1;
            }
        return rgba;
    }
}
