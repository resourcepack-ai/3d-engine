package ai.resourcepack.engine3d.runtime;

import ai.resourcepack.engine3d.api.ModelInstance;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.util.Vector;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /3d}: list, place, remove, scale, rotate, animate and give models, and
 * re-bake the folder. A model is picked by looking at it.
 */
final class Commands implements CommandExecutor, TabCompleter, Listener {

    private static final NamespacedKey PLACES = new NamespacedKey("3dengine", "places");

    private final Engine engine;
    private final JavaPlugin plugin;
    /** Effects put down with /3d effect, so /3d effect clear can take them away. */
    private final List<ai.resourcepack.engine3d.api.EffectInstance> placedEffects = new ArrayList<>();

    Commands(Engine engine, JavaPlugin plugin) {
        this.engine = engine;
        this.plugin = plugin;
    }

    private static void say(CommandSender to, String text) {
        to.sendMessage(ChatColor.AQUA + "3D " + ChatColor.GRAY + text);
    }

    /** Whether {@code sender} may run {@code /3d <sub>}. */
    private static boolean allowed(CommandSender sender, String sub) {
        return CommandPermissions.SUBS.contains(sub) && sender.hasPermission(CommandPermissions.permissionFor(sub));
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, String @NotNull [] args) {
        // Permissions are the sender's own, also when the console acts as a player below.
        CommandSender actor = sender;
        // From the console, a trailing player name acts as that player: /3d effect fire 2 Steve.
        if (!(sender instanceof Player) && args.length > 1) {
            Player as = plugin.getServer().getPlayerExact(args[args.length - 1]);
            if (as != null) {
                sender = as;
                args = java.util.Arrays.copyOf(args, args.length - 1);
            }
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase(Locale.ROOT);
        if (CommandPermissions.SUBS.contains(sub) && !allowed(actor, sub)) {
            say(sender, ChatColor.RED + "You need " + CommandPermissions.permissionFor(sub) + " for that.");
            return true;
        }
        switch (sub) {
            case "list" -> {
                if (engine.loadedModels().isEmpty()) say(sender, "No models yet. Put .glb, .gltf or .obj files in " + plugin.getDataFolder().getName() + "/models and run /3d reload.");
                for (LoadedModel m : engine.loadedModels().values())
                    say(sender, String.format("%s  %.2f blocks tall  states: %s", m.name(), m.height(), String.join(", ", m.states())));
                for (String e : engine.errors()) say(sender, "failed: " + e);
            }
            case "effect" -> {
                if (args.length < 2) {
                    say(sender, "Effects: " + (engine.effectNames().isEmpty() ? "none (put .glsl files in " + plugin.getDataFolder().getName() + "/effects)" : String.join(", ", engine.effectNames())));
                    return usage(sender, "/3d effect <name> [size] | /3d effect clear");
                }
                if (args[1].equalsIgnoreCase("clear")) {
                    placedEffects.forEach(ai.resourcepack.engine3d.api.EffectInstance::remove);
                    say(sender, "Cleared " + placedEffects.size() + " effects.");
                    placedEffects.clear();
                    return true;
                }
                Player p = player(sender);
                if (p == null) return true;
                if (!engine.effectNames().contains(args[1].toLowerCase(Locale.ROOT))) {
                    say(sender, "No effect called " + args[1] + ". Have: " + String.join(", ", engine.effectNames()));
                    return true;
                }
                double size = args.length > 2 ? parse(args[2], 1) : 1;
                Location at = placeAt(p);
                if (at == null) at = p.getEyeLocation().add(p.getEyeLocation().getDirection().multiply(3));
                placedEffects.add(engine.effect(args[1].toLowerCase(Locale.ROOT), at.add(0, size / 2, 0), size));
            }
            case "reload" -> {
                say(sender, "Baking models...");
                engine.reload(sender);
            }
            case "quality" -> {
                Registry.Quality q = engine.quality();
                if (args.length < 2) {
                    say(sender, String.format("Default %d triangles and %dpx textures, at most %d and %dpx.", q.triangles(), q.textureWidth(), q.maxTriangles(), q.maxTextureWidth()));
                    for (var e : q.models().entrySet())
                        say(sender, String.format("  %s: %s triangles, %s texture", e.getKey(), e.getValue()[0] > 0 ? e.getValue()[0] : "default",
                                e.getValue()[1] > 0 ? e.getValue()[1] + "px" : "default"));
                    return usage(sender, "/3d quality <model> <triangles|reset> [texture width]");
                }
                LoadedModel m = model(sender, args[1]);
                if (m == null) return true;
                if (args.length < 3) {
                    say(sender, String.format("%s: %d triangles, %dpx texture, %.1f megatexels in the pack.", m.name(), m.spec.triangles, m.spec.textureWidth, m.manifest.texels / 1e6));
                    return usage(sender, "/3d quality " + m.name() + " <triangles|reset> [texture width]");
                }
                if (args[2].equalsIgnoreCase("reset")) {
                    say(sender, m.name() + " goes back to its own settings. Baking...");
                    engine.setQuality(m.name(), 0, 0, sender);
                    return true;
                }
                int t = (int) parse(args[2], 0), w = args.length > 3 ? (int) parse(args[3], 0) : 0;
                if (t <= 0) return usage(sender, "/3d quality <model> <triangles|reset> [texture width]");
                if (t > q.maxTriangles()) say(sender, "That is over this server's limit (quality.max-triangles), so it gets " + q.maxTriangles() + ".");
                say(sender, "Baking " + m.name() + " at " + Math.min(t, q.maxTriangles()) + " triangles" + (w > 0 ? " and " + w + "px" : "") + "...");
                engine.setQuality(m.name(), t, w, sender);
            }
            case "pack" -> {
                if (sender instanceof Player p) {
                    engine.send(p);
                    say(sender, "Sent the pack.");
                }
            }
            case "place" -> {
                Player p = player(sender);
                if (p == null || args.length < 2) return usage(sender, "/3d place <model> [scale]");
                LoadedModel m = model(sender, args[1]);
                if (m == null) return true;
                Location at = placeAt(p);
                if (at == null) {
                    say(sender, "Look at a block to place it on.");
                    return true;
                }
                ModelInstance i = engine.spawn(m.name(), at, true);
                if (args.length > 2) i.scale(parse(args[2], 1));
                say(sender, "Placed " + m.name() + ".");
            }
            case "give" -> {
                if (args.length < 2) return usage(sender, "/3d give <model> [player]");
                LoadedModel m = model(sender, args[1]);
                if (m == null) return true;
                Player to = args.length > 2 ? plugin.getServer().getPlayerExact(args[2]) : player(sender);
                if (to == null) return usage(sender, "/3d give <model> <player>");
                if (to != actor && !actor.hasPermission(CommandPermissions.othersPermissionFor("give"))) {
                    say(sender, ChatColor.RED + "You need " + CommandPermissions.othersPermissionFor("give") + " to give to somebody else.");
                    return true;
                }
                to.getInventory().addItem(placer(m));
                say(sender, "Gave " + to.getName() + " a " + m.name() + ": right-click a block to place it.");
            }
            case "remove", "scale", "rotate", "state", "sit" -> {
                Player p = player(sender);
                if (p == null) return true;
                Instance i = lookedAt(p);
                if (i == null) {
                    say(sender, "Look at a model first.");
                    return true;
                }
                switch (sub) {
                    case "remove" -> {
                        i.remove();
                        say(sender, "Removed " + i.model.name() + ".");
                    }
                    case "scale" -> {
                        if (args.length < 2) return usage(sender, "/3d scale <size, 1 = as made>");
                        i.scale(parse(args[1], i.scale()));
                        say(sender, String.format("%s at scale %.2f.", i.model.name(), i.scale()));
                    }
                    case "rotate" -> {
                        if (args.length < 2) return usage(sender, "/3d rotate <degrees>");
                        Location l = i.location();
                        l.setYaw(l.getYaw() + (float) parse(args[1], 0));
                        i.teleport(l);
                    }
                    case "sit" -> {
                        if (i.model.seats().isEmpty()) say(sender, i.model.name() + " has no seats.");
                        else if (!i.mount(p, args.length > 1 ? args[1] : null)) say(sender, "No free seat" + (args.length > 1 ? " called " + args[1] : "") + ".");
                    }
                    case "state" -> {
                        if (args.length < 2) return usage(sender, "/3d state <" + String.join("|", i.model.states()) + ">");
                        if (!i.model.states().contains(args[1])) {
                            say(sender, i.model.name() + " has no state " + args[1] + ": " + String.join(", ", i.model.states()));
                            return true;
                        }
                        i.state(args[1]);
                    }
                    default -> { }
                }
            }
            case "info" -> {
                LoadedModel m = args.length > 1 ? model(sender, args[1]) : null;
                if (m == null && sender instanceof Player p) {
                    Instance i = lookedAt(p);
                    if (i != null) m = i.model;
                }
                if (m == null) return usage(sender, "/3d info <model>");
                say(sender, String.format("%s: %.2f tall, %.2f wide; states %s; points %s; %d game models, %.1f megatexels",
                        m.name(), m.height(), m.radius() * 2, String.join(", ", m.states()), m.points().isEmpty() ? "none" : String.join(", ", m.points()),
                        m.manifest.models.size(), m.manifest.texels / 1e6));
                say(sender, String.format("seats %s%s; looks %s; %d hitbox%s%s",
                        m.seats().isEmpty() ? "none" : String.join(", ", m.seats()), m.drivable() ? " (drivable from " + m.driverSeat() + ")" : "",
                        m.spec.look == null ? "no" : m.spec.look.bone != null ? "with its " + m.spec.look.bone : "with its whole body",
                        Instance.boxesOf(m).size(), Instance.boxesOf(m).size() == 1 ? "" : "es", m.spec.solid ? ", solid" : ""));
            }
            default -> help(actor, sender);
        }
        return true;
    }

    /** The subcommands {@code actor} may run, and only those. */
    private static void help(CommandSender actor, CommandSender to) {
        List<String> theirs = new ArrayList<>();
        for (String sub : CommandPermissions.SUBS) if (allowed(actor, sub)) theirs.add(CommandPermissions.USAGE.get(sub));
        if (theirs.isEmpty()) {
            say(to, ChatColor.RED + "You don't have permission to use /3d.");
            return;
        }
        say(to, "/3d " + String.join(" | ", theirs));
        if (allowed(actor, "remove") || allowed(actor, "scale") || allowed(actor, "rotate") || allowed(actor, "state"))
            say(to, "Look at a placed model to remove, scale, rotate or animate it.");
    }

    private static boolean usage(CommandSender to, String text) {
        say(to, "Usage: " + text);
        return true;
    }

    private static Player player(CommandSender sender) {
        if (sender instanceof Player p) return p;
        say(sender, "Only a player can do that.");
        return null;
    }

    private LoadedModel model(CommandSender to, String name) {
        LoadedModel m = engine.loadedModels().get(name.toLowerCase(Locale.ROOT));
        if (m == null) say(to, "No model called " + name + ". /3d list shows them.");
        return m;
    }

    private static double parse(String s, double fallback) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }

