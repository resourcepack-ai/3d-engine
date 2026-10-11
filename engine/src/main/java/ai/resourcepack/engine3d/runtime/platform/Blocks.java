package ai.resourcepack.engine3d.runtime;

import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * A block's position packed into a long, in Paper's own layout (x and z in 27
 * bits each, y above them), so the keys saved in worlds before the engine ran
 * on Spigot still unpack to the same blocks.
 */
final class Blocks {

    private Blocks() {
    }

    static long key(Block b) {
        return ((long) b.getX() & 0x7FFFFFF) | (((long) b.getZ() & 0x7FFFFFF) << 27) | ((long) b.getY() << 54);
    }

    static Block at(World w, long key) {
        int x = (int) ((key << 37) >> 37);
        int y = (int) (key >> 54);
        int z = (int) ((key << 10) >> 37);
        return w.getBlockAt(x, y, z);
    }
}
