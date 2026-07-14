package dev.botalive.core.world;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Testy klientského odhadu doby těžby (vanilla vzorec).
 */
class BreakTimeEstimatorTest {

    @Test
    void kamenDrevenymKrumpacem() {
        // hardness 1.5, rychlost 2, sklizitelné: 2/1.5/30 → 22.5 → 23 ticků (1.15 s).
        assertEquals(23, BreakTimeEstimator.estimateTicks(1.5, true, 1, true));
    }

    @Test
    void kamenRukou() {
        // Nesklizitelné (dělitel 100): 1/1.5/100 → 150 ticků (7.5 s).
        assertEquals(150, BreakTimeEstimator.estimateTicks(1.5, false, 0, false));
    }

    @Test
    void hlinaRukou() {
        // Hlína nevyžaduje nástroj: 1/0.5/30 → 15 ticků (0.75 s).
        assertEquals(15, BreakTimeEstimator.estimateTicks(0.5, false, 0, true));
    }

    @Test
    void obsidianDiamantovymKrumpacem() {
        // 8/50/30 → 187.5 → 188 ticků (9.4 s).
        assertEquals(188, BreakTimeEstimator.estimateTicks(50, true, 5, true));
    }

    @Test
    void krajniPripady() {
        assertEquals(6000, BreakTimeEstimator.estimateTicks(-1, true, 6, true));
        assertEquals(1, BreakTimeEstimator.estimateTicks(0, false, 0, true));
        // Zlato je nejrychlejší: 12/0.5/30 = 0.8 → 2 ticky.
        assertEquals(2, BreakTimeEstimator.estimateTicks(0.5, true, 2, true));
    }
}
