package dev.botalive.core.ai;

import dev.botalive.core.world.Dimension;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy dimenzního gatingu cílů – co v Endu (a Netheru) nesmí běžet.
 */
class DimensionPolicyTest {

    @Test
    void postelVEnduANetheruExploduje() {
        // Kritické: spánek mimo overworld = výbuch postele.
        assertEquals(0.0, DimensionPolicy.weight("sleep", Dimension.THE_END));
        assertEquals(0.0, DimensionPolicy.weight("sleep", Dimension.NETHER));
        assertEquals(1.0, DimensionPolicy.weight("sleep", Dimension.OVERWORLD));
    }

    @Test
    void vEnduSeNebydliAneFarmari() {
        for (String goal : new String[]{"house", "shelter", "maintain", "farm",
                "fish", "boat", "trade", "tame", "hunt", "mine", "home", "stash",
                "steal", "rob", "guard"}) {
            assertEquals(0.0, DimensionPolicy.weight(goal, Dimension.THE_END),
                    "cíl '" + goal + "' nemá v Endu co dělat");
        }
    }

    @Test
    void prezitiABojZustavajVsude() {
        for (Dimension dimension : Dimension.values()) {
            assertEquals(1.0, DimensionPolicy.weight("survive", dimension));
            assertEquals(1.0, DimensionPolicy.weight("combat", dimension));
            assertEquals(1.0, DimensionPolicy.weight("eat", dimension));
            assertEquals(1.0, DimensionPolicy.weight("collect", dimension));
        }
    }

    @Test
    void endoveCileBeziJenVEndu() {
        assertEquals(0.0, DimensionPolicy.weight("dragon-fight", Dimension.OVERWORLD));
        assertEquals(0.0, DimensionPolicy.weight("end-harvest", Dimension.OVERWORLD));
        assertEquals(0.0, DimensionPolicy.weight("end-return", Dimension.OVERWORLD));
        assertEquals(1.0, DimensionPolicy.weight("dragon-fight", Dimension.THE_END));
        assertEquals(1.0, DimensionPolicy.weight("end-return", Dimension.THE_END));
        // Výprava do Endu se plánuje z overworldu, v Endu nedává smysl.
        assertEquals(1.0, DimensionPolicy.weight("end-travel", Dimension.OVERWORLD));
        assertEquals(0.0, DimensionPolicy.weight("end-travel", Dimension.THE_END));
        assertEquals(0.0, DimensionPolicy.weight("end-travel", Dimension.NETHER));
    }

    @Test
    void dennyRytmusPlatiJenVOverworldu() {
        assertTrue(DimensionPolicy.rhythmApplies(Dimension.OVERWORLD));
        assertFalse(DimensionPolicy.rhythmApplies(Dimension.THE_END));
        assertFalse(DimensionPolicy.rhythmApplies(Dimension.NETHER));
    }

    @Test
    void pruzkumVEnduJenTlumeny() {
        double weight = DimensionPolicy.weight("explore", Dimension.THE_END);
        assertTrue(weight > 0 && weight < 1, "průzkum v Endu má být tlumený, ne vypnutý");
    }
}
