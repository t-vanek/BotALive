package dev.botalive.core.role;

import dev.botalive.api.personality.Personality;
import dev.botalive.api.personality.Trait;
import dev.botalive.api.role.BotRole;
import dev.botalive.core.personality.PersonalityImpl;
import dev.botalive.core.util.BotRandom;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy výběru profese podle osobnosti.
 */
class RolePickerTest {

    private static Personality withTraits(Map<Trait, Double> overrides) {
        Map<Trait, Double> traits = new EnumMap<>(Trait.class);
        for (Trait trait : Trait.values()) {
            traits.put(trait, overrides.getOrDefault(trait, 0.3));
        }
        return new PersonalityImpl(1L, traits);
    }

    @Test
    void agresivniOdvazlivecByvaLovec() {
        Personality personality = withTraits(Map.of(
                Trait.AGGRESSION, 0.95, Trait.COURAGE, 0.95));
        int hunters = 0;
        for (long seed = 0; seed < 100; seed++) {
            if (RolePicker.pick(personality, new BotRandom(seed)) == BotRole.HUNTER) {
                hunters++;
            }
        }
        assertTrue(hunters > 50, "výrazný lovec má lovcem být většinou (bylo " + hunters + "/100)");
    }

    @Test
    void trpelivyLenochByvaRybar() {
        Personality personality = withTraits(Map.of(
                Trait.PATIENCE, 0.95, Trait.LAZINESS, 0.9));
        int fishermen = 0;
        for (long seed = 0; seed < 100; seed++) {
            if (RolePicker.pick(personality, new BotRandom(seed)) == BotRole.FISHERMAN) {
                fishermen++;
            }
        }
        assertTrue(fishermen > 40, "trpělivý lenoch má být rybářem často (bylo " + fishermen + "/100)");
    }

    @Test
    void vzdyVratiNejakouRoli() {
        for (long seed = 0; seed < 50; seed++) {
            Personality personality = dev.botalive.core.personality.PersonalityGenerator
                    .generate(seed);
            assertNotNull(RolePicker.pick(personality, new BotRandom(seed)));
        }
    }

    @Test
    void populaceJeRozmanita() {
        // Náhodná populace nesmí skončit celá u jedné profese.
        Set<BotRole> seen = EnumSet.noneOf(BotRole.class);
        for (long seed = 0; seed < 300; seed++) {
            Personality personality = dev.botalive.core.personality.PersonalityGenerator
                    .generate(seed);
            seen.add(RolePicker.pick(personality, new BotRandom(seed * 31)));
        }
        assertTrue(seen.size() >= 6, "populace má pokrýt většinu rolí, pokryto: " + seen);
    }
}
