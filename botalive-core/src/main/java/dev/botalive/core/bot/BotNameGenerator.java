package dev.botalive.core.bot;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * Generátor herních jmen botů.
 *
 * <p>Bere jména z konfiguračního poolu (administrátor si může dodat vlastní);
 * při vyčerpání/kolizi přidává číselné přípony. Jména vypadají jako běžní
 * hráči – žádné „Bot_1234" prefixy, které by boty okamžitě prozradily.</p>
 */
public final class BotNameGenerator {

    /** Výchozí pool, když administrátor žádný nedodá. */
    private static final List<String> DEFAULT_POOL = List.of(
            "Martin", "Petr", "Honza", "Lukas", "Tomas", "Jakub", "Ondra", "Vojta",
            "Filip", "David", "Adam", "Matej", "Karel", "Standa", "Radek", "Milan",
            "Zdenek", "Pavel", "Michal", "Roman", "Alex", "Sam", "Max", "Leo",
            "Nikol", "Katka", "Lucka", "Anicka", "Terka", "Bara", "Elis", "Sofie"
    );

    private final List<String> pool;

    /**
     * @param configuredPool pool z konfigurace (prázdný = výchozí)
     */
    public BotNameGenerator(List<String> configuredPool) {
        this.pool = configuredPool == null || configuredPool.isEmpty()
                ? DEFAULT_POOL
                : List.copyOf(configuredPool);
    }

    /**
     * Vygeneruje jméno, které projde filtrem obsazenosti.
     *
     * @param taken predikát „jméno už je obsazené"
     * @return volné jméno (3–16 znaků)
     */
    public String next(Predicate<String> taken) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        // Nejdřív zkusit čistá jména z poolu.
        for (int attempt = 0; attempt < 16; attempt++) {
            String name = pool.get(random.nextInt(pool.size()));
            if (isValid(name) && !taken.test(name)) {
                return name;
            }
        }
        // Pak s číselnou příponou (Honza27 apod.).
        for (int attempt = 0; attempt < 64; attempt++) {
            String base = pool.get(random.nextInt(pool.size()));
            String name = base + random.nextInt(10, 100);
            if (name.length() <= 16 && isValid(name) && !taken.test(name)) {
                return name;
            }
        }
        // Krajní záchrana.
        String fallback;
        do {
            fallback = "Hrac" + random.nextInt(1000, 10_000);
        } while (taken.test(fallback));
        return fallback;
    }

    private static boolean isValid(String name) {
        return name.matches("[A-Za-z0-9_]{3,16}");
    }

    /** @return sada výchozích jmen (pro dokumentaci/konfiguraci) */
    public static Set<String> defaultPool() {
        return Set.copyOf(DEFAULT_POOL);
    }
}
