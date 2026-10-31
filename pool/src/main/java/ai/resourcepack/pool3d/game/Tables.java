package ai.resourcepack.pool3d.game;

import ai.resourcepack.engine3d.api.Engine3D;
import ai.resourcepack.pool3d.Assets;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Chunk;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.block.BlockFace;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Every table on the server: placing and removing them, who plays where, and the keys. */
public final class Tables implements Listener {

    final JavaPlugin plugin;
    private final Engine3D engine;
    private final Map<String, PoolTable> tables = new LinkedHashMap<>();
    private final File file;
    boolean guide;
    /** Metres of cue travel per degree the mouse turns the view during a stroke. */
    double strokeScale;
    /** Multiplies how fast the cue ball leaves the tip. */
    double strokePower;
    /** Ball radii the tip moves per degree, holding W. */
    double spinScale;

    public Tables(JavaPlugin plugin, Engine3D engine) {
        this.plugin = plugin;
        this.engine = engine;
        this.file = new File(plugin.getDataFolder(), "tables.yml");
        this.guide = plugin.getConfig().getBoolean("aim-guide", false);
        this.strokeScale = plugin.getConfig().getDouble("stroke.sensitivity", 0.012);
        this.strokePower = plugin.getConfig().getDouble("stroke.power", 1.0);
        this.spinScale = plugin.getConfig().getDouble("spin-sensitivity", 0.012);
    }

    // ---- placing ------------------------------------------------------------------------------

    /**
     * A table in front of {@code p}, its length the way they face. Its middle
     * sits on the block grid so that three blocks along and two across hold
     * it, and those six must be free.
     */
    public String create(Player p) {
        Location l = p.getLocation();
        BlockFace f = facing(l.getYaw());
        boolean alongZ = f == BlockFace.NORTH || f == BlockFace.SOUTH;
        Block feet = l.getBlock();
        // Three blocks ahead to the middle: the near end is a block and a half away.
        int ahead = 3;
        double cx, cz;
        if (alongZ) {
            cz = feet.getZ() + 0.5 + f.getModZ() * ahead;
            cx = feet.getX() + 1.0;
        } else {
            cx = feet.getX() + 0.5 + f.getModX() * ahead;
            cz = feet.getZ() + 1.0;
        }
        String id = "t" + System.currentTimeMillis() % 100000000;
        PoolTable t = new PoolTable(this, engine, id, l.getWorld(), cx, feet.getY(), cz, alongZ);
        for (Block b : t.footprint()) if (!b.getType().isAir()) return "There's no room: the table needs a 3 by 2 space of air ahead of you.";
        tables.put(id, t);
        keepLoaded(t, true);
        if (engine.ready()) t.spawn();
        save();
        return null;
    }

    private static BlockFace facing(float yaw) {
        float y = ((yaw % 360) + 360) % 360;
        if (y >= 315 || y < 45) return BlockFace.SOUTH;
        if (y < 135) return BlockFace.WEST;
        if (y < 225) return BlockFace.NORTH;
        return BlockFace.EAST;
    }

    /** A table with its middle at (x, y, z), long along X or Z: for the console. */
    public String create(World w, double x, double y, double z, boolean alongZ) {
        PoolTable t = new PoolTable(this, engine, "t" + System.currentTimeMillis() % 100000000, w, x, y, z, alongZ);
        for (Block b : t.footprint()) if (!b.getType().isAir()) return "There's no room there.";
        tables.put(t.id, t);
        keepLoaded(t, true);
        if (engine.ready()) t.spawn();
        save();
        return null;
    }

    public boolean remove(PoolTable t) {
        for (Player p : t.online()) leave(p);
        t.despawn();
        keepLoaded(t, false);
        tables.remove(t.id);
        save();
        return true;
    }

    private void keepLoaded(PoolTable t, boolean on) {
        for (Block b : t.footprint()) {
            Chunk c = b.getChunk();
            if (on) c.addPluginChunkTicket(plugin);
            else c.removePluginChunkTicket(plugin);
        }
    }

    public PoolTable nearest(Location l, double within) {
        PoolTable best = null;
        double d = within * within;
        for (PoolTable t : tables.values()) {
            if (!t.world.equals(l.getWorld())) continue;
            double dd = t.centre().distanceSquared(l);
            if (dd < d) {
                d = dd;
                best = t;
            }
        }
        return best;
    }

    public PoolTable of(Player p) {
        for (PoolTable t : tables.values()) if (t.has(p)) return t;
        return null;
    }

