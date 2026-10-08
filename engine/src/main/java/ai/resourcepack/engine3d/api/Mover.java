package ai.resourcepack.engine3d.api;

import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Mob;

/**
 * Legs for a model. An invisible mob (the brain) walks the world; the model is
 * drawn on it, turned at an animal's rate (shuffling round when it turns on the
 * spot) and played walking, running or standing to match its speed.
 *
 * The brain is steered in straight lines and smooth curves wherever the ground
 * allows, rather than along the game's eight-way paths, and falls back to the
 * pathfinder round obstacles.
 */
public interface Mover {

    ModelInstance body();

    Mob brain();

    /** Keeps within reach of {@code target}: walks after it beyond {@code start} blocks, runs beyond {@code run}, stops inside {@code stop}. */
    void follow(Entity target, double start, double stop, double run);

    default void follow(Entity target) {
        follow(target, 6, 2.5, 9);
    }

    /** Teleports to {@code target} when it gets further than this (0 = never). Default 24. */
    void leash(double blocks);

    void unfollow();

    /**
     * Goes to {@code to} at {@code speed} blocks per tick, stopping within
     * {@code arrive}. A go-to wins over following until it arrives or is
     * cleared with {@link #stop}.
     */
    void goTo(Location to, double speed, double arrive);

    /** Whether a {@link #goTo} is under way. */
    boolean going();

    /** Stops any go-to (following carries on). */
    void stop();

    /** Turns to face {@code at} (shuffling round if it has to). */
    void face(Location at);

    /** Smoothed ground speed, blocks per tick. */
    double speed();

    /**
     * Plays {@code state} instead of the walk/run/stand it would choose, until
     * cleared with null. The model still moves if told to.
     */
    void hold(String state);

    /** The walk/run/turn/idle state it would play now. */
    String locomotion();

    /** Whether it moves at all: a frozen mover stands where it is (sitting, say). */
    void freeze(boolean frozen);

    void teleport(Location at);

    boolean valid();

    /** Removes the brain and the model. */
    void remove();
}
