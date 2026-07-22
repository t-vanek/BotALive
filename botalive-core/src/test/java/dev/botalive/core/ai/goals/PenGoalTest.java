package dev.botalive.core.ai.goals;

import dev.botalive.core.util.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Testy výběru obdélníku ohrady – pevná buňka mřížky 7×7 kolem těžiště stáda,
 * práh počtu zvířat a rozptyl přes hranu buňky. Deterministický obdélník brání
 * překrývajícím se ohradám (idempotence). Stavbu vede {@link BarrierWorker}
 * (testuje se v provozu).
 */
class PenGoalTest {

    private static final int Y = 64;

    private static int[] at(int x, int z) {
        return new int[]{x, z};
    }

    @Test
    void stadoVBunceDaOhraduPrichycenouNaMrizku() {
        // Těžiště (2,2) → buňka [0,7) → ohrada min (0,0), max (6,6).
        PenGoal.PenRect r = PenGoal.penRect(List.of(at(1, 1), at(2, 2), at(3, 3)), Y);
        assertEquals(new BlockPos(0, Y, 0), r.min());
        assertEquals(new BlockPos(6, Y, 6), r.max());
    }

    @Test
    void maloZviratZadnaOhrada() {
        assertNull(PenGoal.penRect(List.of(at(1, 1), at(2, 2)), Y));
        assertNull(PenGoal.penRect(List.of(), Y));
    }

    @Test
    void rozptyleneStadoPresHranuNeohradime() {
        // Těžiště padne do buňky (0,0), ale jsou v ní jen dvě zvířata (třetí je
        // v jiné buňce) – práh v buňce nesplněn.
        assertNull(PenGoal.penRect(List.of(at(0, 0), at(6, 6), at(13, 13)), Y));
    }

    @Test
    void zaporneSouradniceSePrichytiSpravne() {
        // Těžiště (−2,−2) → floorDiv(−2,7) = −1 → buňka [−7,0).
        PenGoal.PenRect r = PenGoal.penRect(List.of(at(-1, -1), at(-2, -2), at(-3, -3)), Y);
        assertEquals(new BlockPos(-7, Y, -7), r.min());
        assertEquals(new BlockPos(-1, Y, -1), r.max());
    }

    @Test
    void tezeStadoDaVzdyTentyzObdelnik() {
        // Posun jednoho zvířete uvnitř buňky nezmění buňku → stejná ohrada
        // (klíč idempotence: opakovaný běh nepostaví druhou překrývající ohradu).
        PenGoal.PenRect a = PenGoal.penRect(List.of(at(1, 1), at(2, 2), at(3, 3)), Y);
        PenGoal.PenRect b = PenGoal.penRect(List.of(at(1, 1), at(2, 2), at(4, 4)), Y);
        assertEquals(a.min(), b.min());
        assertEquals(a.max(), b.max());
    }
}
