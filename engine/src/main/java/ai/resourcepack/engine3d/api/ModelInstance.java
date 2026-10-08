package ai.resourcepack.engine3d.api;

import org.bukkit.Location;
import org.bukkit.entity.ItemDisplay;

/**
 * One model in the world, drawn by an item display.
 *
 * Its animation is a state machine: ask for a state and it gets there through
 * the model's transitions, waiting for a sync point where the state says to, so
 * nothing ever pops. The server always knows which frame is showing, which is
 * what lets {@link #point} say where a bone is.
 */
public interface ModelInstance {

    Model model();

    ItemDisplay display();

    Location location();

    /** Moves (and turns, by the location's yaw) the model; the client slides it there over two ticks. */
    void teleport(Location at);

    /** Asks for a state; the model gets there by its transitions. */
    void state(String state);

    /** The state it is in, or "a>b" while passing from a to b. */
    String state();

    /** True when it has arrived in {@code state} and is not leaving it. */
    boolean settledIn(String state);

    /** Ticks into the current state's loop. */
    long phase();

    /**
     * Where attachment point {@code name} is in the world, as the client will
     * be drawing it {@code leadTicks} from now (match it to your own entity's
     * teleport smoothing), or null if the model has no such point.
     */
    Location point(String name, int leadTicks);

    double scale();

    void scale(double scale);

    /** Saved with its chunk, as a placed model is. */
    boolean persistent();

    boolean valid();

    void remove();
}
