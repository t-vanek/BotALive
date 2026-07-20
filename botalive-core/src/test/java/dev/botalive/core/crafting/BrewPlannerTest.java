package dev.botalive.core.crafting;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Testy plánovače vaření – řetěz voda → awkward → efekty → splash.
 */
class BrewPlannerTest {

    /** Stav bez lektvarů, s volitelnými přísadami. */
    private static BrewPlanner.State bare(int water, int awkward, int poison,
                                          int wart, int magma, int melon,
                                          int eye, int gunpowder, int blaze) {
        return new BrewPlanner.State(water, awkward, poison,
                false, false, false, false,
                wart, magma, melon, eye, gunpowder, blaze);
    }

    @Test
    void bezSurovinNicNevari() {
        assertNull(BrewPlanner.next(bare(0, 0, 0, 0, 0, 0, 0, 0, 0)));
        // Voda bez bradavice základ neuvaří.
        assertNull(BrewPlanner.next(bare(3, 0, 0, 0, 1, 0, 0, 0, 0)));
    }

    @Test
    void zakladJdePrvni() {
        BrewPlanner.Batch batch = BrewPlanner.next(bare(3, 0, 0, 4, 1, 0, 0, 0, 0));
        assertEquals(Material.NETHER_WART, batch.ingredient());
        assertEquals(BrewPlanner.Base.WATER, batch.base());
    }

    @Test
    void odolnostOhniMaPrednostPredOstatnimi() {
        BrewPlanner.Batch batch = BrewPlanner.next(bare(0, 3, 0, 0, 2, 2, 2, 2, 4));
        assertEquals(Material.MAGMA_CREAM, batch.ingredient());
        assertEquals(BrewPlanner.Base.AWKWARD, batch.base());
    }

    @Test
    void poradiEfektuLecenieSilaJed() {
        // Bez magma krému: léčení (třpytivý meloun).
        assertEquals(Material.GLISTERING_MELON_SLICE,
                BrewPlanner.next(bare(0, 3, 0, 0, 0, 1, 1, 1, 4)).ingredient());
        // Bez melounu: síla (blaze prach, aspoň 1 kus zůstává).
        assertEquals(Material.BLAZE_POWDER,
                BrewPlanner.next(bare(0, 3, 0, 0, 0, 0, 1, 1, 4)).ingredient());
        // Poslední prach se na sílu nemele – zbývá jed.
        assertEquals(Material.SPIDER_EYE,
                BrewPlanner.next(bare(0, 3, 0, 0, 0, 0, 1, 1, 1)).ingredient());
    }

    @Test
    void splashKonverzeUzaviraRetez() {
        // Pitelný jed + střelný prach → splash.
        BrewPlanner.Batch batch = BrewPlanner.next(bare(0, 1, 2, 0, 0, 0, 0, 2, 1));
        assertEquals(Material.GUNPOWDER, batch.ingredient());
        assertEquals(BrewPlanner.Base.POISON, batch.base());
    }

    @Test
    void kompletneVybavenyAlchymistaNevari() {
        BrewPlanner.State done = new BrewPlanner.State(3, 3, 0,
                true, true, true, true,
                8, 4, 4, 4, 4, 4);
        assertNull(BrewPlanner.next(done), "všechny cílové lektvary má");
    }

    @Test
    void hotovyEfektSeZnovuNevari() {
        // Odolnost ohni už nese → další na řadě je léčení.
        BrewPlanner.State state = new BrewPlanner.State(0, 2, 0,
                true, false, false, false,
                0, 2, 1, 1, 1, 4);
        assertEquals(Material.GLISTERING_MELON_SLICE,
                BrewPlanner.next(state).ingredient());
    }
}
