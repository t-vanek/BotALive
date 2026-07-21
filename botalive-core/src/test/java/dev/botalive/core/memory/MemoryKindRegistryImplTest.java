package dev.botalive.core.memory;

import dev.botalive.api.memory.MemoryKindDefinition;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ověřuje registr kategorií vzpomínek: registraci, ochranu vyhrazených
 * (vestavěných) i duplicitních id, odregistraci a vyhledávání.
 */
class MemoryKindRegistryImplTest {

    @Test
    void registersAndLooksUp() {
        MemoryKindRegistryImpl registry = new MemoryKindRegistryImpl();
        registry.register(new MemoryKindDefinition("myplugin:shrine", 0.05, 0.1));

        assertTrue(registry.byId("myplugin:shrine").isPresent());
        assertEquals(0.05, registry.byId("myplugin:shrine").get().dailyDecay(), 1e-9);
        assertTrue(registry.byId("nope").isEmpty());
        assertEquals(1, registry.all().size());
    }

    @Test
    void rejectsReservedBuiltInNames() {
        MemoryKindRegistryImpl registry = new MemoryKindRegistryImpl();
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(new MemoryKindDefinition("CHEST", 0, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(new MemoryKindDefinition("friend", 0, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(new MemoryKindDefinition("PLUGIN", 0, 0)));
    }

    @Test
    void rejectsDuplicates() {
        MemoryKindRegistryImpl registry = new MemoryKindRegistryImpl();
        registry.register(new MemoryKindDefinition("myplugin:x", 0, 0));
        assertThrows(IllegalArgumentException.class,
                () -> registry.register(new MemoryKindDefinition("myplugin:x", 0, 0)));
    }

    @Test
    void unregisterRemoves() {
        MemoryKindRegistryImpl registry = new MemoryKindRegistryImpl();
        registry.register(new MemoryKindDefinition("myplugin:x", 0, 0));
        assertTrue(registry.unregister("myplugin:x"));
        assertTrue(registry.byId("myplugin:x").isEmpty());
        assertFalse(registry.unregister("myplugin:x"));
    }
}
