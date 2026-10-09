package ai.resourcepack.engine3d.runtime;

import ai.resourcepack.engine3d.bake.Baker;
import ai.resourcepack.engine3d.bake.Manifest;
import ai.resourcepack.engine3d.bake.ObjCubed;
import ai.resourcepack.engine3d.bake.Spec;
import ai.resourcepack.engine3d.pack.PackBuilder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;
import java.util.stream.Stream;

/**
 * The models folder, baked: every model file in it (and its spec beside it),
 * baked once and cached by the hash of what went in, so a restart bakes only
 * what changed.
 */
final class Registry {

    private static final Set<String> EXTENSIONS = Set.of("glb", "gltf", "obj");

    private final Path folder;
    private final Path cache;
    private final Logger log;

    Registry(Path folder, Path cache, Logger log) {
        this.folder = folder;
        this.cache = cache;
        this.log = log;
    }

    record Loaded(Map<String, LoadedModel> models, List<PackBuilder.Entry> entries, List<String> errors) {
    }

    /** Bakes (or loads from cache) every model. Runs off the main thread. */
    Loaded load() throws IOException {
        Files.createDirectories(folder);
        unzipDownloads();
        List<Path> files;
        try (Stream<Path> s = Files.walk(folder)) {
            files = s.filter(Files::isRegularFile).filter(p -> EXTENSIONS.contains(ext(p))).sorted().toList();
        }
        // Files another model takes its animations from aren't models themselves.
        Map<Path, Spec> specs = new LinkedHashMap<>();
        Map<Path, String> specText = new LinkedHashMap<>();
        Set<Path> sources = new HashSet<>();
        for (Path f : files) {
            Path yml = f.resolveSibling(base(f) + ".yml");
            String text = Files.exists(yml) ? Files.readString(yml) : "";
            Spec spec;
            try {
                spec = Spec.parse(text);
            } catch (RuntimeException e) {
                log.warning(folder.relativize(yml) + " is not valid YAML: " + e.getMessage());
                spec = new Spec();
            }
            specs.put(f, spec);
            specText.put(f, text);
            if (spec.animationsFrom != null) sources.add(f.getParent().resolve(spec.animationsFrom).normalize());
        }

        Map<String, LoadedModel> models = new LinkedHashMap<>();
        List<PackBuilder.Entry> entries = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        for (Path f : files) {
            if (sources.contains(f.normalize())) continue;
            String name = nameOf(f);
            if (models.containsKey(name)) {
                errors.add(name + ": two files have this name; " + folder.relativize(f) + " is skipped");
                continue;
            }
            Spec spec = specs.get(f);
            try {
                String hash = hash(f, specText.get(f), spec);
                Path dir = cache.resolve(name);
                Path mf = dir.resolve("manifest.json");
                Manifest manifest = null;
                if (Files.exists(mf)) {
                    Manifest old = Manifest.fromJson(Files.readString(mf));
                    if (old != null && hash.equals(old.hash) && old.version == Manifest.BAKE_VERSION) manifest = old;
                }
                if (manifest == null) {
                    long t0 = System.nanoTime();
                    Baker.Baked baked = Baker.bake(f, spec, name, log::info);
                    manifest = baked.manifest();
                    manifest.hash = hash;
                    if (Files.exists(dir)) try (Stream<Path> old = Files.list(dir)) {
                        for (Path p : old.toList()) if (Files.isRegularFile(p)) Files.delete(p);
                    }
                    Files.createDirectories(dir);
                    manifest.item = spec.item != null;
                    for (var e : baked.models().entrySet()) {
                        ObjCubed.Result r = e.getValue();
                        String tex = PackBuilder.NAMESPACE + ":item/" + name + "/" + e.getKey();
                        Files.write(dir.resolve(e.getKey() + ".png"), r.png());
                        Files.writeString(dir.resolve(e.getKey() + ".json"), r.model(tex));
                        if (spec.item != null)
                            for (String slot : ObjCubed.SLOTS)
                                Files.writeString(dir.resolve(e.getKey() + "@" + slot + ".json"), r.slotModel(tex, slot, spec.item.get(slot)));
                    }
                    Files.writeString(mf, manifest.toJson());
                    log.info(String.format("Baked %s in %.1fs", name, (System.nanoTime() - t0) / 1e9));
                }
                models.put(name, new LoadedModel(manifest, spec));
                entries.add(new PackBuilder.Entry(name, dir, manifest));
            } catch (Exception e) {
                errors.add(name + ": " + e.getMessage());
                log.warning("Couldn't bake " + folder.relativize(f) + ": " + e);
            }
        }
        return new Loaded(models, entries, errors);
    }

