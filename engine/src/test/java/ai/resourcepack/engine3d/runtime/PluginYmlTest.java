package ai.resourcepack.engine3d.runtime;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * plugin.yml has to agree with the nodes {@code /3d} checks. A subcommand whose
 * node is not declared defaults to nobody but ops, with no description to say
 * what it is; one missing from 3dengine.admin is refused to the owner who
 * granted the parent and reasonably expected everything.
 */
class PluginYmlTest {

    /** Nodes that are not a /3d subcommand, and so are not derived from one. */
    private static final Set<String> NOT_SUBCOMMANDS = Set.of("3dengine.admin", "3dengine.place", "3dengine.ride", "3dengine.update", "3dengine.give.others");

    @SuppressWarnings("unchecked")
    private static Map<String, Object> pluginYml() throws IOException {
        return new Yaml().load(Files.readString(Path.of("src", "main", "resources", "plugin.yml")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Map<String, Object>> permissions() throws IOException {
        return (Map<String, Map<String, Object>>) pluginYml().get("permissions");
    }

    @Test
    void everySubcommandHasADeclaredNodeAndAUsage() throws IOException {
        Map<String, Map<String, Object>> perms = permissions();
        for (String sub : CommandPermissions.SUBS) {
            String node = CommandPermissions.permissionFor(sub);
            assertTrue(perms.containsKey(node), "/3d " + sub + " checks " + node + ", which plugin.yml does not declare");
            assertTrue(perms.get(node).get("description") != null, node + " has no description");
            assertTrue(CommandPermissions.USAGE.containsKey(sub), "/3d " + sub + " has no help text");
        }
        assertEquals(Set.copyOf(CommandPermissions.SUBS), CommandPermissions.USAGE.keySet());
        assertTrue(perms.containsKey(CommandPermissions.othersPermissionFor("give")));
        assertEquals("op", String.valueOf(perms.get(CommandPermissions.othersPermissionFor("give")).get("default")));
    }

    @Test
    void everyNodeGatesSomething() throws IOException {
        for (String node : permissions().keySet()) {
            if (NOT_SUBCOMMANDS.contains(node)) continue;
            assertTrue(CommandPermissions.SUBS.stream().anyMatch(s -> CommandPermissions.permissionFor(s).equals(node)), node + " gates nothing");
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void adminGrantsEveryCommandAndPlacing() throws IOException {
        Map<String, Map<String, Object>> perms = permissions();
        Map<String, Object> children = (Map<String, Object>) perms.get("3dengine.admin").get("children");
        assertEquals("op", String.valueOf(perms.get("3dengine.admin").get("default")));
        for (String node : perms.keySet()) {
            if (node.equals("3dengine.admin") || node.equals("3dengine.ride") || node.equals("3dengine.update")) continue;
            assertEquals(Boolean.TRUE, children.get(node), "3dengine.admin does not grant " + node);
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void theCommandItselfCarriesNoPermission() throws IOException {
        Map<String, Object> command = ((Map<String, Map<String, Object>>) pluginYml().get("commands")).get("3d");
        assertFalse(command.containsKey("permission"), "/3d gates itself; each subcommand does that");
    }
}
