package dev.botalive.core.ai.goals;

import dev.botalive.core.build.Enclosure.Assessment;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy prioritizace opravy bariér – jádro požadavku: „opravit ohradu (ať
 * neutečou zvířata) dřív než plot domu" a „blíží se noc → nejdřív hradby".
 */
class BarrierRepairTest {

    /** Pár děr v jinak stojící bariéře (17 stojí, 3 chybí). */
    private static final Assessment DAMAGED = new Assessment(17, 3);

    @Test
    void poradiPriorit() {
        double wallNight = BarrierRepair.wallUrgency(DAMAGED, true);
        double pen = BarrierRepair.penUrgency(DAMAGED);
        double wallDay = BarrierRepair.wallUrgency(DAMAGED, false);
        double houseFence = BarrierRepair.houseFenceUrgency(DAMAGED);

        // Hradby za soumraku jsou nejnaléhavější (obrana sídla).
        assertTrue(wallNight > pen, "hradby@noc > ohrada");
        // Ohrada zvířat před plotem domu (ať neutečou zvířata).
        assertTrue(pen > houseFence, "ohrada > plot domu");
        // Hradby ve dne i plot domu jsou mírné, pod ohradou.
        assertTrue(pen > wallDay, "ohrada > hradby@den");
        assertTrue(wallDay > houseFence || wallDay > 0, "hradby@den mírné");
        assertTrue(wallNight > wallDay, "noc zvedá naléhavost hradeb");
    }

    @Test
    void vetsiPoskozeniVetsiNalehavost() {
        Assessment malo = new Assessment(19, 1);
        Assessment hodne = new Assessment(12, 8);
        assertTrue(BarrierRepair.penUrgency(hodne) > BarrierRepair.penUrgency(malo),
                "víc děr = naléhavější");
    }

    @Test
    void isDamagedRozezaOpravuOdStavby() {
        assertTrue(BarrierRepair.isDamaged(new Assessment(17, 3)), "pár děr = oprava");
        assertTrue(BarrierRepair.isDamaged(new Assessment(19, 1)), "jedna díra = oprava");
        assertFalse(BarrierRepair.isDamaged(new Assessment(20, 0)), "celá stojí = není co opravovat");
        assertFalse(BarrierRepair.isDamaged(new Assessment(0, 16)), "nová stavba = ne oprava");
        assertFalse(BarrierRepair.isDamaged(new Assessment(10, 10)), "poloviční stavba = ne oprava");
        assertFalse(BarrierRepair.isDamaged(new Assessment(30, 9)),
                "víc než MAX_GAPS děr = (pře)stavba, ne oprava");
    }
}
