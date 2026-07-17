package dev.botalive.core.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy denního rytmu – fáze dne, násobiče a osobní posun.
 */
class DayRhythmTest {

    @Test
    void ranoBoostujeFarmuVecerDruzeni() {
        DayRhythm rhythm = new DayRhythm(0.5); // bez posunu
        assertTrue(rhythm.multiplier("farm", 1000) > 1.0, "ráno má farma přednost");
        assertTrue(rhythm.multiplier("socialize", 10_000) > 1.0, "večer se boti druží");
        assertTrue(rhythm.multiplier("mine", 15_000) < 1.0, "v noci se netěží");
        assertTrue(rhythm.multiplier("house", 5_000) > 1.0, "přes den se staví");
    }

    @Test
    void neznamyCilMaNeutralniVahu() {
        DayRhythm rhythm = new DayRhythm(0.5);
        assertEquals(1.0, rhythm.multiplier("combat", 1000), 1e-9);
        assertEquals(1.0, rhythm.multiplier("survive", 15_000), 1e-9);
    }

    @Test
    void neznamyCasJeNeutralni() {
        DayRhythm rhythm = new DayRhythm(0.5);
        assertEquals(1.0, rhythm.multiplier("farm", -1), 1e-9);
    }

    @Test
    void liniBotiMajiPosunutyDen() {
        DayRhythm early = new DayRhythm(0.0);  // skřivan: den začíná dřív
        DayRhythm lazy = new DayRhythm(1.0);   // sova: den posunutý k večeru
        assertTrue(early.shiftTicks() < 0);
        assertTrue(lazy.shiftTicks() > 0);
        // Ve stejný čas můžou být v jiné fázi dne.
        assertEquals(DayRhythm.Phase.DAY, early.phaseAt(2_000));
        assertEquals(DayRhythm.Phase.MORNING, lazy.phaseAt(2_000));
    }

    @Test
    void fazeSeStridajiPresPulnoc() {
        DayRhythm rhythm = new DayRhythm(0.5);
        assertEquals(DayRhythm.Phase.MORNING, rhythm.phaseAt(23_500));
        assertEquals(DayRhythm.Phase.MORNING, rhythm.phaseAt(500));
        assertEquals(DayRhythm.Phase.NIGHT, rhythm.phaseAt(18_000));
    }
}
