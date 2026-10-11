package ai.resourcepack.engine3d.runtime.platform;

import ai.resourcepack.engine3d.runtime.movement.Terrain;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.BiPredicate;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Spigot's half of {@link Platform}: no mob goals and no pathfinder in the
 * API, so the brain's AI is switched off and a short path is searched here.
 */
final class SpigotPlatform implements Platform {

    private static final Logger LOG = Logger.getLogger("3DEngine");

    @Override
    public String name() {
        return "Spigot";
    }

    @Override
    public void prepareBrain(Mob brain, Plugin plugin, Legs legs) {
        // An unaware mob runs no goals and paths nowhere, but still falls,
        // collides and moves under the velocity the steering gives it.
        brain.setAware(false);
    }

    @Override
    public void stopPathfinding(Mob brain) {
    }

    @Override
    public boolean listenForSwings(Plugin plugin, BiPredicate<Player, Entity> attacked) {
        return false;
    }

    @Override
    public boolean cancellable(EntityDismountEvent event) {
        return true;
    }

    @Override
    public TextDisplay spawnText(Location at, String text, String font, int rgb, Consumer<TextDisplay> before) {
        // Spigot sets a text display's text only from legacy formatting, which
        // has no font; the entity's own data does.
        String snbt = "{id:\"minecraft:text_display\",text:{text:\"" + text + "\",font:\"" + font + "\",color:\"#"
                + String.format("%06X", rgb & 0xFFFFFF) + "\"}}";
        TextDisplay d = (TextDisplay) Bukkit.getEntityFactory().createEntitySnapshot(snbt).createEntity(at);
        before.accept(d);
        return d;
    }

    // ---- moving a seat with somebody in it ----------------------------------------

    private static Method place;
    private static boolean looked;
    /** Set while a seat's riders are lifted off and put back to move it, so that is not taken for getting out. */
    private static boolean reseating;

    @Override
    public boolean reseating() {
        return reseating;
    }

    @Override
    public void moveWithRiders(Entity entity, Location to) {
        if (entity.getPassengers().isEmpty()) {
            entity.teleport(to);
            return;
        }
        // Spigot refuses to teleport anything with riders. The game itself
        // just sets the position, and the riders follow it on the next tick.
        Method m = placer(entity);
        if (m != null) {
            try {
                Object handle = entity.getClass().getMethod("getHandle").invoke(entity);
                if (m.getParameterCount() == 5) m.invoke(handle, to.getX(), to.getY(), to.getZ(), to.getYaw(), to.getPitch());
                else m.invoke(handle, to.getX(), to.getY(), to.getZ());
                return;
            } catch (ReflectiveOperationException e) {
                place = null;
            }
        }
        List<Entity> riders = new ArrayList<>(entity.getPassengers());
        reseating = true;
        try {
            for (Entity r : riders) entity.removePassenger(r);
            entity.teleport(to);
            for (Entity r : riders) entity.addPassenger(r);
        } finally {
            reseating = false;
        }
    }

    private static synchronized Method placer(Entity entity) {
        if (looked) return place;
        looked = true;
        try {
            Object handle = entity.getClass().getMethod("getHandle").invoke(entity);
            for (String name : new String[]{"snapTo", "moveTo", "absSnapTo", "absMoveTo"}) {
                for (Class<?> c = handle.getClass(); c != null && place == null; c = c.getSuperclass()) {
                    try {
                        place = c.getDeclaredMethod(name, double.class, double.class, double.class, float.class, float.class);
                    } catch (NoSuchMethodException ignored) {
                    }
                }
                if (place != null) break;
            }
            if (place == null) {
                for (Class<?> c = handle.getClass(); c != null && place == null; c = c.getSuperclass()) {
                    try {
                        place = c.getDeclaredMethod("setPos", double.class, double.class, double.class);
                    } catch (NoSuchMethodException ignored) {
                    }
                }
            }
            if (place != null) place.setAccessible(true);
        } catch (ReflectiveOperationException | RuntimeException e) {
            place = null;
        }
        if (place == null) LOG.warning("Can't move a seat with its rider in place on this server; riders are lifted off and back on each move.");
        return place;
    }

    // ---- a short path, since Spigot has no pathfinder in its API ------------------

    private static final int[][] DIRS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    @Override
    public List<Location> path(Mob brain, Location target) {
        World w = brain.getWorld();
        if (target.getWorld() != w) return null;
        Location from = brain.getLocation();
        int sx = from.getBlockX(), sy = from.getBlockY(), sz = from.getBlockZ();
        int tx = target.getBlockX(), ty = target.getBlockY(), tz = target.getBlockZ();
        if (Math.abs(tx - sx) > 32 || Math.abs(tz - sz) > 32) return null;

        record Node(int x, int y, int z) {
        }
        Map<Node, Node> came = new HashMap<>();
        Map<Node, Double> cost = new HashMap<>();
        PriorityQueue<Object[]> open = new PriorityQueue<>((a, b) -> Double.compare((double) a[0], (double) b[0]));
        Node start = new Node(sx, sy, sz);
        cost.put(start, 0.0);
        open.add(new Object[]{0.0, start});
        Node end = null;
        int expanded = 0;
        while (!open.isEmpty() && expanded++ < 4000) {
            Node n = (Node) open.poll()[1];
            if (Math.abs(n.x - tx) <= 1 && Math.abs(n.z - tz) <= 1 && Math.abs(n.y - ty) <= 1) {
                end = n;
                break;
            }
            double base = cost.get(n);
            for (int[] d : DIRS) {
                int nx = n.x + d[0], nz = n.z + d[1];
                if (!w.isChunkLoaded(nx >> 4, nz >> 4)) continue;
                // No cutting corners: a diagonal needs both sides open.
                if (d[0] != 0 && d[1] != 0 && (!open(w, n.x + d[0], n.y, n.z) || !open(w, n.x, n.y, n.z + d[1]))) continue;
                Integer ny = landing(w, nx, n.y, nz);
                if (ny == null) continue;
                Node next = new Node(nx, ny, nz);
                double step = (d[0] != 0 && d[1] != 0 ? 1.414 : 1) + (ny > n.y ? 0.5 : 0);
                double c = base + step;
                if (c >= cost.getOrDefault(next, Double.MAX_VALUE)) continue;
                cost.put(next, c);
                came.put(next, n);
                double h = Math.sqrt((double) (nx - tx) * (nx - tx) + (double) (nz - tz) * (nz - tz) + (double) (ny - ty) * (ny - ty));
                open.add(new Object[]{c + h, next});
            }
        }
        if (end == null) return null;
        List<Location> points = new ArrayList<>();
        for (Node n = end; n != null; n = came.get(n)) points.add(new Location(w, n.x, n.y, n.z));
        Collections.reverse(points);
        return points;
    }

    /** Where an animal stepping into column x,z from height y stands: up one, level, or down up to three. */
    private static Integer landing(World w, int x, int y, int z) {
        if (standable(w, x, y + 1, z) && open(w, x, y + 2, z)) return y + 1;
        for (int dy = 0; dy >= -3; dy--) {
            if (!open(w, x, y + dy, z)) return null;
            if (standable(w, x, y + dy, z)) return y + dy;
        }
        return null;
    }

    private static boolean standable(World w, int x, int y, int z) {
        Block below = w.getBlockAt(x, y - 1, z);
        return open(w, x, y, z) && open(w, x, y + 1, z) && !Terrain.clear(below) && !below.isLiquid();
    }

    private static boolean open(World w, int x, int y, int z) {
        return Terrain.clear(w.getBlockAt(x, y, z));
    }
}
