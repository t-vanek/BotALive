package dev.botalive.core.ai;

import dev.botalive.api.personality.Personality;
import dev.botalive.api.personality.Trait;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Testy volby životních ambicí – pořadí podle povahy.
 */
class AmbitionTest {

    private static Personality personality(double greed, double caution, double courage) {
        Map<Trait, Double> traits = new EnumMap<>(Trait.class);
        for (Trait trait : Trait.values()) {
            traits.put(trait, 0.5);
        }
        traits.put(Trait.GREED, greed);
        traits.put(Trait.CAUTION, caution);
        traits.put(Trait.COURAGE, courage);
        return new Personality() {
            @Override
            public double trait(Trait trait) {
                return traits.get(trait);
            }

            @Override
            public Map<Trait, Double> traits() {
                return traits;
            }

            @Override
            public long seed() {
                return 0;
            }

            @Override
            public String archetype() {
                return "test";
            }
        };
    }

    @Test
    void dominantniRysUrcujePrvniAmbici() {
        assertEquals(Ambition.RICH, Ambition.pick(personality(0.9, 0.4, 0.4)));
        assertEquals(Ambition.COZY_HOME, Ambition.pick(personality(0.3, 0.8, 0.4)));
        assertEquals(Ambition.FULL_IRON, Ambition.pick(personality(0.3, 0.4, 0.9)));
    }

    @Test
    void poradiOdpovidaSileRysu() {
        var ranked = Ambition.ranked(personality(0.9, 0.7, 0.2));
        assertEquals(Ambition.RICH, ranked.get(0));
        assertEquals(Ambition.COZY_HOME, ranked.get(1));
        assertEquals(Ambition.FULL_IRON, ranked.get(2));
    }

    @Test
    void remizaDrziPuvodniPreference() {
        // Shodné rysy → stejné pořadí jako historicky (RICH > COZY > IRON).
        assertEquals(Ambition.RICH, Ambition.pick(personality(0.5, 0.5, 0.5)));
    }
}
