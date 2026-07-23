package dev.botalive.core.ai.goals;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rozhodovací jádro ukládání do truhly ({@link StashGoal#utilityFor}).
 * Tick smyčka (hledání truhly, chůze, otevírání) se testuje jen naživo; váha
 * je čistá funkce a chytá regrese pásma i vstupních podmínek.
 */
class StashGoalTest {

    @Test
    void mlciSMaloZaplnenymBatohem() {
        // Pod prahem 18 slotů se kvůli pár kusům do truhly nechodí.
        assertEquals(0.0, StashGoal.utilityFor(5, true, true, 1.0),
                "málo zaplněný batoh – žádná pochůzka");
    }

    @Test
    void mlciBezCehoUlozit() {
        // Plný batoh, ale jen rezerva a spotřebák (jídlo, nástroje) – nic k uložení.
        assertEquals(0.0, StashGoal.utilityFor(27, false, false, 1.0),
                "bez odpadu i přebytku cenností se neukládá");
    }

    @Test
    void ukladaOdpad() {
        assertTrue(StashGoal.utilityFor(20, true, false, 0.0) > 0,
                "odpad nad prahem → uložit");
    }

    @Test
    void ukladaPrebytekCennosti() {
        // Klíčová novinka: i bez odpadu (samé rudy/ingoty nad rezervu) se banku­je –
        // dřív zůstávaly v batohu, dokud je smrt nesebrala.
        assertTrue(StashGoal.utilityFor(20, false, true, 0.0) > 0,
                "přebytek rud/ingotů bez odpadu → uložit");
    }

    @Test
    void plnejsiBatohTahneSilneji() {
        assertTrue(StashGoal.utilityFor(26, true, false, 0.5)
                        > StashGoal.utilityFor(20, true, false, 0.5),
                "fuller batoh = vyšší priorita");
    }

    @Test
    void chamtivostZvedaVahu() {
        assertTrue(StashGoal.utilityFor(20, true, false, 1.0)
                        > StashGoal.utilityFor(20, true, false, 0.0),
                "chamtivost zvedá ukládání");
    }

    @Test
    void zustavaVPasmuSpravyInventare() {
        // I plný batoh přebytku (27/27) s max chamtivostí zůstává pod pásmem
        // opravdového přežití (jídlo při hladu, útěk, boj – ~34+).
        assertTrue(StashGoal.utilityFor(27, true, true, 1.0) < 32,
                "ukládání zůstává v pásmu vlastní správy inventáře");
    }
}
