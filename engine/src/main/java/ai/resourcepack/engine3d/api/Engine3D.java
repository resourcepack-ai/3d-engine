package ai.resourcepack.engine3d.api;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.EntityType;

import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;

/**
 * 3D Engine: 3D models from files, drawn in the game by core shaders.
 *
 * Drop a .glb, .gltf or .obj into {@code plugins/3DEngine/models/} and it is
 * baked into the resource pack the engine serves; then spawn it anywhere, play
 * its animations, hang things on its bones and walk it about.
 *
 * <pre>{@code
 * Engine3D engine = Engine3D.get();
 * engine.whenReady(() -> {
 *     ModelInstance dog = engine.spawn("labrador", player.getLocation());
 *     Mover legs = engine.mover(dog, EntityType.WOLF);
 *     legs.follow(player);
 * });
 * }</pre>
 */
public interface Engine3D {

    static Engine3D get() {
        Engine3D e = Holder.engine;
        if (e == null) throw new IllegalStateException("3D Engine is not enabled");
        return e;
    }

    /** True once every model is baked and the pack is being served. */
    boolean ready();

    /**
     * Runs {@code task} on the main thread once the engine is ready (now, if it
     * is), and again after every {@code /3d reload}, so a plugin can respawn
     * what it drew against the new models.
     */
    void whenReady(Runnable task);

    Optional<Model> model(String name);

    Collection<Model> models();

    /** A model in the world that lasts until removed or the server stops. */
    ModelInstance spawn(String model, Location at);

    /** A model placed in the world for good: saved with its chunk, as {@code /3d place} does. */
    ModelInstance place(String model, Location at);

    /**
     * Legs for a model: an invisible mob of type {@code brain} (it decides how
     * big a gap the model fits through) that the engine steers, with the model
     * drawn on it and its walk, run and turn animations played to match.
     */
    Mover mover(ModelInstance body, EntityType brain);

    /**
     * An item that shows {@code model} (in {@code state}, or its default) in
     * the inventory, in hand and on the ground. It is paper; change its type
     * with {@link org.bukkit.inventory.ItemStack#withType} to make it a
     * snowball, a sword or anything else that keeps its look.
     */
    org.bukkit.inventory.ItemStack item(String model, String state);

    /** The shader effects loaded from {@code plugins/3DEngine/effects/}. */
    java.util.Set<String> effects();

    /** Puts effect {@code name} in the world, centred at {@code at}, {@code size} blocks across. */
    EffectInstance effect(String name, Location at, double size);

    /** {@link #effect(String, Location, double)}, seen by everybody but {@code except}. */
    EffectInstance effect(String name, Location at, double size, org.bukkit.entity.Player except);

    /**
     * Effect {@code name} seen only by {@code viewer}, and drawn the way their
     * held item is: Minecraft draws the hand at a field of view of 70 whatever
     * the player has set, so anything in the world that should meet what they
     * hold (a flame leaving a nozzle, a muzzle flash) misses it at any other
     * setting. This one doesn't. Place it as if their field of view were 70;
     * it is meant for first person, and pairs with an ordinary effect hidden
     * from {@code viewer} that everybody else sees.
     */
    EffectInstance viewModelEffect(String name, org.bukkit.entity.Player viewer, Location at, double size);

    /** Launches effect {@code name} from {@code at} at {@code velocity} (blocks per tick), flown by the engine. */
    Particle particle(String name, Location at, org.bukkit.util.Vector velocity, Particle.Options options);

    Collection<ModelInstance> instances();

    /** The instance drawn by {@code entity} (its display, or a mover's brain). */
    Optional<ModelInstance> instance(Entity entity);

    /** Where model files go. Another plugin may copy its own models in before the first tick. */
    Path modelsFolder();

    /**
     * Files here go into the pack as they are, at the same path ({@code
     * assets/<namespace>/sounds.json}, a font, a texture), on the next load.
     * For sounds, fonts and item models a plugin needs beside its 3D models:
     * use a namespace of your own. A file at a path the engine already writes
     * (its shaders, its models) is left out.
     */
    Path packFolder();

    /** Internal: the running engine. */
    final class Holder {
        static volatile Engine3D engine;

        private Holder() {
        }

        public static void set(Engine3D e) {
            engine = e;
        }
    }
}