    /** On top of (or against) the block looked at, facing the player. */
    private static Location placeAt(Player p) {
        Block b = p.getTargetBlockExact(8);
        if (b == null) return null;
        org.bukkit.util.RayTraceResult hit = p.rayTraceBlocks(8);
        BlockFace face = hit == null ? null : hit.getHitBlockFace();
        Location at;
        if (face == null || face == BlockFace.UP) at = b.getLocation().add(0.5, Steering.height(b) > 0 ? Steering.height(b) : 1, 0.5);
        else at = b.getRelative(face).getLocation().add(0.5, 0, 0.5);
        at.setYaw(p.getLocation().getYaw() + 180);
        return at;
    }

    /** The model the player is looking at, within eight blocks. */
    private Instance lookedAt(Player p) {
        Location eye = p.getEyeLocation();
        Vector dir = eye.getDirection();
        Instance best = null;
        double bestT = Double.MAX_VALUE;
        for (Instance i : engine.all()) {
            if (!i.valid() || i.display.getWorld() != eye.getWorld()) continue;
            Location at = i.location();
            if (at.distanceSquared(eye) > 16 * 16) continue;
            double r = Math.max(0.3, i.model.radius() * i.scale()), h = Math.max(0.3, i.model.height() * i.scale());
            for (double t = 0; t < 8 && t < bestT; t += 0.1) {
                double x = eye.getX() + dir.getX() * t, y = eye.getY() + dir.getY() * t, z = eye.getZ() + dir.getZ() * t;
                if (y < at.getY() || y > at.getY() + h) continue;
                if (Math.hypot(x - at.getX(), z - at.getZ()) <= r) {
                    best = i;
                    bestT = t;
                    break;
                }
            }
        }
        return best;
    }

