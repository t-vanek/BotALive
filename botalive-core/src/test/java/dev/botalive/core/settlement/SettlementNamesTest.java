package dev.botalive.core.settlement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy generátoru jmen vesnic.
 */
class SettlementNamesTest {

    @Test
    void jmenaJsouDeterministicka() {
        String first = SettlementNames.generate("Pepa", 42L, 0);
        String second = SettlementNames.generate("Pepa", 42L, 0);
        assertEquals(first, second);
    }

    @Test
    void ruznePokusyDavajiRuznaJmena() {
        boolean anyDifferent = false;
        String base = SettlementNames.generate("Pepa", 7L, 0);
        for (int attempt = 1; attempt < 8; attempt++) {
            if (!base.equals(SettlementNames.generate("Pepa", 7L, attempt))) {
                anyDifferent = true;
                break;
            }
        }
        assertTrue(anyDifferent, "kolize jmen musí jít vyřešit dalším pokusem");
    }

    @Test
    void jmenaNejsouPrazdnaAniProNickyBezPismen() {
        for (long seed = 0; seed < 50; seed++) {
            String name = SettlementNames.generate("x_123_x", seed, 0);
            assertFalse(name.isBlank());
            String noFounder = SettlementNames.generate(null, seed, 0);
            assertFalse(noFounder.isBlank());
        }
    }

    @Test
    void jadroNickuSeCteZPismen() {
        assertEquals("Reaper", SettlementNames.readableCore("xX_Reaper_Xx"));
        assertEquals("Shadow", SettlementNames.readableCore("Shadow42"));
        assertEquals("", SettlementNames.readableCore("12345"));
    }
}
