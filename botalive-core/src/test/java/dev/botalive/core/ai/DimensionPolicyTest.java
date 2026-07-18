package dev.botalive.core.ai;

import dev.botalive.core.world.WorldDimension;
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
        assertEquals(0.0, DimensionPolicy.weight("sleep", WorldDimension.END));
        assertEquals(0.0, DimensionPolicy.weight("sleep", WorldDimension.NETHER));
        assertEquals(1.0, DimensionPolicy.weight("sleep", WorldDimension.OVERWORLD));
    }

    @Test
    void vEnduSeNebydliAneFarmari() {
        for (String goal : new String[]{"house", "shelter", "maintain", "farm",
                "fish", "boat", "trade", "tame", "hunt", "mine", "home", "stash",
                "steal", "rob", "guard"}) {
            assertEquals(0.0, DimensionPolicy.weight(goal, WorldDimension.END),
                    "cíl '" + goal + "' nemá v Endu co dělat");
        }
    }

    @Test
    void prezitiABojZustavajVsude() {
        for (WorldDimension dimension : WorldDimension.values()) {
            assertEquals(1.0, DimensionPolicy.weight("survive", dimension));
            assertEquals(1.0, DimensionPolicy.weight("combat", dimension));
            assertEquals(1.0, DimensionPolicy.weight("eat", dimension));
            assertEquals(1.0, DimensionPolicy.weight("collect", dimension));
        }
    }

    @Test
    void endoveCileBeziJenVEndu() {
        assertEquals(0.0, DimensionPolicy.weight("dragon-fight", WorldDimension.OVERWORLD));
        assertEquals(0.0, DimensionPolicy.weight("end-harvest", WorldDimension.OVERWORLD));
        assertEquals(0.0, DimensionPolicy.weight("end-return", WorldDimension.OVERWORLD));
        assertEquals(1.0, DimensionPolicy.weight("dragon-fight", WorldDimension.END));
        assertEquals(1.0, DimensionPolicy.weight("end-return", WorldDimension.END));
        // Výprava do Endu se plánuje z overworldu, v Endu nedává smysl.
        assertEquals(1.0, DimensionPolicy.weight("end-travel", WorldDimension.OVERWORLD));
        assertEquals(0.0, DimensionPolicy.weight("end-travel", WorldDimension.END));
        assertEquals(0.0, DimensionPolicy.weight("end-travel", WorldDimension.NETHER));
    }

    @Test
    void dennyRytmusPlatiJenVOverworldu() {
        assertTrue(DimensionPolicy.rhythmApplies(WorldDimension.OVERWORLD));
        assertFalse(DimensionPolicy.rhythmApplies(WorldDimension.END));
        assertFalse(DimensionPolicy.rhythmApplies(WorldDimension.NETHER));
    }

    @Test
    void pruzkumVEnduJenTlumeny() {
        double weight = DimensionPolicy.weight("explore", WorldDimension.END);
        assertTrue(weight > 0 && weight < 1, "průzkum v Endu má být tlumený, ne vypnutý");
    }
}