    /** An item that places {@code m} where it's used; it shows the model itself in the inventory. */
    private static ItemStack placer(LoadedModel m) {
        String still = m.manifest.icon != null ? m.manifest.icon : m.manifest.states.get(m.defaultState()).model;
        ItemStack item = Animator.item(m.name(), still, 0);
        ItemMeta meta = item.getItemMeta();
        meta.setItemName(ChatColor.AQUA + m.name());
        meta.setLore(List.of(ChatColor.GRAY + "Right-click a block to place"));
        meta.getPersistentDataContainer().set(PLACES, PersistentDataType.STRING, m.name());
        item.setItemMeta(meta);
        return item;
    }

    @EventHandler
    public void onUse(PlayerInteractEvent e) {
        if (e.getAction() != Action.RIGHT_CLICK_BLOCK || e.getHand() != EquipmentSlot.HAND || e.getItem() == null || !e.getItem().hasItemMeta()) return;
        String name = e.getItem().getItemMeta().getPersistentDataContainer().get(PLACES, PersistentDataType.STRING);
        if (name == null) return;
        e.setCancelled(true);
        if (!e.getPlayer().hasPermission("3dengine.place")) return;
        LoadedModel m = engine.loadedModels().get(name);
        if (m == null) {
            say(e.getPlayer(), "That model isn't loaded any more.");
            return;
        }
        Block b = e.getClickedBlock();
        Location at = e.getBlockFace() == BlockFace.UP ? b.getLocation().add(0.5, Steering.height(b) > 0 ? Steering.height(b) : 1, 0.5)
                : b.getRelative(e.getBlockFace()).getLocation().add(0.5, 0, 0.5);
        at.setYaw(e.getPlayer().getLocation().getYaw() + 180);
        engine.spawn(m.name(), at, true);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, String @NotNull [] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1) {
            // Only what they may run: completing a command that then refuses is worse than not completing it.
            for (String s : CommandPermissions.SUBS) if (s.startsWith(args[0].toLowerCase(Locale.ROOT)) && allowed(sender, s)) out.add(s);
        } else if (!allowed(sender, args[0].toLowerCase(Locale.ROOT))) {
            return out;
        } else if (args.length == 2 && (args[0].equalsIgnoreCase("place") || args[0].equalsIgnoreCase("give") || args[0].equalsIgnoreCase("info") || args[0].equalsIgnoreCase("quality"))) {
            for (String m : engine.loadedModels().keySet()) if (m.startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(m);
        } else if (args.length == 2 && args[0].equalsIgnoreCase("effect")) {
            for (String m : engine.effectNames()) if (m.startsWith(args[1].toLowerCase(Locale.ROOT))) out.add(m);
            if ("clear".startsWith(args[1].toLowerCase(Locale.ROOT))) out.add("clear");
        } else if (args.length == 2 && args[0].equalsIgnoreCase("state") && sender instanceof Player p) {
            Instance i = lookedAt(p);
            if (i != null) for (String s : i.model.states()) if (s.startsWith(args[1])) out.add(s);
        }
        return out;
    }
}
