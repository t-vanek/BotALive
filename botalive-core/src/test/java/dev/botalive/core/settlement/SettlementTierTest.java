package dev.botalive.core.settlement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Odvození stupně sídla ze substance (dostavěné domy, infrastruktura).
 */
class SettlementTierTest {

    @Test
    void osadaPodCtyrmiDomy() {
        assertEquals(SettlementTier.OSADA, SettlementTier.of(0, false));
        assertEquals(SettlementTier.OSADA, SettlementTier.of(3, false));
    }

    @Test
    void vesniceOdCtyrDomu() {
        assertEquals(SettlementTier.VESNICE, SettlementTier.of(4, false));
        assertEquals(SettlementTier.VESNICE, SettlementTier.of(7, false));
    }

    @Test
    void samotnyPocetDomuMestoNedela() {
        // Město vyžaduje infrastrukturu (fáze B) – 8 chalup je pořád vesnice.
        assertEquals(SettlementTier.VESNICE, SettlementTier.of(8, false));
        assertEquals(SettlementTier.MESTO, SettlementTier.of(8, true));
    }
}
