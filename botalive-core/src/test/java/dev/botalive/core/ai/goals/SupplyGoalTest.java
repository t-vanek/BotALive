package dev.botalive.core.ai.goals;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rozhodovací jádro zásobování stavby ({@link SupplyGoal#utilityFor}).
 * Tick smyčka (navigace, otevírání truhly) se testuje jen naživo; váha je
 * čistá funkce a chytá regrese pásma i podmínek.
 */
class SupplyGoalTest {

    @Test
    void mlciBezRozestaveneStavby() {
        // I s plným batohem a hotovým skladem: když se nic nestaví, nezásobovat.
        assertEquals(0.0, SupplyGoal.utilityFor(64, false, true, 1.0),
                "bez rozestavěné stavby se materiál netahá");
    }

    @Test
    void mlciBezSkladu() {
        assertEquals(0.0, SupplyGoal.utilityFor(64, true, false, 1.0),
                "bez skladu není kam ukládat");
    }

    @Test
    void mlciBezPrebytku() {
        // Přesně na prahu (32) ještě není přebytek – bloky si nechá.
        assertEquals(0.0, SupplyGoal.utilityFor(32, true, true, 1.0),
                "bez přebytku bloků se nezásobuje");
    }

    @Test
    void zasobujeSPrebytkemStavbouASkladem() {
        assertTrue(SupplyGoal.utilityFor(48, true, true, 0.5) > 0,
                "přebytek + stavba + sklad → zásobovat");
    }

    @Test
    void ochotaPomociZvedaVahu() {
        double sobec = SupplyGoal.utilityFor(48, true, true, 0.0);
        double ochotny = SupplyGoal.utilityFor(48, true, true, 1.0);
        assertTrue(ochotny > sobec, "ochota pomoci zvedá zásobování");
    }

    @Test
    void vetsiPrebytekTahneSilneji() {
        assertTrue(SupplyGoal.utilityFor(96, true, true, 0.5)
                        > SupplyGoal.utilityFor(48, true, true, 0.5),
                "víc přebytečných bloků = silnější zásobování");
    }

    @Test
    void zustavaVPasmuPomocnychPraci() {
        // I s obřím přebytkem a max ochotou nesmí přebít přežití/boj/jídlo (~30+).
        assertTrue(SupplyGoal.utilityFor(500, true, true, 1.0) < 30,
                "zásobování zůstává pod vlastním bezpečím bota");
    }
}
