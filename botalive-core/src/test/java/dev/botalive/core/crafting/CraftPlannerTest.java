package dev.botalive.core.crafting;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy survival crafting progrese (sdílený plánovač obou implementací).
 */
class CraftPlannerTest {

    private static CraftPlanner.State state(int logs, int planks, int sticks, int cobble,
                                            boolean table, boolean woodPick, boolean stonePick,
                                            boolean sword, boolean stoneSword, boolean axe) {
        return new CraftPlanner.State(logs, planks, sticks, cobble, table, woodPick,
                stonePick, sword, stoneSword, axe,
                logs > 0 ? Material.OAK_LOG : null,
                planks > 0 ? Material.OAK_PLANKS : null,
                Material.COBBLESTONE);
    }

    @Test
    void bezSurovinNicNeplanuje() {
        assertNull(CraftPlanner.next(state(0, 0, 0, 0, false, false, false, false, false, false)));
    }

    @Test
    void progresePrknaTyckyPonk() {
        CraftPlanner.Plan prkna = CraftPlanner.next(
                state(3, 0, 0, 0, false, false, false, false, false, false));
        assertEquals("prkna", prkna.id());
        assertFalse(prkna.needsTable());
        assertEquals(1, prkna.ingredients().get(Material.OAK_LOG));

        CraftPlanner.Plan tycky = CraftPlanner.next(
                state(0, 4, 0, 0, false, false, false, false, false, false));
        assertEquals("tyčky", tycky.id());
        assertEquals(2, tycky.ingredients().get(Material.OAK_PLANKS));

        CraftPlanner.Plan ponk = CraftPlanner.next(
                state(0, 5, 4, 0, false, false, false, false, false, false));
        assertEquals("ponk", ponk.id());
        assertFalse(ponk.needsTable());
        assertEquals(4, ponk.ingredients().get(Material.OAK_PLANKS));
    }

    @Test
    void nastrojeVyzadujiPonk() {
        CraftPlanner.Plan krumpac = CraftPlanner.next(
                state(0, 5, 4, 0, true, false, false, false, false, false));
        assertEquals("dřevěný krumpáč", krumpac.id());
        assertTrue(krumpac.needsTable());
        assertEquals(3, krumpac.ingredients().get(Material.OAK_PLANKS));
        assertEquals(2, krumpac.ingredients().get(Material.STICK));
    }

    @Test
    void kamennaProgreseAzPoDrevene() {
        CraftPlanner.Plan pick = CraftPlanner.next(
                state(0, 5, 4, 5, true, true, false, true, false, true));
        assertEquals("kamenný krumpáč", pick.id());
        assertEquals(3, pick.ingredients().get(Material.COBBLESTONE));

        CraftPlanner.Plan mec = CraftPlanner.next(
                state(0, 5, 4, 2, true, true, true, true, false, true));
        assertEquals("kamenný meč", mec.id());
    }

    @Test
    void kompletniVybavaNicNepotrebuje() {
        assertNull(CraftPlanner.next(
                state(0, 5, 4, 2, true, true, true, true, true, true)));
    }
}
