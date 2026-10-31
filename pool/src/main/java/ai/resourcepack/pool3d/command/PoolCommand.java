package ai.resourcepack.pool3d.command;

import ai.resourcepack.pool3d.game.PoolTable;
import ai.resourcepack.pool3d.game.Tables;
import org.bukkit.ChatColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** {@code /pool}: create, remove, join, leave, rack. */
public final class PoolCommand implements TabExecutor {

    private static final Map<String, String> PERMISSION = Map.of(
            "create", "pool3d.create", "remove", "pool3d.remove",
            "join", "pool3d.play", "leave", "pool3d.play", "rack", "pool3d.play", "shoot", "pool3d.shoot");

    private final Tables tables;

    public PoolCommand(Tables tables) {
        this.tables = tables;
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (args.length >= 1 && args[0].equalsIgnoreCase("shoot") && sender.hasPermission("pool3d.shoot")) return shoot(sender, args);
        if (!(sender instanceof Player) && args.length >= 1 && args[0].equalsIgnoreCase("test")) return test(sender, args);
        if (!(sender instanceof Player p)) {
            if (args.length == 6 && args[0].equalsIgnoreCase("create")) {
                org.bukkit.World w = org.bukkit.Bukkit.getWorld(args[1]);
                String err = w == null ? "No world " + args[1] : tables.create(w, Double.parseDouble(args[2]), Double.parseDouble(args[3]),
                        Double.parseDouble(args[4]), args[5].equalsIgnoreCase("z"));
                sender.sendMessage(err == null ? "Table set up." : err);
                return true;
            }
            sender.sendMessage("From the console: /pool create <world> <x> <y> <z> <x|z>, /pool shoot <x> <y> <z> <degrees> <power 0-1> [side] [height]");
            return true;
        }
        String sub = args.length == 0 ? "help" : args[0].toLowerCase();
        String perm = PERMISSION.get(sub);
        if (perm != null && !p.hasPermission(perm)) {
            say(p, ChatColor.RED + "You don't have permission for that (" + perm + ").");
            return true;
        }
        switch (sub) {
            case "create" -> {
                String err = tables.create(p);
                say(p, err != null ? ChatColor.RED + err : "Table set up. Right-click it to play.");
            }
            case "remove" -> {
                PoolTable t = tables.nearest(p.getLocation(), 6);
                if (t == null) say(p, ChatColor.RED + "There's no table within six blocks.");
                else {
                    tables.remove(t);
                    say(p, "Table removed.");
                }
            }
            case "join" -> {
                PoolTable t = tables.nearest(p.getLocation(), 8);
                String err = t == null ? "There's no table near you." : tables.join(p, t);
                if (err != null) say(p, ChatColor.RED + err);
            }
            case "leave" -> {
                if (tables.of(p) == null) say(p, "You're not at a table.");
                else {
                    tables.leave(p);
                    say(p, "You left the table.");
                }
            }
            case "rack" -> {
                if (tables.of(p) == null) say(p, "You're not at a table.");
                else tables.rack(p);
            }
            default -> {
                say(p, "/pool join " + ChatColor.DARK_GRAY + "(or right-click a table)" + ChatColor.GRAY + ", /pool leave, /pool rack");
                if (p.hasPermission("pool3d.create")) say(p, "/pool create, /pool remove");
            }
        }
        return true;
    }

    /** /pool shoot x y z degrees power [side] [height]: plays a shot on the table nearest (x, y, z), for testing. */
    private boolean shoot(CommandSender sender, String[] a) {
        try {
            org.bukkit.World w = sender instanceof Player p ? p.getWorld() : org.bukkit.Bukkit.getWorlds().get(0);
            org.bukkit.Location l = new org.bukkit.Location(w, Double.parseDouble(a[1]), Double.parseDouble(a[2]), Double.parseDouble(a[3]));
            PoolTable t = tables.nearest(l, 16);
            if (t == null) {
                sender.sendMessage("No table near there.");
                return true;
            }
            if (a.length > 9) t.placeCue(Double.parseDouble(a[8]), Double.parseDouble(a[9]));
            t.shoot(Double.parseDouble(a[4]), Double.parseDouble(a[5]), a.length > 6 ? Double.parseDouble(a[6]) : 0, a.length > 7 ? Double.parseDouble(a[7]) : 0);
            sender.sendMessage("Shot.");
        } catch (RuntimeException e) {
            sender.sendMessage("/pool shoot <x> <y> <z> <degrees> <power 0-1> [side] [height] [cue x] [cue y]");
        }
        return true;
    }

    /**
     * Console-only checks: {@code test join <player>} brings a player to the
     * table, {@code test cue <x> <y>} puts the cue ball down, {@code test pose
     * <n> <qx> <qy> <qz> <qw>} turns a ball.
     */
    private boolean test(CommandSender sender, String[] a) {
        PoolTable t = tables.nearest(new org.bukkit.Location(org.bukkit.Bukkit.getWorlds().get(0), 0, -60, 0), 1e6);
        if (t == null) return true;
        if (a[1].equals("join")) {
            Player p = org.bukkit.Bukkit.getPlayerExact(a[2]);
            String err = p == null ? "No player " + a[2] : tables.join(p, t);
            sender.sendMessage(err == null ? "ok" : err);
            return true;
        }
        if (a[1].equals("hold")) {
            Player p = org.bukkit.Bukkit.getPlayerExact(a[2]);
            if (p != null) t.testHold(p);
            sender.sendMessage("ok");
            return true;
        }
        if (a[1].equals("click")) {
            Player p = org.bukkit.Bukkit.getPlayerExact(a[2]);
            if (p != null) t.click(p);
            sender.sendMessage("ok");
            return true;
        }
        if (a[1].equals("cue")) t.placeCue(Double.parseDouble(a[2]), Double.parseDouble(a[3]));
        if (a[1].equals("pose")) t.pose(Integer.parseInt(a[2]), Float.parseFloat(a[3]), Float.parseFloat(a[4]), Float.parseFloat(a[5]), Float.parseFloat(a[6]));
        sender.sendMessage("ok");
        return true;
    }

    private static void say(Player p, String text) {
        p.sendMessage(ChatColor.DARK_GREEN + "Pool " + ChatColor.DARK_GRAY + "» " + ChatColor.GRAY + text);
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String label, String[] args) {
        List<String> out = new ArrayList<>();
        if (args.length == 1)
            for (String s : List.of("join", "leave", "rack", "create", "remove"))
                if (s.startsWith(args[0].toLowerCase()) && (PERMISSION.get(s) == null || sender.hasPermission(PERMISSION.get(s)))) out.add(s);
        return out;
    }
}
