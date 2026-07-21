package dev.botalive.core.commands;

import dev.botalive.api.command.BotSubcommand;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ověřuje registr cizích podpříkazů {@code /botalive}: registraci,
 * case-insensitivitu, odregistraci a ochranu vyhrazených/duplicitních jmen.
 */
class SubcommandRegistryImplTest {

    private static BotSubcommand named(String name) {
        return new BotSubcommand() {
            @Override
            public String name() {
                return name;
            }

            @Override
            public void execute(CommandSender sender, String[] args) {
                // v testu se nevolá
            }
        };
    }

    private SubcommandRegistryImpl registry() {
        return new SubcommandRegistryImpl(Set.of("create", "goal", "remove"));
    }

    @Test
    void registersAndLooksUpCaseInsensitively() {
        SubcommandRegistryImpl registry = registry();
        registry.register(named("Greet"));

        assertNotNull(registry.byName("greet"));
        assertNotNull(registry.byName("GREET"));
        assertNull(registry.byName("nope"));
        assertEquals(List.of("greet"), registry.registeredNames());
    }

    @Test
    void rejectsReservedNames() {
        SubcommandRegistryImpl registry = registry();
        assertThrows(IllegalArgumentException.class, () -> registry.register(named("create")));
        assertThrows(IllegalArgumentException.class, () -> registry.register(named("GOAL")));
    }

    @Test
    void rejectsDuplicates() {
        SubcommandRegistryImpl registry = registry();
        registry.register(named("greet"));
        assertThrows(IllegalArgumentException.class, () -> registry.register(named("greet")));
        assertThrows(IllegalArgumentException.class, () -> registry.register(named("GREET")));
    }

    @Test
    void rejectsBlankAndSpacedNames() {
        SubcommandRegistryImpl registry = registry();
        assertThrows(IllegalArgumentException.class, () -> registry.register(named("")));
        assertThrows(IllegalArgumentException.class, () -> registry.register(named("  ")));
        assertThrows(IllegalArgumentException.class, () -> registry.register(named("two words")));
    }

    @Test
    void unregisterRemoves() {
        SubcommandRegistryImpl registry = registry();
        registry.register(named("greet"));
        assertTrue(registry.unregister("GREET"));
        assertNull(registry.byName("greet"));
        assertFalse(registry.unregister("greet"));
        assertFalse(registry.unregister(null));
    }
}
