package dev.botalive.core.personality;

import dev.botalive.api.personality.Trait;
import dev.botalive.core.personality.PersonalityEvolution.BotExperience;
import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy vývoje osobnosti – posuny, stropy driftu, přepočet archetypu.
 */
class PersonalityEvolutionTest {

    /** Osobnost s neutrálním základem 0.5 (seed 1 jen identifikuje základ). */
    private static PersonalityImpl neutral() {
        Map<Trait, Double> traits = new EnumMap<>(Trait.class);
        for (Trait trait : Trait.values()) {
            traits.put(trait, 0.5);
        }
        // baseTrait se počítá ze seedu – pro test potřebujeme základ == aktuál,
        // proto použijeme skutečný generovaný základ.
        long seed = 424242L;
        return new PersonalityImpl(seed,
                PersonalityGenerator.generate(seed).traits());
    }

    @Test
    void kradezZvysujeChamtivostASnizujeOchotu() {
        PersonalityImpl p = neutral();
        double greedBefore = p.trait(Trait.GREED);
        double helpBefore = p.trait(Trait.HELPFULNESS);
        var result = PersonalityEvolution.apply(p, BotExperience.STEAL_SUCCESS);
        assertTrue(result.changed());
        assertTrue(p.trait(Trait.GREED) > greedBefore, "krádež má zvýšit chamtivost");
        assertTrue(p.trait(Trait.HELPFULNESS) < helpBefore, "krádež má snížit ochotu");
    }

    @Test
    void pomahaniZvysujeOchotu() {
        PersonalityImpl p = neutral();
        double helpBefore = p.trait(Trait.HELPFULNESS);
        PersonalityEvolution.apply(p, BotExperience.SHARE_GIVEN);
        assertTrue(p.trait(Trait.HELPFULNESS) > helpBefore, "pomáhání má zvýšit ochotu");
    }

    @Test
    void driftJeOmezenyVuciZakladu() {
        PersonalityImpl p = neutral();
        double base = p.baseTrait(Trait.GREED);
        for (int i = 0; i < 200; i++) {
            PersonalityEvolution.apply(p, BotExperience.STEAL_SUCCESS);
        }
        double drift = p.trait(Trait.GREED) - base;
        assertTrue(drift <= PersonalityEvolution.MAX_DRIFT + 1e-9,
                "drift nesmí překročit strop: " + drift);
        assertTrue(p.trait(Trait.GREED) <= 0.98, "hodnota zůstává v mezích");
        // Po dosažení stropu už se nic nemění.
        var result = PersonalityEvolution.apply(p, BotExperience.STEAL_SUCCESS);
        assertFalse(result.changed() && p.trait(Trait.GREED) - base
                > PersonalityEvolution.MAX_DRIFT, "za stropem se drift nezvětšuje");
    }

    @Test
    void prekroceniPrahuVygenerujeHlasku() {
        PersonalityImpl p = neutral();
        boolean announced = false;
        for (int i = 0; i < 30 && !announced; i++) {
            announced = !PersonalityEvolution.apply(p, BotExperience.STEAL_SUCCESS)
                    .announcements().isEmpty();
        }
        assertTrue(announced, "růst chamtivosti má bot časem okomentovat");
    }

    @Test
    void archetypSePrepocitava() {
        long seed = 424242L;
        PersonalityImpl p = new PersonalityImpl(seed,
                PersonalityGenerator.generate(seed).traits());
        for (int i = 0; i < 40; i++) {
            PersonalityEvolution.apply(p, BotExperience.ROB_SUCCESS);
        }
        // Po sérii loupeží musí být agrese výrazně nad základem.
        assertTrue(p.trait(Trait.AGGRESSION) > p.baseTrait(Trait.AGGRESSION) + 0.2,
                "agrese má po loupežích výrazně vyrůst");
    }

    @Test
    void smrtUciOpatrnosti() {
        PersonalityImpl p = neutral();
        double cautionBefore = p.trait(Trait.CAUTION);
        double courageBefore = p.trait(Trait.COURAGE);
        PersonalityEvolution.apply(p, BotExperience.DEATH);
        assertTrue(p.trait(Trait.CAUTION) > cautionBefore);
        assertTrue(p.trait(Trait.COURAGE) < courageBefore);
    }
}
