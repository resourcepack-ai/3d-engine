package ai.resourcepack.engine3d.runtime;

import ai.resourcepack.engine3d.api.EffectInstance;
import ai.resourcepack.engine3d.api.Particle;
import ai.resourcepack.engine3d.bake.ObjCubed;
import ai.resourcepack.engine3d.effects.EffectLinker;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.bukkit.util.Transformation;
import org.bukkit.util.Vector;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * Shader effects: the {@code effects/} folder linked into the pack's text
 * shader, the displays that draw them, and the particles the engine flies.
 */
final class Effects {

    static final String FONT = "3dengine:effects";
    static final String MARKER = "\uE000";

    private final Path folder;
    private final Logger log;
    private Map<String, Integer> index = Map.of();
    private final List<ParticleImpl> particles = new ArrayList<>();
    /**
     * Displays of particles that have died, kept invisible (scale 0) to be
     * reused: a new entity takes a tick or two to reach the client, so a
     * spawned particle is never seen where it was born, while a reused one
     * jumps there and is drawn the same tick. A flamethrower's fire would
     * otherwise start a block or two from the nozzle.
     */
    private final Map<String, java.util.ArrayDeque<EffectImpl>> idle = new java.util.HashMap<>();
    private final Map<EffectImpl, Long> idleSince = new java.util.HashMap<>();
    private long ticks;

    Effects(Path folder, Logger log) {
        this.folder = folder;
        this.log = log;
    }

    record Linked(Map<String, Integer> index, Map<String, byte[]> files, List<String> errors) {
    }

