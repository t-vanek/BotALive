package dev.botalive.core.util;

import java.util.List;
import java.util.random.RandomGenerator;
import java.util.random.RandomGeneratorFactory;

/**
 * Zdroj náhody vázaný na konkrétního bota.
 *
 * <p>Každý bot má vlastní instanci (seedovanou z jeho identity), takže se boti
 * nikdy nechovají synchronně – všechna zpoždění, chyby a mikropohyby čerpají
 * odsud. Není thread-safe; instance je confinovaná na tick vlákno bota.</p>
 */
public final class BotRandom {

    private final RandomGenerator rng;

    /**
     * @param seed deterministický seed (typicky odvozený z UUID bota)
     */
    public BotRandom(long seed) {
        this.rng = RandomGeneratorFactory.<RandomGenerator.SplittableGenerator>of("L64X128MixRandom")
                .create(seed);
    }

    /** @return rovnoměrné double v [0,1) */
    public double next() {
        return rng.nextDouble();
    }

    /** @return rovnoměrné double v [min,max) */
    public double range(double min, double max) {
        return min + rng.nextDouble() * (max - min);
    }

    /** @return rovnoměrné int v [min,max] včetně */
    public int rangeInt(int min, int max) {
        return rng.nextInt(min, max + 1);
    }

    /** @return gaussovská hodnota se zadaným středem a odchylkou */
    public double gaussian(double mean, double stdDev) {
        return rng.nextGaussian(mean, stdDev);
    }

    /**
     * @param probability šance 0–1
     * @return {@code true} s danou pravděpodobností
     */
    public boolean chance(double probability) {
        return rng.nextDouble() < probability;
    }

    /**
     * @param list neprázdný seznam
     * @param <T>  typ prvku
     * @return náhodný prvek seznamu
     */
    public <T> T pick(List<T> list) {
        return list.get(rng.nextInt(list.size()));
    }

    /** @return podkladový generátor (pro API vyžadující RandomGenerator) */
    public RandomGenerator generator() {
        return rng;
    }
}
