package dev.botalive.core.tasks;

import dev.botalive.api.bot.BotControl;
import dev.botalive.api.task.BotTask;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ověřuje registr taktických tasků: registraci, case-insensitivitu, čerstvé
 * instance z továrny, odregistraci a ochranu proti duplicitám/prázdným id.
 */
class TaskRegistryImplTest {

    /** Task, který hned skončí – v testu se netiká proti reálnému botovi. */
    private static BotTask noop() {
        return new BotTask() {
            @Override
            public boolean tick(BotControl control) {
                return true;
            }
        };
    }

    @Test
    void registersAndCreatesFreshInstances() {
        TaskRegistryImpl registry = new TaskRegistryImpl();
        registry.register("Dig", TaskRegistryImplTest::noop);

        assertEquals(List.of("dig"), registry.registeredIds());
        Optional<BotTask> a = registry.create("DIG");
        Optional<BotTask> b = registry.create("dig");
        assertTrue(a.isPresent());
        assertTrue(b.isPresent());
        assertNotSame(a.get(), b.get(), "každé create vrací novou instanci");
        assertTrue(registry.create("nope").isEmpty());
    }

    @Test
    void rejectsDuplicatesAndBlankIds() {
        TaskRegistryImpl registry = new TaskRegistryImpl();
        registry.register("dig", TaskRegistryImplTest::noop);
        assertThrows(IllegalArgumentException.class,
                () -> registry.register("DIG", TaskRegistryImplTest::noop));
        assertThrows(IllegalArgumentException.class,
                () -> registry.register("  ", TaskRegistryImplTest::noop));
    }

    @Test
    void unregisterRemoves() {
        TaskRegistryImpl registry = new TaskRegistryImpl();
        registry.register("dig", TaskRegistryImplTest::noop);
        assertTrue(registry.unregister("DIG"));
        assertTrue(registry.create("dig").isEmpty());
        assertFalse(registry.unregister("dig"));
    }
}