    /** Reads and links the folder. Off the main thread. */
    Linked link(ClassLoader jar) throws IOException {
        Files.createDirectories(folder);
        List<EffectLinker.Effect> effects = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Map<String, Integer> names = new LinkedHashMap<>();
        try (Stream<Path> s = Files.walk(folder)) {
            for (Path p : s.filter(f -> f.toString().toLowerCase(Locale.ROOT).endsWith(".glsl")).sorted().toList()) {
                String rel = folder.relativize(p).toString().replace('\\', '/');
                String name = rel.substring(0, rel.length() - 5).toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._-]+", "_");
                String glsl = Files.readString(p);
                List<String> problems = EffectLinker.problems(glsl);
                if (!problems.isEmpty()) {
                    errors.add("effect " + name + ": " + String.join("; ", problems));
                    continue;
                }
                if (effects.size() >= 255) {
                    errors.add("effect " + name + ": over 255 effects");
                    continue;
                }
                names.put(name, effects.size());
                effects.add(new EffectLinker.Effect(name, glsl));
            }
        }
        Map<String, byte[]> files = new LinkedHashMap<>();
        if (!effects.isEmpty()) {
            String vsh = resource(jar, "shader-src/text.vsh"), fsh = resource(jar, "shader-src/text.fsh");
            files.put("assets/minecraft/shaders/core/text.vsh", EffectLinker.vertex(vsh).getBytes(StandardCharsets.UTF_8));
            files.put("assets/minecraft/shaders/core/text.fsh", EffectLinker.fragment(fsh, effects).getBytes(StandardCharsets.UTF_8));
            files.put("assets/3dengine/textures/font/effect_marker.png",
                    ObjCubed.png(EffectLinker.GLYPH_TEXELS, EffectLinker.GLYPH_TEXELS, EffectLinker.markerTexture()));
            String font = "{\"providers\":[{\"type\":\"bitmap\",\"file\":\"3dengine:font/effect_marker.png\",\"height\":" + EffectLinker.GLYPH_HEIGHT
                    + ",\"ascent\":" + EffectLinker.GLYPH_ASCENT + ",\"chars\":[\"\\ue000\"]}]}";
            files.put("assets/3dengine/font/effects.json", font.getBytes(StandardCharsets.UTF_8));
        }
        return new Linked(names, files, errors);
    }

    private static String resource(ClassLoader jar, String path) throws IOException {
        try (InputStream in = jar.getResourceAsStream(path)) {
            if (in == null) throw new IOException("Missing " + path + " in the jar");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    void use(Map<String, Integer> fresh) {
        index = Map.copyOf(fresh);
    }

    java.util.Set<String> names() {
        return index.keySet();
    }

    // ---- displays ----------------------------------------------------------------

    EffectInstance spawn(String name, Location at, double size) {
        Integer id = index.get(name);
        if (id == null) throw new IllegalArgumentException("No effect called " + name);
        TextDisplay d = at.getWorld().spawn(at, TextDisplay.class, t -> {
            t.text(Component.text(MARKER).font(Key.key(FONT)).color(TextColor.color(EffectLinker.SIGNATURE_RED, id, EffectLinker.SIGNATURE_BLUE)));
            t.setBillboard(Display.Billboard.CENTER);
            t.setBackgroundColor(Color.fromARGB(0, 0, 0, 0));
            t.setDefaultBackground(false);
            t.setShadowed(false);
            t.setSeeThrough(false);
            t.setLineWidth(1000);
            t.setViewRange(2f);
            t.setPersistent(false);
            t.setBrightness(null);
            t.addScoreboardTag("3dengine");
            t.setTransformation(scaled(size));
        });
        EffectImpl e = new EffectImpl(name, d, size);
        e.param(0);
        return e;
    }

    private static Transformation scaled(double s) {
        float f = (float) s;
        return new Transformation(new Vector3f(), new Quaternionf(), new Vector3f(f, f, f), new Quaternionf());
    }

    static final class EffectImpl implements EffectInstance {
        private final String name;
        private final TextDisplay display;
        private double size;
        private int opacity = -1;

        EffectImpl(String name, TextDisplay display, double size) {
            this.name = name;
            this.display = display;
            this.size = size;
        }

        @Override
        public String effect() {
            return name;
        }

        @Override
        public TextDisplay display() {
            return display;
        }

        @Override
        public Location location() {
            return display.getLocation();
        }

        @Override
        public void teleport(Location at, int ticks) {
            display.setTeleportDuration(Math.max(0, Math.min(59, ticks)));
            display.teleport(at);
        }

        @Override
        public double size() {
            return size;
        }

        @Override
        public void size(double blocks, int ticks) {
            if (Math.abs(blocks - size) < 1e-4 && ticks != 0) return;
            size = blocks;
            display.setInterpolationDelay(0);
            display.setInterpolationDuration(Math.max(0, ticks));
            display.setTransformation(scaled(blocks));
        }

        @Override
        public void param(double value) {
            int o = EffectLinker.PARAM_FLOOR + (int) Math.round(Math.max(0, Math.min(1, value)) * (255 - EffectLinker.PARAM_FLOOR));
            if (o == opacity) return;
            opacity = o;
            display.setTextOpacity((byte) o);
        }

        @Override
        public boolean valid() {
            return display.isValid();
        }

        @Override
        public void remove() {
            display.remove();
        }
    }

    // ---- particles ---------------------------------------------------------------

    Particle particle(String name, Location at, Vector velocity, Particle.Options o) {
        Location start = at.clone();
        start.setYaw(0);
        start.setPitch(0);
        EffectImpl e = reuse(name, start);
        if (e != null) {
            e.teleport(start, 0);
            e.size(o.startSize, 0);
        } else {
            e = (EffectImpl) spawn(name, start, o.startSize);
        }
        e.param(o.startParam);
        ParticleImpl p = new ParticleImpl(e, start, velocity.clone(), o);
        particles.add(p);
        return p;
    }

    private EffectImpl reuse(String name, Location at) {
        java.util.ArrayDeque<EffectImpl> pool = idle.get(name);
        if (pool == null) return null;
        for (Iterator<EffectImpl> it = pool.iterator(); it.hasNext(); ) {
            EffectImpl e = it.next();
            if (!e.valid()) {
                it.remove();
                idleSince.remove(e);
                continue;
            }
            Location l = e.location();
            if (l.getWorld() != at.getWorld() || l.distanceSquared(at) > 48 * 48) continue;
            it.remove();
            idleSince.remove(e);
            return e;
        }
        return null;
    }

    private void retire(EffectImpl e) {
        if (!e.valid()) return;
        e.size(0, 0);
        idle.computeIfAbsent(e.effect(), k -> new java.util.ArrayDeque<>()).add(e);
        idleSince.put(e, ticks);
    }

    void tick() {
        ticks++;
        for (Iterator<ParticleImpl> it = particles.iterator(); it.hasNext(); ) {
            ParticleImpl p = it.next();
            if (!p.step()) {
                it.remove();
                retire(p.effect);
            }
        }
        // Idle displays nobody has reused for ten seconds go.
        if (ticks % 40 == 0) {
            for (var pool : idle.values())
                pool.removeIf(e -> {
                    Long since = idleSince.get(e);
                    if (since != null && ticks - since > 200) {
                        idleSince.remove(e);
                        e.remove();
                        return true;
                    }
                    return false;
                });
        }
    }

    void clear() {
        for (ParticleImpl p : particles) p.effect.remove();
        particles.clear();
        for (var pool : idle.values()) pool.forEach(EffectImpl::remove);
        idle.clear();
        idleSince.clear();
    }

    static final class ParticleImpl implements Particle {
        final EffectImpl effect;
        final Location at;
        Vector velocity;
        final Options o;
        int age;
        boolean dead, stuck;

        ParticleImpl(EffectImpl effect, Location at, Vector velocity, Options o) {
            this.effect = effect;
            this.at = at;
            this.velocity = velocity;
            this.o = o;
        }

        /** One tick; false when it is over. */
        boolean step() {
            if (dead || !effect.valid()) return false;
            age++;
            if (age >= o.life) return false;
            if (!stuck) {
                Vector v = velocity;
                Location next = at.clone().add(v);
                if (o.collide && hits(at, next)) {
                    stuck = true;
                    // Stop at the surface, a little short of it.
                    Location back = at.clone();
                    for (int i = 0; i < 8; i++) {
                        Location probe = back.clone().add(v.clone().multiply(0.125));
                        if (solid(probe.getBlock())) break;
                        back = probe;
                    }
                    at.setX(back.getX());
                    at.setY(back.getY());
                    at.setZ(back.getZ());
                    velocity = new Vector();
                    if (o.onHit != null) o.onHit.accept(this);
                } else {
                    at.setX(next.getX());
                    at.setY(next.getY());
                    at.setZ(next.getZ());
                }
                velocity.multiply(o.drag);
                velocity.setY(velocity.getY() + o.lift);
            } else if (o.lift != 0) {
                // Stuck against a block: fire still rises off it.
                Location up = at.clone().add(0, o.lift * 4, 0);
                if (!solid(up.getBlock())) at.setY(up.getY());
            }
            double t = progress();
            effect.size(o.startSize + (o.endSize - o.startSize) * t, 1);
            effect.param(o.startParam + (o.endParam - o.startParam) * t);
            effect.teleport(at, 1);
            if (o.onTick != null) o.onTick.accept(this);
            return !dead;
        }

        private static boolean hits(Location from, Location to) {
            double d = from.distance(to);
            int steps = Math.max(1, (int) Math.ceil(d / 0.25));
            for (int i = 1; i <= steps; i++) {
                Location p = from.clone().add(to.clone().subtract(from).toVector().multiply((double) i / steps));
                if (solid(p.getBlock())) return true;
            }
            return false;
        }

        private static boolean solid(Block b) {
            return !b.isPassable() && !b.isLiquid();
        }

        @Override
        public EffectInstance effect() {
            return effect;
        }

        @Override
        public Location location() {
            return at.clone();
        }

        @Override
        public Vector velocity() {
            return velocity.clone();
        }

        @Override
        public void velocity(Vector v) {
            velocity = v.clone();
            stuck = false;
        }

        @Override
        public int age() {
            return age;
        }

        @Override
        public double progress() {
            return Math.min(1, (double) age / Math.max(1, o.life));
        }

        @Override
        public double size() {
            return effect.size();
        }

        @Override
        public boolean alive() {
            return !dead && age < o.life && effect.valid();
        }

        @Override
        public void remove() {
            dead = true;
        }
    }
}
