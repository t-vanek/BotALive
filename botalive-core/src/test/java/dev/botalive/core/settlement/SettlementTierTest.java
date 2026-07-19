package dev.botalive.core.settlement;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Odvození stupně sídla ze substance (dostavěné domy, infrastruktura).
 */
class SettlementTierTest {

    @Test
    void osadaPodCtyrmiDomy() {
        assertEquals(SettlementTier.OSADA, SettlementTier.of(0, false, false));
        assertEquals(SettlementTier.OSADA, SettlementTier.of(3, true, false));
    }

    @Test
    void vesniceChceDomyIStudnu() {
        assertEquals(SettlementTier.VESNICE, SettlementTier.of(4, true, false));
        // Bez studny zůstává i osm chalup osadou – substance, ne dekret.
        assertEquals(SettlementTier.OSADA, SettlementTier.of(8, false, false));
    }

    @Test
    void samotnyPocetDomuMestoNedela() {
        // Město vyžaduje městskou infrastrukturu (sýpka + tržiště, fáze B2/D).
        assertEquals(SettlementTier.VESNICE, SettlementTier.of(8, true, false));
        assertEquals(SettlementTier.MESTO, SettlementTier.of(8, true, true));
    }
}
