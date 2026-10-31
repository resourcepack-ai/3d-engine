package ai.resourcepack.pool3d;

import ai.resourcepack.engine3d.api.Engine3D;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * The models, sounds and HUD font this plugin ships in its jar, copied into 3D
 * Engine's folders before it bakes; and the cue a player holds.
 */
public final class Assets {

    public static final String TABLE = "pool/table";
    public static final String CUE = "pool/cue";
    public static final String GHOST = "pool/ghost";
    public static final String GLOSS = "pool/gloss";

    public static String ball(int n) {
        return "pool/ball_" + n;
    }

    private static final NamespacedKey IS_CUE = new NamespacedKey("pool3d", "cue");

    private Assets() {
    }

    /** Copies models/ into the engine's models folder (as pool/...) and pack/ into its pack folder. */
    static void install(JavaPlugin plugin, Engine3D engine, File jar) {
        try (JarFile jf = new JarFile(jar)) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry e = entries.nextElement();
                if (e.isDirectory()) continue;
                Path to;
                if (e.getName().startsWith("models/")) to = engine.modelsFolder().resolve("pool").resolve(e.getName().substring(7));
                else if (e.getName().startsWith("pack/")) to = engine.packFolder().resolve(e.getName().substring(5));
                else continue;
                try (InputStream in = jf.getInputStream(e)) {
                    byte[] bytes = in.readAllBytes();
                    Files.createDirectories(to.getParent());
                    if (!Files.exists(to) || !Arrays.equals(Files.readAllBytes(to), bytes)) Files.write(to, bytes);
                }
            }
        } catch (IOException e) {
            plugin.getLogger().severe("Couldn't install the models: " + e.getMessage());
        }
    }

    /**
     * The cue: held, it shows nothing (the cue on the table is the one you
     * see); in the inventory, a cue. (It is still a slow use that never
     * finishes, so a right-click never does anything else with it.)
     */
    public static ItemStack cue() {
        ItemStack item = Bukkit.getItemFactory().createItemStack("minecraft:stick[minecraft:item_model=\"pool:cue\","
                + "minecraft:consumable={consume_seconds:100000,animation:\"none\",has_consume_particles:false,sound:\"minecraft:intentionally_empty\"},"
                + "minecraft:max_stack_size=1]");
        ItemMeta meta = item.getItemMeta();
        meta.setItemName(ChatColor.WHITE + "Cue");
        meta.setLore(List.of(ChatColor.GRAY + "Mouse aims  ·  hold S and move the mouse to cue",
                ChatColor.GRAY + "Hold W and move the mouse for spin  ·  Q: centre",
                ChatColor.GRAY + "A/D fine aim  ·  scroll zoom  ·  space: whole table",
                ChatColor.GRAY + "Sneak to get up  ·  F: guide line"));
        meta.getPersistentDataContainer().set(IS_CUE, PersistentDataType.BYTE, (byte) 1);
        item.setItemMeta(meta);
        return item;
    }

    public static boolean isCue(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer().has(IS_CUE);
    }
}