    /**
     * A .zip dropped in the models folder (what Sketchfab and most model sites
     * hand out) is unpacked into a folder of the same name beside it, once: the
     * folder existing is what says it has been done, so deleting the folder
     * unpacks it again. Entries that would land outside that folder are skipped.
     */
    private void unzipDownloads() throws IOException {
        List<Path> zips;
        try (Stream<Path> s = Files.walk(folder)) {
            zips = s.filter(Files::isRegularFile).filter(p -> ext(p).equals("zip")).sorted().toList();
        }
        for (Path zip : zips) {
            Path into = zip.resolveSibling(base(zip));
            if (Files.exists(into)) continue;
            int count = 0;
            try (java.util.zip.ZipInputStream in = new java.util.zip.ZipInputStream(Files.newInputStream(zip))) {
                for (java.util.zip.ZipEntry e; (e = in.getNextEntry()) != null; ) {
                    Path to = into.resolve(e.getName()).normalize();
                    if (!to.startsWith(into)) continue;
                    if (e.isDirectory()) Files.createDirectories(to);
                    else {
                        Files.createDirectories(to.getParent());
                        Files.copy(in, to, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                        count++;
                    }
                }
            } catch (IOException e) {
                log.warning("Couldn't unpack " + folder.relativize(zip) + ": " + e.getMessage());
                continue;
            }
            log.info("Unpacked " + folder.relativize(zip) + " (" + count + " files)");
        }
    }

    /**
     * The model's name: its path in the models folder, lower case, without the
     * extension; a file called {@code scene} (every Sketchfab download's) is
     * named after its folder instead.
     */
    String nameOf(Path f) {
        String rel = folder.relativize(f).toString().replace('\\', '/');
        rel = rel.substring(0, rel.length() - ext(f).length() - 1);
        if (rel.endsWith("/scene")) rel = rel.substring(0, rel.length() - "/scene".length());
        return rel.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9/._-]+", "_");
    }

    private static String ext(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? "" : n.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    private static String base(Path p) {
        String n = p.getFileName().toString();
        int dot = n.lastIndexOf('.');
        return dot < 0 ? n : n.substring(0, dot);
    }

    /** Everything a bake depends on: the file, its spec, the file its animations come from, the baker's version. */
    private static String hash(Path f, String specText, Spec spec) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-1");
        md.update(Files.readAllBytes(f));
        md.update(specText.getBytes(StandardCharsets.UTF_8));
        if (spec.animationsFrom != null) {
            Path other = f.getParent().resolve(spec.animationsFrom).normalize();
            if (Files.exists(other)) md.update(Files.readAllBytes(other));
        }
        // An .obj's materials and textures sit beside it.
        if (f.toString().toLowerCase(Locale.ROOT).endsWith(".obj") || f.toString().toLowerCase(Locale.ROOT).endsWith(".gltf")) {
            try (Stream<Path> s = Files.list(f.getParent())) {
                for (Path p : s.sorted().toList()) {
                    String e = ext(p);
                    if (base(p).startsWith(base(f)) && (e.equals("mtl") || e.equals("bin") || e.equals("png") || e.equals("jpg") || e.equals("jpeg")))
                        md.update(Files.readAllBytes(p));
                }
            }
        }
        md.update(("bake" + Manifest.BAKE_VERSION).getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder();
        for (byte b : md.digest()) hex.append(String.format("%02x", b));
        return hex.toString();
    }
}
