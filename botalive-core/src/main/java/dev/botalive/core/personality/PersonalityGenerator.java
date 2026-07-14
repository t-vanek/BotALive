package dev.botalive.core.personality;

import dev.botalive.api.personality.Personality;
import dev.botalive.api.personality.Trait;

import java.util.EnumMap;
import java.util.Map;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * Deterministický generátor osobností.
 *
 * <p>Rysy se vzorkují z gaussovského rozdělení (μ=0.5, σ=0.18) a ořezávají do
 * [0.05, 0.95] – extrémní povahy existují, ale jsou vzácné. Generování je čistou
 * funkcí seedu: stejný seed vždy vyprodukuje stejnou osobnost, takže se dá
 * osobnost obnovit z databáze jen ze seedu a zároveň validovat proti uloženým
 * hodnotám. Navíc se zavádí lehká korelace mezi souvisejícími rysy
 * (odvaha ↔ opatrnost, agresivita ↔ ochota pomoci), aby povahy působily
 * konzistentně, ne jako náhodný šum.</p>
 */
public final class PersonalityGenerator {

    private PersonalityGenerator() {
    }

    /**
     * Vygeneruje osobnost ze seedu.
     *
     * @param seed seed
     * @return osobnost
     */
    public static Personality generate(long seed) {
        RandomGenerator rng = RandomGeneratorFactory.of("L64X128MixRandom").create(seed);
        Map<Trait, Double> traits = new EnumMap<>(Trait.class);
        for (Trait trait : Trait.values()) {
            traits.put(trait, sample(rng));
        }
        // Korelace: odvážní boti bývají méně opatrní, agresivní méně ochotní.
        traits.computeIfPresent(Trait.CAUTION,
                (t, v) -> blend(v, 1.0 - traits.get(Trait.COURAGE), 0.35));
        traits.computeIfPresent(Trait.HELPFULNESS,
                (t, v) -> blend(v, 1.0 - traits.get(Trait.AGGRESSION), 0.30));
        traits.computeIfPresent(Trait.LAZINESS,
                (t, v) -> blend(v, 1.0 - traits.get(Trait.CURIOSITY), 0.20));
        return new PersonalityImpl(seed, traits);
    }

    private static double sample(RandomGenerator rng) {
        double value = rng.nextGaussian(0.5, 0.18);
        return Math.max(0.05, Math.min(0.95, value));
    }

    private static double blend(double base, double influence, double strength) {
        double blended = base * (1.0 - strength) + influence * strength;
        return Math.max(0.05, Math.min(0.95, blended));
    }
}