    /** The table {@code p} is looking at, within reach. */
    PoolTable lookedAt(Player p) {
        Location eye = p.getEyeLocation();
        org.bukkit.util.Vector d = eye.getDirection();
        for (PoolTable t : tables.values()) {
            if (!t.world.equals(p.getWorld()) || d.getY() >= 0) continue;
            double s = (t.cy + PoolTable.BED + 0.04 - eye.getY()) / d.getY();
            if (s <= 0 || s > 5) continue;
            double[] xy = t.toTable(eye.getX() + d.getX() * s, eye.getZ() + d.getZ() * s);
            if (Math.abs(xy[0]) < PoolTable.OUT_X && Math.abs(xy[1]) < PoolTable.OUT_Y) return t;
        }
        return null;
    }

    // ---- playing --------------------------------------------------------------------------------

    public String join(Player p, PoolTable t) {
        if (of(p) != null) return "You're already at a table: /pool leave first.";
        if (t.players.size() >= 2) return "That table has a game on.";
        PlayerInventory inv = p.getInventory();
        int slot = -1;
        for (int i = 0; i < 9; i++) if (inv.getItem(i) == null) {
            slot = i;
            break;
        }
        if (slot < 0) {
            slot = inv.getHeldItemSlot();
            ItemStack old = inv.getItem(slot);
            inv.setItem(slot, null);
            for (ItemStack left : inv.addItem(old).values()) p.getWorld().dropItem(p.getLocation(), left);
        }
        inv.setItem(slot, Assets.cue());
        inv.setHeldItemSlot(slot);
        t.join(p);
        say(p, "Mouse aims and tilts. Hold S and move the mouse back and forward to cue. Hold W and move the mouse for spin, Q centres it. A/D fine aim, scroll zooms, space looks down on the table, sneak gets up, F toggles the guide line.");
        return null;
    }

    public void leave(Player p) {
        PoolTable t = of(p);
        if (t != null) t.leave(p);
    }

    void say(Player p, String text) {
        p.sendMessage(ChatColor.DARK_GREEN + "Pool " + ChatColor.DARK_GRAY + "» " + ChatColor.GRAY + text);
    }

    public void rack(Player p) {
        PoolTable t = of(p);
        if (t != null) t.rack(t.players.size() > 1 ? (t.game != null ? t.game.turn : 0) : 0);
    }

    /** The shooter at their table, if it is their turn. */
    private PoolTable shooting(Player p) {
        PoolTable t = of(p);
        return t != null && p.equals(t.shooter()) ? t : null;
    }

    public void tick() {
        // Nothing can be drawn until 3D Engine has baked the models.
        if (!engine.ready()) return;
        for (PoolTable t : tables.values()) {
            t.tick();
            keepOut(t);
        }
    }

    /**
     * The table is solid: anybody walking into it is eased back out. (Not
     * barriers: a barrier is a block the crosshair finds, and the game draws
     * its outline over the cloth.)
     */
    private void keepOut(PoolTable t) {
        for (Player p : t.world.getPlayers()) {
            // A shooter in the shot camera sits with their body in the table.
            if (p.getGameMode() == org.bukkit.GameMode.SPECTATOR || p.isInsideVehicle()) continue;
            Location l = p.getLocation();
            if (l.getY() > t.cy + PoolTable.BED + 0.1 || l.getY() < t.cy - 1.5) continue;
            double[] xy = t.toTable(l.getX(), l.getZ());
            double ax = PoolTable.OUT_X + 0.3, ay = PoolTable.OUT_Y + 0.3;
            double inX = ax - Math.abs(xy[0]), inY = ay - Math.abs(xy[1]);
            if (inX <= 0 || inY <= 0) continue;
            // Out the nearer side.
            double tx = 0, ty = 0;
            if (inX < inY) tx = Math.signum(xy[0]) * inX;
            else ty = Math.signum(xy[1]) * inY;
            org.joml.Vector3f w = t.dir(tx, ty, 0);
            if (inX > 0.35 && inY > 0.35) {
                p.teleport(l.add(w.x, 0, w.z));
                continue;
            }
            org.bukkit.util.Vector v = p.getVelocity();
            double len = Math.hypot(w.x, w.z);
            double nx = w.x / len, nz = w.z / len;
            double into = v.getX() * nx + v.getZ() * nz;
            double push = Math.max(0.08, Math.min(0.3, len * 0.9));
            v.setX(v.getX() + nx * (push - Math.min(0, into)));
            v.setZ(v.getZ() + nz * (push - Math.min(0, into)));
            p.setVelocity(v);
        }
    }

