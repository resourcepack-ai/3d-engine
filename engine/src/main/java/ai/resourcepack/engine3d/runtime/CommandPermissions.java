package ai.resourcepack.engine3d.runtime;

import java.util.List;
import java.util.Map;

/**
 * Which permission each {@code /3d} subcommand needs. Kept apart from
 * {@link Commands} so it can be read without a server (the plugin.yml test
 * does).
 *
 * <p>One node per subcommand, {@code 3dengine.<subcommand>}, rather than one
 * node for the lot: whoever may resize a model is not necessarily whoever may
 * re-bake the folder. {@code 3dengine.admin} in plugin.yml grants them all for
 * a server that does not care, and nothing checks it.
 */
final class CommandPermissions {

    /** Every subcommand of {@code /3d}, in the order help and tab-completion show them. */
    static final List<String> SUBS = List.of("list", "place", "give", "remove", "scale", "rotate", "state", "sit", "info", "quality", "effect", "reload", "pack");

    /** What each subcommand takes, for the help line. */
    static final Map<String, String> USAGE = Map.ofEntries(
            Map.entry("list", "list"), Map.entry("place", "place <model> [scale]"), Map.entry("give", "give <model> [player]"),
            Map.entry("remove", "remove"), Map.entry("scale", "scale <n>"), Map.entry("rotate", "rotate <deg>"),
            Map.entry("state", "state <state>"), Map.entry("sit", "sit [seat]"), Map.entry("info", "info [model]"),
            Map.entry("quality", "quality [model] [triangles]"), Map.entry("effect", "effect <name> [size]"),
            Map.entry("reload", "reload"), Map.entry("pack", "pack"));

    private CommandPermissions() {
    }

    /**
     * What {@code /3d <sub>} needs. The one exception is {@code /3d place}:
     * {@code 3dengine.place} already meant placing a model with a model item,
     * and a server that hands those items to players must not find it now also
     * lets them put any model anywhere, so the command has
     * {@code 3dengine.place.command}.
     */
    static String permissionFor(String sub) {
        return sub.equals("place") ? "3dengine.place.command" : "3dengine." + sub;
    }

    /** What pointing {@code /3d <sub>} at another player needs, on top of {@link #permissionFor}. */
    static String othersPermissionFor(String sub) {
        return "3dengine." + sub + ".others";
    }
}
