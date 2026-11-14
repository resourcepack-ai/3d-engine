package ai.resourcepack.pool3d.game;

import ai.resourcepack.pool3d.Assets;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.configuration.serialization.ConfigurationSerializable;
import org.bukkit.util.io.BukkitObjectInputStream;
import org.bukkit.util.io.BukkitObjectOutputStream;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputFilter;
import java.io.Serializable;
import java.util.Base64;
import java.util.Set;

/**
 * Spare cues in the rest of the shooter's hotbar while they are at the shot.
 *
 * <p>The wheel zooms, and a turn of the wheel is the client choosing the
 * next hotbar slot: the server puts the cue back, but for that round trip
 * the client draws its shrunken, invisible player holding whatever was in
 * that slot, right where the camera looks. With a cue in every slot it
 * holds a cue either way. What was there is kept in the player's own data
 * until they get up, so it survives a crash or a restart.
 */
final class Hotbar {

    private static final NamespacedKey KEPT = new NamespacedKey("pool3d", "hotbar");
    private static final NamespacedKey SPARE = new NamespacedKey("pool3d", "spare-cue");

    private Hotbar() {
    }

    /** Fills the hotbar round the cue in hand with spare cues, keeping what was there. */
    static void fill(Player p) {
        if (p.getPersistentDataContainer().has(KEPT, PersistentDataType.STRING)) return;
        PlayerInventory inv = p.getInventory();
        int held = inv.getHeldItemSlot();
        if (!Assets.isCue(inv.getItem(held))) return;
        ItemStack[] kept = new ItemStack[9];
        for (int i = 0; i < 9; i++) if (i != held) kept[i] = inv.getItem(i);
        String data;
        try {
            data = write(kept);
        } catch (IOException e) {
            return;
        }
        p.getPersistentDataContainer().set(KEPT, PersistentDataType.STRING, data);
        for (int i = 0; i < 9; i++) if (i != held) inv.setItem(i, spare());
    }

    /** Takes the spare cues away and gives back what they stood in for. */
    static void restore(Player p) {
        String data = p.getPersistentDataContainer().get(KEPT, PersistentDataType.STRING);
        if (data == null) return;
        PlayerInventory inv = p.getInventory();
        for (int i = 0; i < inv.getSize(); i++) if (isSpare(inv.getItem(i))) inv.setItem(i, null);
        if (isSpare(p.getItemOnCursor())) p.setItemOnCursor(null);
        ItemStack[] kept;
        try {
            kept = read(data);
        } catch (IOException | ClassNotFoundException e) {
            return;
        }
        p.getPersistentDataContainer().remove(KEPT);
        for (int i = 0; i < Math.min(9, kept.length); i++) {
            ItemStack it = kept[i];
            if (it == null || it.getType().isAir()) continue;
            ItemStack there = inv.getItem(i);
            if (there == null || there.getType().isAir()) inv.setItem(i, it);
            else for (ItemStack left : inv.addItem(it).values()) p.getWorld().dropItem(p.getLocation(), left);
        }
    }

    private static ItemStack spare() {
        ItemStack it = Assets.cue();
        ItemMeta meta = it.getItemMeta();
        meta.getPersistentDataContainer().set(SPARE, PersistentDataType.BYTE, (byte) 1);
        it.setItemMeta(meta);
        return it;
    }

    private static boolean isSpare(ItemStack it) {
        return it != null && it.hasItemMeta() && it.getItemMeta().getPersistentDataContainer().has(SPARE, PersistentDataType.BYTE);
    }

    /** Exact classes a kept stack may contain, besides those {@link #allowed} takes by rule. */
    private static final Set<String> ALLOWED = Set.of(
            "org.bukkit.util.io.Wrapper",
            "java.lang.Object", "java.lang.String", "java.lang.Number", "java.lang.Boolean",
            "java.lang.Byte", "java.lang.Short", "java.lang.Integer", "java.lang.Long",
            "java.lang.Float", "java.lang.Double", "java.lang.Character", "java.lang.Enum",
            "java.util.ArrayList", "java.util.LinkedList", "java.util.Arrays$ArrayList",
            "java.util.HashMap", "java.util.LinkedHashMap", "java.util.TreeMap",
            "java.util.HashSet", "java.util.LinkedHashSet", "java.util.TreeSet",
            "java.util.EnumMap", "java.util.EnumSet$SerializationProxy",
            "java.util.RegularEnumSet", "java.util.JumboEnumSet",
            "java.util.CollSer", "java.util.UUID", "java.util.Map$Entry");

    /**
     * A read only builds the classes an item is made of, and nothing deep or
     * huge. An unfiltered object stream builds any serialisable class on the
     * server's classpath out of whatever bytes it is handed. The rule is RP
     * Engine's for its stored containers.
     */
    private static final ObjectInputFilter FILTER = info -> {
        if (info.depth() > 32 || info.references() > 1_000_000 || info.arrayLength() > 1_000_000) {
            return ObjectInputFilter.Status.REJECTED;
        }
        Class<?> type = info.serialClass();
        if (type == null) return ObjectInputFilter.Status.UNDECIDED;
        return allowed(type) ? ObjectInputFilter.Status.ALLOWED : ObjectInputFilter.Status.REJECTED;
    };

    private static boolean allowed(Class<?> type) {
        while (type.isArray()) type = type.getComponentType();
        // An enum constant is read by name, never constructed.
        if (type.isPrimitive() || type.isEnum()) return true;
        String name = type.getName();
        if (ALLOWED.contains(name)) return true;
        // What a Wrapper resolves to (the stack, its meta), built by Bukkit from the wrapper's map.
        if (ConfigurationSerializable.class.isAssignableFrom(type) && !Serializable.class.isAssignableFrom(type)) return true;
        if (name.startsWith("java.util.Collections$Unmodifiable") || name.startsWith("java.util.Collections$Singleton")
                || name.startsWith("java.util.Collections$Empty") || name.startsWith("java.util.ImmutableCollections$")) {
            return true;
        }
        // Guava's immutable collections, which is what a stack's serialised map is.
        if (!name.startsWith("com.google.common.collect.")) return false;
        if (name.contains("SerializedForm")) return true;
        for (Class<?> at = type; at != null; at = at.getSuperclass()) {
            String parent = at.getName();
            if (parent.equals("com.google.common.collect.ImmutableCollection") || parent.equals("com.google.common.collect.ImmutableMap")) return true;
        }
        return false;
    }

    private static String write(ItemStack[] items) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (BukkitObjectOutputStream out = new BukkitObjectOutputStream(bytes)) {
            out.writeInt(items.length);
            for (ItemStack it : items) out.writeObject(it);
        }
        return Base64.getEncoder().encodeToString(bytes.toByteArray());
    }

    private static ItemStack[] read(String data) throws IOException, ClassNotFoundException {
        try (BukkitObjectInputStream in = new BukkitObjectInputStream(new ByteArrayInputStream(Base64.getDecoder().decode(data)))) {
            in.setObjectInputFilter(FILTER);
            int count = in.readInt();
            // write() only ever stores the nine hotbar slots.
            if (count < 0 || count > 9) throw new IOException("Not a kept hotbar: " + count + " stacks");
            ItemStack[] items = new ItemStack[count];
            for (int i = 0; i < items.length; i++) items[i] = (ItemStack) in.readObject();
            return items;
        }
    }
}