    public void spawnAll() {
        for (PoolTable t : tables.values()) t.spawn();
    }

    public void shutdown() {
        for (PoolTable t : new ArrayList<>(tables.values())) {
            for (Player p : t.online()) t.leave(p);
            t.despawn();
        }
    }

    // ---- saving -------------------------------------------------------------------------------

    void save() {
        YamlConfiguration y = new YamlConfiguration();
        for (PoolTable t : tables.values()) {
            ConfigurationSection s = y.createSection("tables." + t.id);
            s.set("world", t.world.getName());
            s.set("x", t.cx);
            s.set("y", t.cy);
            s.set("z", t.cz);
            s.set("along", t.alongZ ? "z" : "x");
        }
        try {
            y.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Couldn't save tables.yml: " + e.getMessage());
        }
    }

    public void load() {
        if (!file.exists()) return;
        YamlConfiguration y = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection all = y.getConfigurationSection("tables");
        if (all == null) return;
        for (String id : all.getKeys(false)) {
            ConfigurationSection s = all.getConfigurationSection(id);
            World w = Bukkit.getWorld(s.getString("world", ""));
            if (w == null) continue;
            PoolTable t = new PoolTable(this, engine, id, w, s.getDouble("x"), s.getDouble("y"), s.getDouble("z"), "z".equals(s.getString("along")));
            tables.put(id, t);
            keepLoaded(t, true);
        }
    }

    // ---- keys -----------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent e) {
        if (e.getHand() != EquipmentSlot.HAND) return;
        Player p = e.getPlayer();
        if ((e.getAction() == Action.RIGHT_CLICK_BLOCK || e.getAction() == Action.RIGHT_CLICK_AIR) && of(p) == null) {
            PoolTable t = lookedAt(p);
            if (t != null) {
                e.setCancelled(true);
                String err = p.hasPermission("pool3d.play") ? join(p, t) : "You don't have permission to play (pool3d.play).";
                if (err != null) say(p, err);
                return;
            }
        }
        PoolTable t = of(p);
        if (t == null) return;
        if (e.getAction() == Action.RIGHT_CLICK_AIR || e.getAction() == Action.RIGHT_CLICK_BLOCK) t.click(p);
    }

    @EventHandler
    public void onSwing(PlayerAnimationEvent e) {
        PoolTable t = shooting(e.getPlayer());
        if (t != null) t.cancelStroke();
    }

    @EventHandler(ignoreCancelled = true)
    public void onScroll(PlayerItemHeldEvent e) {
        Player p = e.getPlayer();
        PoolTable t = shooting(p);
        if (t == null || !Assets.isCue(p.getInventory().getItem(e.getPreviousSlot()))) return;
        if (t.state != PoolTable.State.AIM) return;
        int d = e.getNewSlot() - e.getPreviousSlot();
        if (d > 4) d -= 9;
        if (d < -4) d += 9;
        if (d == 0) return;
        e.setCancelled(true);
        t.scroll(p, d > 0 ? 1 : -1);
    }

    @EventHandler(ignoreCancelled = true)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        Player p = e.getPlayer();
        if (!Assets.isCue(e.getOffHandItem()) && !Assets.isCue(e.getMainHandItem())) return;
        e.setCancelled(true);
        PoolTable t = shooting(p);
        if (t != null) {
            guide = !guide;
            say(p, "Aiming guide " + (guide ? "on" : "off") + ".");
        }
    }

    /**
     * Sneaking gets a shooter off their seat to walk round the table, which is
     * the free camera here; anything else that would take them off it (the
     * seat moving) is not them getting up.
     */
    @EventHandler(ignoreCancelled = true)
    public void onDismount(org.bukkit.event.entity.EntityDismountEvent e) {
        if (!(e.getEntity() instanceof Player p)) return;
        PoolTable t = of(p);
        if (t == null) return;
        ShotCamera c = t.cameraOf(p);
        if (c == null || !c.seat(e.getDismounted()) || c.moving) return;
        t.gotUp(p);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent e) {
        if (!Assets.isCue(e.getItemDrop().getItemStack())) return;
        e.setCancelled(true);
        PoolTable t = shooting(e.getPlayer());
        if (t != null) t.centreSpin(e.getPlayer());
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        leave(e.getPlayer());
    }

    public List<String> ids() {
        return new ArrayList<>(tables.keySet());
    }
}
