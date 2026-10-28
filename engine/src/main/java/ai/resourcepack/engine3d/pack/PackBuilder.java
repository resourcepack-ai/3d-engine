package ai.resourcepack.engine3d.pack;

import ai.resourcepack.engine3d.bake.Manifest;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The resource pack: obj³'s core shaders (from this jar) and every baked game
 * model, each as its own item model definition in the {@code 3dengine}
 * namespace. An item shows a game model through its {@code item_model}
 * component; the model's clock is the first {@code custom_model_data} colour.
 *
 * <p>The models and textures add to vanilla and replace nothing, but the pack
 * does <b>replace vanilla core shaders</b>: {@code block}, {@code entity},
 * {@code item} and {@code terrain} (each {@code .vsh} and {@code .fsh}) always,
 * since that is how obj³ draws a mesh out of an item model, plus its
 * {@code objmc_*.glsl} includes in the {@code minecraft} namespace; and
 * {@code text.vsh}/{@code text.fsh} as well when {@code effects/} holds at
 * least one effect (see {@code Effects}). A core shader is one file per name,
 * so when another pack on the same client replaces any of the same ones, only
 * the pack higher in the client's list has its shader used: above this one,
 * models (or effects) stop drawing; below it, whatever that pack's shader did
 * is lost. Packs that draw meshes with core shaders (objmc and its forks)
 * replace the first four; RP Engine's shader objects and MC GUI replace
 * the text shaders.
 * Everything else (other packs' models, textures, sounds, fonts) stacks fine.
 */
public final class PackBuilder {

    public static final String NAMESPACE = "3dengine";

    private PackBuilder() {
    }

    /** A baked model on disk: its manifest and the folder holding {@code <id>.png} and {@code <id>.json}. */
    public record Entry(String name, Path dir, Manifest manifest) {
    }

    public record Pack(byte[] zip, byte[] sha1, String hex) {
    }

    /** The item model id of game model {@code id} of model {@code name}. */
    public static String itemModel(String name, String id) {
        return NAMESPACE + ":" + name + "/" + id;
    }

    public static Pack build(Path jar, List<Entry> models, java.util.Map<String, byte[]> extras, int[] format, String description) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            put(zip, "pack.mcmeta", ("{\"pack\":{\"min_format\":[" + format[0] + "," + format[1] + "],\"max_format\":[" + format[0] + "," + format[1]
                    + "],\"description\":" + quote(description) + "}}").getBytes(StandardCharsets.UTF_8));
            try (JarFile jf = new JarFile(jar.toFile())) {
                Enumeration<JarEntry> entries = jf.entries();
                while (entries.hasMoreElements()) {
                    JarEntry e = entries.nextElement();
                    if (e.isDirectory() || !e.getName().startsWith("pack/")) continue;
                    try (InputStream in = jf.getInputStream(e)) {
                        put(zip, e.getName().substring("pack/".length()), in.readAllBytes());
                    }
                }
            }
            for (var e : extras.entrySet()) put(zip, e.getKey(), e.getValue());
            for (Entry m : models) {
                for (String id : m.manifest().models.keySet()) {
                    String path = m.name() + "/" + id;
                    put(zip, "assets/" + NAMESPACE + "/textures/item/" + path + ".png", Files.readAllBytes(m.dir().resolve(id + ".png")));
                    put(zip, "assets/" + NAMESPACE + "/models/item/" + path + ".json", Files.readAllBytes(m.dir().resolve(id + ".json")));
                    String tints = "\"tints\":[{\"type\":\"minecraft:custom_model_data\",\"index\":0,\"default\":-1}]";
                    String neutral = "{\"type\":\"minecraft:model\",\"model\":\"" + NAMESPACE + ":item/" + path + "\"," + tints + "}";
                    String body = neutral;
                    if (m.manifest().item) {
                        // A held item: a model per display context, each calibrated for it.
                        StringBuilder cases = new StringBuilder();
                        for (String slot : ai.resourcepack.engine3d.bake.ObjCubed.SLOTS) {
                            Path f = m.dir().resolve(id + "@" + slot + ".json");
                            if (!Files.exists(f)) continue;
                            put(zip, "assets/" + NAMESPACE + "/models/item/" + path + "_" + slot + ".json", Files.readAllBytes(f));
                            if (cases.length() > 0) cases.append(',');
                            cases.append("{\"when\":\"").append(slot).append("\",\"model\":{\"type\":\"minecraft:model\",\"model\":\"")
                                    .append(NAMESPACE).append(":item/").append(path).append('_').append(slot).append("\",").append(tints).append("}}");
                        }
                        body = "{\"type\":\"minecraft:select\",\"property\":\"minecraft:display_context\",\"cases\":[" + cases + "],\"fallback\":" + neutral + "}";
                    }
                    String item = "{\"model\":" + body + ",\"hand_animation_on_swap\":false}";
                    put(zip, "assets/" + NAMESPACE + "/items/" + path + ".json", item.getBytes(StandardCharsets.UTF_8));
                }
            }
        }
        byte[] zip = bytes.toByteArray();
        try {
            byte[] sha1 = MessageDigest.getInstance("SHA-1").digest(zip);
            StringBuilder hex = new StringBuilder();
            for (byte b : sha1) hex.append(String.format("%02x", b));
            return new Pack(zip, sha1, hex.toString());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static void put(ZipOutputStream zip, String name, byte[] data) throws IOException {
        ZipEntry e = new ZipEntry(name);
        e.setTime(0); // the same models make the same pack, and the same hash
        zip.putNextEntry(e);
        zip.write(data);
        zip.closeEntry();
    }

    private static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
