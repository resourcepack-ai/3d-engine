package ai.resourcepack.engine3d.runtime.movement;

import org.bukkit.Location;
import org.bukkit.block.Block;

/**
 * The ground as an animal walking on it sees it: what it walks through, how
 * high a block stands, and whether it can run straight from one place to
 * another. The steering reads it, and so does Spigot's own path search.
 */
public final class Terrain {

    private Terrain() {
    }

    /** Something an animal walks through or straight over (air, grass, a carpet, a slab). */
    public static boolean clear(Block b) {
        return !b.isLiquid() && (b.isPassable() || height(b) <= 0.6);
    }

    /** How high the top of a block's collision shape is, in blocks: 0 for none. */
    public static double height(Block b) {
        double top = 0;
        for (var box : b.getCollisionShape().getBoundingBoxes()) top = Math.max(top, box.getMaxY());
        return top;
    }

    /**
     * Whether it can run straight from {@code from} to {@code to}: following
     * the ground, up single steps, down drops of up to three, no water, and
     * with room for its shoulders either side.
     */
    static boolean walkable(Location from, Location to) {
        var world = from.getWorld();
        double dx = to.getX() - from.getX(), dz = to.getZ() - from.getZ();
        double len = Math.hypot(dx, dz);
        if (len < 0.01) return true;
        double ux = dx / len, uz = dz / len;
        int y = from.getBlockY();
        int steps = (int) Math.ceil(len / 0.4);
        for (int i = 1; i <= steps; i++) {
            double t = Math.min(len, i * 0.4);
            double x = from.getX() + ux * t, z = from.getZ() + uz * t;
            int bx = (int) Math.floor(x), bz = (int) Math.floor(z);
            if (!world.isChunkLoaded(bx >> 4, bz >> 4)) return false;
            Block b = world.getBlockAt(bx, y, bz);
            if (!clear(b)) {
                if (height(b) > 1.0 || !clear(b.getRelative(0, 1, 0)) || !clear(b.getRelative(0, 2, 0))) return false;
                y++;
            } else {
                int drop = 0;
                while (drop < 4 && clear(world.getBlockAt(bx, y - 1, bz))) {
                    y--;
                    drop++;
                }
                if (drop == 4) return false;
            }
            if (world.getBlockAt(bx, y - 1, bz).isLiquid()) return false;
            for (double s : new double[]{-0.3, 0.3}) {
                Block side = world.getBlockAt((int) Math.floor(x - uz * s), y, (int) Math.floor(z + ux * s));
                if (!clear(side) && !clear(side.getRelative(0, 1, 0))) return false;
            }
        }
        return true;
    }
}
