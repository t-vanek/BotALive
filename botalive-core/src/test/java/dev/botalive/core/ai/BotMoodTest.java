package dev.botalive.core.ai;

import dev.botalive.api.personality.Personality;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotMood.Emotion;
import dev.botalive.core.personality.PersonalityEvolution.BotExperience;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ověřuje náladu bota: neutralitu v klidu, ořez a odeznívání emocí, modulaci
 * priorit cílů, reakci na prožitky škálovanou povahou a tělesné/okolní signály.
 */
class BotMoodTest {

    private static Personality personality(double caution, double aggression, double sociability) {
        return new Personality() {
            @Override
            public double trait(Trait trait) {
                return switch (trait) {
                    case CAUTION -> caution;
                    case AGGRESSION -> aggression;
                    case SOCIABILITY -> sociability;
                    default -> 0.5;
                };
            }

            @Override
            public Map<Trait, Double> traits() {
                return Map.of();
            }

            @Override
            public long seed() {
                return 0L;
            }

            @Override
            public String archetype() {
                return "test";
            }
        };
    }

    @Test
    void calmMoodDoesNotModulate() {
        BotMood mood = new BotMood();
        assertEquals(1.0, mood.modulate("explore"), 1e-9);
        assertEquals(1.0, mood.modulate("survive"), 1e-9);
        assertNull(mood.dominant());
    }

    @Test
    void feelClampsAndDecays() {
        BotMood mood = new BotMood();
        mood.feel(Emotion.FEAR, 1.5);
        assertEquals(1.0, mood.level(Emotion.FEAR), 1e-9);
        mood.feel(Emotion.FEAR, -2.0);
        assertEquals(0.0, mood.level(Emotion.FEAR), 1e-9);

        mood.feel(Emotion.ANGER, 0.5);
        mood.decay();
        assertTrue(mood.level(Emotion.ANGER) < 0.5 && mood.level(Emotion.ANGER) > 0);
    }

    @Test
    void fearShiftsPrioritiesTowardSafety() {
        BotMood mood = new BotMood();
        mood.feel(Emotion.FEAR, 1.0);
        assertTrue(mood.modulate("escape") > 1.3, "strach táhne k útěku");
        assertTrue(mood.modulate("mine") < 1.0, "strach tlumí těžbu");
        assertEquals(Emotion.FEAR, mood.dominant());
    }

    @Test
    void deathTriggersFearScaledByCaution() {
        BotMood cautious = new BotMood();
        cautious.reactTo(BotExperience.DEATH, personality(1.0, 0.5, 0.5));
        BotMood reckless = new BotMood();
        reckless.reactTo(BotExperience.DEATH, personality(0.0, 0.5, 0.5));

        assertTrue(cautious.level(Emotion.FEAR) > reckless.level(Emotion.FEAR),
                "opatrný se smrti bojí víc");
        assertTrue(reckless.level(Emotion.FEAR) > 0);
    }

    @Test
    void achievementBringsContentment() {
        BotMood mood = new BotMood();
        mood.reactTo(BotExperience.HOUSE_BUILT, personality(0.5, 0.5, 0.5));
        assertTrue(mood.level(Emotion.CONTENT) > 0.3);
        assertTrue(mood.modulate("socialize") > 1.0);
    }

    @Test
    void bodyAndCompanyDriveMood() {
        // O samotě roste samota.
        BotMood lonely = new BotMood();
        for (int i = 0; i < 10; i++) {
            lonely.observe(20, 20, 0, 0, false, 1.0);
        }
        double before = lonely.level(Emotion.LONELY);
        assertTrue(before > 0);
        // Společnost samotu zahání.
        lonely.observe(20, 20, 0, 3, false, 1.0);
        assertTrue(lonely.level(Emotion.LONELY) < before);

        // Nízké zdraví a hrozby v noci budí strach.
        BotMood scared = new BotMood();
        scared.observe(4, 20, 2, 0, true, 0.5);
        assertTrue(scared.level(Emotion.FEAR) > 0);
    }
}
