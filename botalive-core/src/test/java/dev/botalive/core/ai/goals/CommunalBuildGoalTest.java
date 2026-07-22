package dev.botalive.core.ai.goals;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.botalive.core.ai.goals.CommunalBuildGoal.ProvisionStep;
import org.junit.jupiter.api.Test;

/**
 * Rozhodovací jádro zásobování komunální stavby (fáze PROVISION) – čisté
 * funkce. Vlastní tick smyčku (navigace, těžba přes {@code BarrierGather})
 * ověřuje živý server; tady se pojistí přechody: kdy zahájit, kdy stavět,
 * kdy shánět dál a kdy projekt vzdát.
 */
class CommunalBuildGoalTest {

    @Test
    void malaStavbaMaPrahJejiPlneSpotreby() {
        // want = blocksNeeded + rezerva 8; když je pod MIN_BLOCKS(24),
        // práh je celá spotřeba – studna/sýpka/tržiště se chovají jako dřív.
        assertEquals(24, CommunalBuildGoal.startThreshold(16), "16+8=24 ≤ MIN");
        assertEquals(20, CommunalBuildGoal.startThreshold(12), "12+8=20 < MIN");
    }

    @Test
    void velkaStavbaStaciZahajitSMinimem() {
        // Radnice 71 / kostel 113: práh je jen MIN_BLOCKS, zbytek dodá PROVISION.
        assertEquals(24, CommunalBuildGoal.startThreshold(71));
        assertEquals(24, CommunalBuildGoal.startThreshold(113));
    }

    @Test
    void dostMaterialuZnamenaStavet() {
        assertEquals(ProvisionStep.BUILD, CommunalBuildGoal.provisionStep(130, 121, false));
        assertEquals(ProvisionStep.BUILD, CommunalBuildGoal.provisionStep(121, 121, true));
    }

    @Test
    void chybiMaterialAOkoliMaZdroje_ShaniSeDal() {
        assertEquals(ProvisionStep.GATHER, CommunalBuildGoal.provisionStep(30, 121, false));
        assertEquals(ProvisionStep.GATHER, CommunalBuildGoal.provisionStep(0, 121, false));
    }

    @Test
    void okoliVytezeno_sMinimemStaviCast() {
        // want 121, MIN 24: s ≥ MIN se postaví aspoň část (zbytek příští seance).
        assertEquals(ProvisionStep.BUILD, CommunalBuildGoal.provisionStep(24, 121, true));
        assertEquals(ProvisionStep.BUILD, CommunalBuildGoal.provisionStep(60, 121, true));
    }

    @Test
    void okoliVytezeno_bezMinimaSeProjektVzda() {
        assertEquals(ProvisionStep.GIVE_UP, CommunalBuildGoal.provisionStep(23, 121, true));
        assertEquals(ProvisionStep.GIVE_UP, CommunalBuildGoal.provisionStep(0, 121, true));
    }

    @Test
    void seanceSmiZacitSCastiBlokuUVelkeStavby() {
        assertTrue(CommunalBuildGoal.canStartSession(24, 121), "≥ MIN → seance smí začít");
        assertTrue(CommunalBuildGoal.canStartSession(50, 121));
        assertFalse(CommunalBuildGoal.canStartSession(10, 121), "pod MIN se nezačíná");
    }

    @Test
    void malaStavbaVyzadujeCelouSpotrebuIProSeanci() {
        // Práh seance je min(MIN, cells): malá stavba potřebuje všechny buňky.
        assertTrue(CommunalBuildGoal.canStartSession(20, 20));
        assertFalse(CommunalBuildGoal.canStartSession(19, 20));
    }
}
