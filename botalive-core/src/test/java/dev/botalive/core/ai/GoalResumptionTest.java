package dev.botalive.core.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Návratová pobídka: přerušená produktivní práce/výprava si krátce drží navrch,
 * aby se k ní bot vrátil; reflexy pobídku nedostanou, pobídka časem slábne k 1,0
 * a maže se při návratu i ztrátě proveditelnosti.
 */
class GoalResumptionTest {

    @Test
    void preruseneProduktivniPraciZvysiVahu() {
        GoalResumption r = new GoalResumption();
        assertEquals(1.0, r.weight("mine"), 1e-9, "bez přerušení je násobič 1,0");
        r.interrupted("mine");
        assertTrue(r.weight("mine") > 1.0, "přerušená práce má navrch");
        assertTrue(r.weight("mine") <= 1.25 + 1e-9, "bonus je zastropovaný (max +25 %)");
        assertTrue(r.pending("mine"), "čeká na návrat");
    }

    @Test
    void pobidkaJeSilnejsiNezHystereze() {
        // Klíč návratu: čerstvá pobídka (+25 %) musí přebít hysterezi konkurenta
        // (+15 %), jinak by po odeznění reflexu bot zůstal u náhradní práce.
        GoalResumption r = new GoalResumption();
        r.interrupted("house");
        assertTrue(r.weight("house") > 1.15,
                "čerstvá pobídka je nad hysterezí, aby vrátila bota k práci");
    }

    @Test
    void reflexyPobidkuNedostanou() {
        GoalResumption r = new GoalResumption();
        for (String reflex : new String[]{"survive", "eat", "sleep", "combat",
                "escape", "home", "creeper-dodge", "idle", "wander"}) {
            assertFalse(r.resumable(reflex), reflex + " není práce k návratu");
            r.interrupted(reflex);
            assertEquals(1.0, r.weight(reflex), 1e-9,
                    reflex + " se sám spouští z podmínky, návrat nepotřebuje");
            assertFalse(r.pending(reflex), reflex + " nedrží pobídku");
        }
    }

    @Test
    void vypravyAProjektySeVraci() {
        // Dlouhé výpravy a stavební projekty jsou hlavní oběti přerušení bojem.
        GoalResumption r = new GoalResumption();
        for (String work : new String[]{"nether", "explore", "end-travel",
                "dragon-fight", "stronghold", "settlement-walls"}) {
            assertTrue(r.resumable(work), work + " je práce k návratu");
        }
    }

    @Test
    void pobidkaCasemSlabne() {
        GoalResumption r = new GoalResumption();
        r.interrupted("craft");
        double fresh = r.weight("craft");
        for (int i = 0; i < 50; i++) {
            r.decay();
        }
        assertTrue(r.weight("craft") < fresh, "pobídka slábne");
        // Po dost dlouhém rozpadu spadne pod práh a zahodí se → zpět na 1,0.
        for (int i = 0; i < 700; i++) {
            r.decay();
        }
        assertEquals(1.0, r.weight("craft"), 1e-9, "vyčerpaná pobídka je zpět na 1,0");
        assertFalse(r.pending("craft"), "vyčerpaný záznam se uklidil");
    }

    @Test
    void navratPobidkuZahodi() {
        // Když se bot k cíli vrátí, mozek pobídku zruší – dál ho drží hystereze.
        GoalResumption r = new GoalResumption();
        r.interrupted("farm");
        assertTrue(r.pending("farm"));
        r.clear("farm");
        assertEquals(1.0, r.weight("farm"), 1e-9, "po návratu už pobídka nehraje");
        assertFalse(r.pending("farm"));
    }
}
