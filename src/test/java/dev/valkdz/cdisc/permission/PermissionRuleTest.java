package dev.valkdz.cdisc.permission;

import org.bukkit.command.CommandSender;
import org.bukkit.permissions.PermissionDefault;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PermissionRuleTest {

    private static CommandSender sender(boolean op, String... permissions) {
        Set<String> held = Set.of(permissions);

        return (CommandSender) Proxy.newProxyInstance(
                PermissionRuleTest.class.getClassLoader(),
                new Class<?>[]{CommandSender.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "hasPermission" -> held.contains(String.valueOf(args[0]));
                    case "isOp" -> op;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> null;
                });
    }

    @Nested
    @DisplayName("a rule that names permissions")
    class Named {

        @Test
        @DisplayName("any one right in the list is enough")
        void anyOfTheList() {
            PermissionRule rule = PermissionRule.parse(List.of("group.admin", "group.moderator"));

            assertTrue(rule.test(sender(false, "group.moderator")));
            assertTrue(rule.test(sender(false, "group.admin")));
            assertFalse(rule.test(sender(false, "group.builder")));
        }

        @Test
        @DisplayName("a single right is asked for by name")
        void singleRight() {
            PermissionRule rule = PermissionRule.parse("group.vip");

            assertTrue(rule.test(sender(false, "group.vip")));
            assertFalse(rule.test(sender(false)));
        }

        @Test
        @DisplayName("a leading bang asks for the absence of one")
        void negated() {
            PermissionRule rule = PermissionRule.parse("!group.muted");

            assertFalse(rule.test(sender(false, "group.muted")));
            assertTrue(rule.test(sender(false)));
        }

        @Test
        @DisplayName("named rights leave the node itself to be granted by hand")
        void noDefaultOfItsOwn() {
            assertNull(PermissionRule.parse("group.vip").asDefault());
            assertNull(PermissionRule.parse(List.of("group.vip", "op")).asDefault());
        }
    }

    @Nested
    @DisplayName("a rule that is one word")
    class Words {

        @Test
        @DisplayName("true, false and op become the default of the node")
        void plainWords() {
            assertEquals(PermissionDefault.TRUE, PermissionRule.parse(true).asDefault());
            assertEquals(PermissionDefault.FALSE, PermissionRule.parse(false).asDefault());
            assertEquals(PermissionDefault.OP, PermissionRule.parse("op").asDefault());
            assertEquals(PermissionDefault.NOT_OP, PermissionRule.parse("notop").asDefault());
        }

        @Test
        @DisplayName("a negated word is the word turned around")
        void negatedWords() {
            assertEquals(PermissionDefault.NOT_OP, PermissionRule.parse("!op").asDefault());
            assertEquals(PermissionDefault.FALSE, PermissionRule.parse("!true").asDefault());
        }

        @Test
        @DisplayName("op asks the server, not the permission list")
        void opIsAsked() {
            PermissionRule rule = PermissionRule.parse("op");

            assertTrue(rule.test(sender(true)));
            assertFalse(rule.test(sender(false)));
        }

        @Test
        @DisplayName("an empty value is no rule at all, so the code default stands")
        void emptyRule() {
            assertTrue(PermissionRule.parse(null).isEmpty());
            assertTrue(PermissionRule.parse("  ").isEmpty());
            assertTrue(PermissionRule.parse(List.of()).isEmpty());
        }
    }

    @Nested
    @DisplayName("the shipped permissions.yml")
    class Shipped {

        @SuppressWarnings("unchecked")
        private static Map<String, Object> actions() throws IOException {
            try (Reader reader = Files.newBufferedReader(
                    Path.of("src/main/resources/permissions.yml"), StandardCharsets.UTF_8)) {
                Map<String, Object> root = new Yaml().load(reader);
                return (Map<String, Object>) root.get("actions");
            }
        }

        @SuppressWarnings("unchecked")
        private static Object at(Map<String, Object> tree, String path) {
            Object here = tree;
            for (String step : path.split("\\.")) {
                if (!(here instanceof Map<?, ?> map)) return null;
                here = ((Map<String, Object>) map).get(step);
            }
            return here;
        }

        @Test
        @DisplayName("carries a rule for every action the code knows")
        void everyActionIsDocumented() throws IOException {
            Map<String, Object> actions = actions();
            List<String> missing = new ArrayList<>();

            for (Action action : Action.values()) {
                if (at(actions, action.path()) == null) missing.add(action.path());
            }
            assertTrue(missing.isEmpty(), "no rule shipped for: " + missing);
        }

        @Test
        @DisplayName("ships nothing the code does not read")
        void nothingExtra() throws IOException {
            List<String> unknown = new ArrayList<>();
            collect(actions(), "", unknown);

            assertTrue(unknown.isEmpty(), "no action in the code for: " + unknown);
        }

        @SuppressWarnings("unchecked")
        private static void collect(Map<String, Object> tree, String prefix, List<String> unknown) {
            for (Map.Entry<String, Object> entry : tree.entrySet()) {
                String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();

                if (entry.getValue() instanceof Map<?, ?> deeper) {
                    collect((Map<String, Object>) deeper, path, unknown);
                } else if (Action.byPath(path) == null) {
                    unknown.add(path);
                }
            }
        }

        @Test
        @DisplayName("the node of an action is its path under cdisc")
        void nodesFollowThePath() {
            assertEquals("cdisc.player.pause", Action.PLAYER_PAUSE.node());
            assertEquals("cdisc.queue.add", Action.QUEUE_ADD.node());
            assertEquals("player", Action.PLAYER_PAUSE.group());
        }
    }
}
