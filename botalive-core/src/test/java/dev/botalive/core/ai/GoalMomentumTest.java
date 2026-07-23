package dev.botalive.core.ai;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Naučená hybnost cílů: úspěch produktivní práce ji zvedne (malý, klesavý
 * bonus), reflexy se neposilují, hybnost časem slábne k 1,0.
 */
class GoalMomentumTest {

    @Test
    void uspechProduktivnihoCileZvysiVahu() {
        GoalMomentum m = new GoalMomentum();
        assertEquals(1.0, m.weight("mine"), 1e-9, "bez hybnosti je násobič 1,0");
        m.reinforce("mine");
        assertTrue(m.weight("mine") > 1.0, "úspěch zvedne hybnost");
        assertTrue(m.weight("mine") <= 1.15 + 1e-9, "bonus je zastropovaný (max +15 %)");
    }

    @Test
    void bonusMaStrop() {
        GoalMomentum m = new GoalMomentum();
        for (int i = 0; i < 20; i++) {
            m.reinforce("house");
        }
        assertEquals(1.15, m.weight("house"), 1e-9, "hybnost se nasytí na +15 %");
    }

    @Test
    void reflexySeNeposiluji() {
        GoalMomentum m = new GoalMomentum();
        for (String reflex : new String[]{"home", "eat", "sleep", "survive", "combat"}) {
            m.reinforce(reflex);
            assertEquals(1.0, m.weight(reflex), 1e-9, reflex + " se neučí (nemonopolizuje)");
        }
    }

    @Test
    void hybnostCasemSlabne() {
        GoalMomentum m = new GoalMomentum();
        m.reinforce("craft");
        double afterSuccess = m.weight("craft");
        for (int i = 0; i < 50; i++) {
            m.decay();
        }
        assertTrue(m.weight("craft") < afterSuccess, "hybnost slábne");
        // Po dost dlouhém rozpadu spadne pod práh a zahodí se → zpět na 1,0.
        for (int i = 0; i < 700; i++) {
            m.decay();
        }
        assertEquals(1.0, m.weight("craft"), 1e-9, "vyčerpaná hybnost je zpět na 1,0");
    }
}
