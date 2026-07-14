package dev.botalive.core.bot;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy generátoru jmen botů.
 */
class BotNameGeneratorTest {

    @Test
    void generujeValidniMinecraftJmena() {
        BotNameGenerator generator = new BotNameGenerator(List.of());
        for (int i = 0; i < 100; i++) {
            String name = generator.next(n -> false);
            assertTrue(name.matches("[A-Za-z0-9_]{3,16}"), "neplatné jméno: " + name);
        }
    }

    @Test
    void respektujeObsazenaJmena() {
        BotNameGenerator generator = new BotNameGenerator(List.of("Pepa"));
        Set<String> taken = new HashSet<>();
        for (int i = 0; i < 50; i++) {
            String name = generator.next(taken::contains);
            assertFalse(taken.contains(name), "vygenerováno obsazené jméno: " + name);
            taken.add(name);
        }
    }

    @Test
    void pouzivaVlastniPool() {
        BotNameGenerator generator = new BotNameGenerator(List.of("Franta"));
        String name = generator.next(n -> false);
        assertTrue(name.startsWith("Franta"), "jméno má vycházet z poolu: " + name);
    }
}
