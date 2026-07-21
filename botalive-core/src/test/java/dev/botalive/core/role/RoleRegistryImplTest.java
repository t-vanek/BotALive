package dev.botalive.core.role;

import dev.botalive.api.role.BotRole;
import dev.botalive.api.role.RoleDefinition;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ověřuje registr profesí: seedování vestavěných rolí (a shodu jejich vah
 * s {@link RoleProfiles}), přidání/odebrání cizích rolí, ochranu vestavěných
 * a rozhraní vah pro mozek.
 */
class RoleRegistryImplTest {

    @Test
    void seedsBuiltInRolesWithProfileWeights() {
        RoleRegistryImpl registry = new RoleRegistryImpl();

        assertTrue(registry.byId("builder").isPresent());
        assertTrue(registry.isBuiltIn("BUILDER"));
        // Váhy vestavěných rolí musí odpovídat RoleProfiles (žádná divergence).
        assertEquals(3.0, registry.weight("builder", "house"), 1e-9);
        assertEquals(2.5, registry.weight("miner", "mine"), 1e-9);
        // NONE není v registru; univerzál i cíl mimo profil = 1.0.
        assertEquals(1.0, registry.weight("none", "house"), 1e-9);
        assertEquals(1.0, registry.weight(null, "house"), 1e-9);
        assertEquals(1.0, registry.weight("builder", "fishing-with-dynamite"), 1e-9);
    }

    @Test
    void registersCustomRoleAndWeighsIt() {
        RoleRegistryImpl registry = new RoleRegistryImpl();
        registry.register(new RoleDefinition("Necromancer", "nekromant",
                Map.of("hunt", 2.0, "my-plugin-goal", 3.0)));

        assertTrue(registry.byId("necromancer").isPresent());
        assertFalse(registry.isBuiltIn("necromancer"));
        assertEquals(3.0, registry.weight("NECROMANCER", "my-plugin-goal"), 1e-9);
        assertEquals(1.0, registry.weight("necromancer", "mine"), 1e-9);
    }

    @Test
    void rejectsDuplicateAndBuiltInIds() {
        RoleRegistryImpl registry = new RoleRegistryImpl();
        // Vestavěné id nelze přebít.
        assertThrows(IllegalArgumentException.class, () -> registry.register(
                new RoleDefinition("builder", "můj stavitel", Map.of())));
        registry.register(new RoleDefinition("ranger", "hraničář", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> registry.register(
                new RoleDefinition("RANGER", "jiný", Map.of())));
    }

    @Test
    void unregisterProtectsBuiltIns() {
        RoleRegistryImpl registry = new RoleRegistryImpl();
        registry.register(new RoleDefinition("ranger", "hraničář", Map.of()));

        assertTrue(registry.unregister("RANGER"));
        assertTrue(registry.byId("ranger").isEmpty());
        assertFalse(registry.unregister("ranger"));
        // Vestavěné role odebrat nelze.
        assertFalse(registry.unregister("builder"));
        assertTrue(registry.byId("builder").isPresent());
    }

    @Test
    void allContainsEveryBuiltInRole() {
        RoleRegistryImpl registry = new RoleRegistryImpl();
        long builtInCount = java.util.Arrays.stream(BotRole.values())
                .filter(r -> r != BotRole.NONE).count();
        assertEquals(builtInCount, registry.all().size());
    }
}
