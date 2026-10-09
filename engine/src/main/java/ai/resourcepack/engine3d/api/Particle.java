package ai.resourcepack.engine3d.api;

import org.bukkit.Location;
import org.bukkit.util.Vector;

import java.util.function.Consumer;

/**
 * A shader effect the engine flies about for you: it moves by its velocity
 * every tick (slowed by drag, pulled by gravity, stopped by blocks), grows from
 * its start size to its end size, sweeps its parameter from start to end, and
 * removes itself when its life is up. Fire from a flamethrower, smoke from a
 * chimney, sparks off a grinder.
 */
public interface Particle {

    /** How a particle behaves. Every field has a sensible default; set what you need. */
    final class Options {
        /** Ticks it lives. */
        public int life = 20;
        /** Diameter in blocks at birth and at death. */
        public double startSize = 0.5, endSize = 1.5;
        /** Velocity kept each tick (1 = none lost). */
        public double drag = 0.92;
        /** Added to its vertical velocity each tick, blocks per tick per tick (positive rises). */
        public double lift = 0;
        /** Whether blocks stop it. */
        public boolean collide = true;
        /** The effect's parameter at birth and at death. */
        public double startParam = 0, endParam = 1;
        /** Called every tick it lives, after it moves (for damage, lighting fires, anything). */
        public Consumer<Particle> onTick;
        /** Called once when it hits a block, if it collides. */
        public Consumer<Particle> onHit;

        public Options life(int ticks) {
            life = ticks;
            return this;
        }

        public Options size(double start, double end) {
            startSize = start;
            endSize = end;
            return this;
        }

        public Options drag(double keep) {
            drag = keep;
            return this;
        }

        public Options lift(double perTick) {
            lift = perTick;
            return this;
        }

        public Options collide(boolean c) {
            collide = c;
            return this;
        }

        public Options param(double start, double end) {
            startParam = start;
            endParam = end;
            return this;
        }

        public Options onTick(Consumer<Particle> f) {
            onTick = f;
            return this;
        }

        public Options onHit(Consumer<Particle> f) {
            onHit = f;
            return this;
        }
    }

    EffectInstance effect();

    Location location();

    Vector velocity();

    void velocity(Vector v);

    /** Ticks lived. */
    int age();

    /** Age over life, 0..1. */
    double progress();

    /** Its current diameter in blocks. */
    double size();

    boolean alive();

    void remove();
}
