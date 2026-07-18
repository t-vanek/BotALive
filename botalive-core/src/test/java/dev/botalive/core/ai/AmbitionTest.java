package dev.botalive.core.ai;

import dev.botalive.api.personality.Personality;
import dev.botalive.api.personality.Trait;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
        // Skóre: RICH 0.9 > COZY 0.7 > NETHERITE (0.2+0.9)/2 = 0.55 > IRON 0.2.
        var ranked = Ambition.ranked(personality(0.9, 0.7, 0.2));
        assertEquals(Ambition.RICH, ranked.get(0));
        assertEquals(Ambition.COZY_HOME, ranked.get(1));
        assertEquals(Ambition.NETHERITE, ranked.get(2));
        assertEquals(Ambition.FULL_IRON, ranked.get(3));
    }

    @Test
    void netheritJeDruhySenOdvaznehoChamtivce() {
        // Průměr (odvaha+chamtivost)/2 nikdy nepřekoná dominantní rys –
        // netherit je záměrně druhý sen: po splnění železné výbavy na něj
        // dojde řada (refreshAmbition bere další nesplněnou z ranked).
        var ranked = Ambition.ranked(personality(0.8, 0.3, 0.9));
        assertEquals(Ambition.FULL_IRON, ranked.get(0));
        assertEquals(Ambition.NETHERITE, ranked.get(1));
    }

    @Test
    void remizaDrziPuvodniPreference() {
        // Shodné rysy → stejné pořadí jako historicky (RICH > COZY > IRON).
        assertEquals(Ambition.RICH, Ambition.pick(personality(0.5, 0.5, 0.5)));
    }

    @Test
    void drakJeDruhySenOdvaznych() {
        // Odvážlivec (bez chamtivosti): železná výbava (COURAGE ×1.0) vede,
        // drak (×0.9) hned za ní; netherit u nechamtivého zaostává.
        var ranked = Ambition.ranked(personality(0.2, 0.3, 0.9));
        assertEquals(Ambition.FULL_IRON, ranked.get(0));
        assertEquals(Ambition.DRAGON_SLAYER, ranked.get(1));
    }

    @Test
    void postupDracihoSnaVedeOdVybavyKPortalu() {
        var needs = BotNeeds.assess(null);
        var start = new Ambition.State(needs, false, false, 0,
                false, false, false, false);
        assertEquals(0, Ambition.DRAGON_SLAYER.progress(start).step());

        var geared = new Ambition.State(needs, false, false, 0,
                true, false, false, false);
        assertEquals(1, Ambition.DRAGON_SLAYER.progress(geared).step());

        var armed = new Ambition.State(needs, false, false, 0,
                true, true, false, false);
        assertEquals(2, Ambition.DRAGON_SLAYER.progress(armed).step());

        var knowsPortal = new Ambition.State(needs, false, false, 0,
                true, true, true, false);
        assertEquals(3, Ambition.DRAGON_SLAYER.progress(knowsPortal).step());

        var slain = new Ambition.State(needs, false, false, 0,
                true, true, true, true);
        assertTrue(Ambition.DRAGON_SLAYER.progress(slain).complete());
    }

    @Test
    void stareAmbiceStavemNetrpi() {
        // Refaktoring na State nesmí změnit milníky původních ambicí.
        var needs = BotNeeds.assess(null);
        var state = new Ambition.State(needs, true, true, 600, false, false, false, false);
        assertTrue(Ambition.COZY_HOME.progress(state).complete());
        assertTrue(Ambition.RICH.progress(state).complete());
        assertEquals(0, Ambition.FULL_IRON.progress(state).step());
    }
}
