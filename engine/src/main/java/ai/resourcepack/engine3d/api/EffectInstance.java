package ai.resourcepack.engine3d.api;

import org.bukkit.Location;
import org.bukkit.entity.TextDisplay;

/**
 * One shader effect in the world: a fireball, a puff of flame, anything drawn
 * by a GLSL {@code object()} in {@code plugins/3DEngine/effects/}. Its
 * position is its centre; its size is its diameter in blocks.
 */
public interface EffectInstance {

    String effect();

    TextDisplay display();

    Location location();

    /** Moves it there; the client slides it over {@code ticks} (0 = jump). */
    void teleport(Location at, int ticks);

    double size();

    /** Resizes it; the client eases to the new size over {@code ticks}. */
    void size(double blocks, int ticks);

    /** The effect's own 0..1 parameter ({@code rpw_param} in its GLSL): a flame's age, say. */
    void param(double value);

    boolean valid();

    void remove();
}
