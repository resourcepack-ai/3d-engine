package ai.resourcepack.engine3d.runtime.platform;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.event.entity.EntityDismountEvent;
import org.bukkit.plugin.Plugin;

import java.util.List;
import java.util.function.BiPredicate;
import java.util.function.Consumer;

/**
 * What only Paper can do, and what Spigot does instead.
 *
 * The plugin compiles against Spigot's API, so nothing Paper-only can creep
 * into it unnoticed. Paper's half, {@code PaperPlatform}, is in this same
 * package under {@code src/paper}, compiled against Paper's API and loaded
 * only when the server is Paper; on Spigot {@link SpigotPlatform} stands in.
 * {@link Blocks} stands in for one more thing Paper has and Spigot lacks.
 */
public interface Platform {

    Platform CURRENT = load();

    private static Platform load() {
        try {
            Class.forName("com.destroystokyo.paper.entity.ai.Goal");
            return (Platform) Class.forName("ai.resourcepack.engine3d.runtime.platform.PaperPlatform").getConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError e) {
            return new SpigotPlatform();
        }
    }

    String name();

    /** What a brain's legs need to know each tick. */
    interface Legs {
        /** True while the steering has it (or it stands still): the game's own pathfinder stays off. */
        boolean steering();

        Location goal();

        double speed();
    }

    /** Takes the brain's own AI away and gives it {@code legs}. */
    void prepareBrain(Mob brain, Plugin plugin, Legs legs);

    /** The corners of a walkable path to {@code target}, or null. */
    List<Location> path(Mob brain, Location target);

    void stopPathfinding(Mob brain);

    /**
     * Calls {@code attacked} as a player swings at an entity, before any
     * damage; a true answer cancels the swing. Where the platform has no such
     * event, false, and the damage event is the hook instead.
     */
    boolean listenForSwings(Plugin plugin, BiPredicate<Player, Entity> attacked);

    /** Moves an entity that has riders, keeping them on. */
    void moveWithRiders(Entity entity, Location to);

    /**
     * True while {@link #moveWithRiders} has lifted a seat's riders off to put
     * them back, so the dismounts that causes are not taken for getting out.
     * Only a platform that cannot move a ridden entity outright ever does.
     */
    default boolean reseating() {
        return false;
    }

    /** Whether this dismount may be cancelled (on Spigot, every one may). */
    boolean cancellable(EntityDismountEvent event);

    /** A text display showing {@code text} in {@code font}, in colour {@code rgb}; {@code before} runs as it is made. */
    TextDisplay spawnText(Location at, String text, String font, int rgb, Consumer<TextDisplay> before);
}
