package ai.resourcepack.engine3d.api;

import java.util.Map;
import java.util.Set;

/** A baked model: what it can do and how big it is. */
public interface Model {

    String name();

    /** Its looping states (idle, walk, ...), each an animation the model can settle in. */
    Set<String> states();

    String defaultState();

    /** Whether there is a way (directly or through other states) from one state to another. */
    boolean reachable(String from, String to);

    /** Its attachment points (a mouth, a hand), followed through every animation. */
    Set<String> points();

    /** Height at rest, in blocks, at scale 1. */
    double height();

    /** The furthest any part reaches from its centre line, in blocks, at scale 1. */
    double radius();

    /** The {@code locomotion:} block of its spec, as written: state names and speeds for a {@link Mover}. */
    Map<String, Object> locomotion();

    /** Its seats, in the order a right-click fills them. */
    java.util.List<String> seats();

    /** Whether it turns to look at players (its head alone, or the whole model). */
    boolean looks();

    /** Whether a rider in its driving seat steers it. */
    boolean drivable();

    /** Its spec file's other keys, for plugins that keep their own settings beside a model. */
    Map<String, Object> settings();
}
