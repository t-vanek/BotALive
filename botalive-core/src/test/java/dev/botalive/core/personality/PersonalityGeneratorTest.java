package dev.botalive.core.personality;

import dev.botalive.api.personality.Personality;
import dev.botalive.api.personality.Trait;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy generátoru osobností.
 */
class PersonalityGeneratorTest {

    @Test
    void jeDeterministickyProStejnySeed() {
        Personality first = PersonalityGenerator.generate(123456789L);
        Personality second = PersonalityGenerator.generate(123456789L);

        assertEquals(first.traits(), second.traits(), "stejný seed ⇒ stejná osobnost");
        assertEquals(first.archetype(), second.archetype());
    }

    @Test
    void ruzneSeedyDavajiRuzneOsobnosti() {
        Personality first = PersonalityGenerator.generate(1L);
        Personality second = PersonalityGenerator.generate(2L);

        assertNotEquals(first.traits(), second.traits(), "různé seedy ⇒ různí boti");
    }

    @Test
    void rysyJsouVMezich() {
        for (long seed = 0; seed < 200; seed++) {
            Personality personality = PersonalityGenerator.generate(seed);
            for (Trait trait : Trait.values()) {
                double value = personality.trait(trait);
                assertTrue(value >= 0.05 && value <= 0.95,
                        "rys " + trait + " mimo meze: " + value + " (seed " + seed + ")");
            }
            assertNotNull(personality.archetype());
        }
    }

    @Test
    void rozlozeniNeniDegenerovane() {
        // Průměr přes mnoho seedů má být poblíž 0.5 a hodnoty se mají lišit.
        double sum = 0;
        double min = 1;
        double max = 0;
        int samples = 500;
        for (long seed = 0; seed < samples; seed++) {
            double value = PersonalityGenerator.generate(seed).trait(Trait.CURIOSITY);
            sum += value;
            min = Math.min(min, value);
            max = Math.max(max, value);
        }
        double mean = sum / samples;
        assertTrue(mean > 0.4 && mean < 0.6, "průměr mimo očekávání: " + mean);
        assertTrue(max - min > 0.4, "hodnoty se dostatečně neliší");
    }
}
