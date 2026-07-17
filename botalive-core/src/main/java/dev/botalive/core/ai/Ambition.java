package dev.botalive.core.ai;

import dev.botalive.api.personality.Personality;
import dev.botalive.api.personality.Trait;

import java.util.Map;
import java.util.Set;

/**
 * Životní ambice – dlouhodobý projekt, který dává dnům bota směr.
 *
 * <p>Utility AI rozhoduje „co teď"; ambice říká „kam tím vším mířím".
 * Vybírá se jednou podle osobnosti, persistuje se v paměti
 * ({@code MemoryKind.AMBITION}) a projevuje se dvojím způsobem: jemně
 * zvýhodňuje cíle, které k ní vedou (multiplikátor v mozku), a je vidět –
 * {@code /botalive goal} ukazuje aktuální krok. Milníky se počítají čistě
 * ze stavu (inventář, paměť, peněženka), takže postup přežije restart
 * automaticky.</p>
 */
public enum Ambition {

    /** Chce plnou železnou výbavu. */
    FULL_IRON("mít železnou výbavu", Set.of("mine", "smelt", "craft")),
    /** Chce útulný domov. */
    COZY_HOME("mít útulný domov", Set.of("house", "mine", "craft", "hunt")),
    /** Chce zbohatnout. */
    RICH("zbohatnout", Set.of("mine", "trade", "farm", "fish", "stash"));

    /** Násobič utility cílů, které k ambici vedou (dokud není splněná). */
    private static final double DRIVE = 1.25;

    private final String label;
    private final Set<String> drivenGoals;

    Ambition(String label, Set<String> drivenGoals) {
        this.label = label;
        this.drivenGoals = drivenGoals;
    }

    /** @return lidský název ambice */
    public String label() {
        return label;
    }

    /**
     * Vybere ambici podle osobnosti (deterministicky z dominantního rysu).
     *
     * @param personality osobnost bota
     * @return ambice
     */
    public static Ambition pick(Personality personality) {
        double greed = personality.trait(Trait.GREED);
        double caution = personality.trait(Trait.CAUTION);
        double courage = personality.trait(Trait.COURAGE);
        if (greed >= caution && greed >= courage) {
            return RICH;
        }
        if (caution >= courage) {
            return COZY_HOME;
        }
        return FULL_IRON;
    }

    /**
     * Postup k ambici.
     *
     * @param step  splněné kroky
     * @param total kroků celkem
     * @param label popis aktuálního kroku (u splněné ambice „splněno")
     */
    public record Progress(int step, int total, String label) {

        /** @return {@code true} pokud je ambice splněná */
        public boolean complete() {
            return step >= total;
        }
    }

    /**
     * Spočítá postup ze stavu bota.
     *
     * @param needs      potřeby (inventář)
     * @param hasHouse   má dům (paměť HOME typu house)
     * @param hasBedItem má postel (item)
     * @param balance    zůstatek peněženky
     * @return postup k ambici
     */
    public Progress progress(BotNeeds needs, boolean hasHouse, boolean hasBedItem,
                             double balance) {
        return switch (this) {
            case FULL_IRON -> {
                if (needs.pickaxeTier() >= 4) {
                    yield new Progress(4, 4, "splněno – mám železnou výbavu!");
                }
                if (needs.hasIronMaterial()) {
                    yield new Progress(3, 4, "přetavit železo a vyrobit nástroje");
                }
                if (needs.pickaxeTier() >= 3) {
                    yield new Progress(2, 4, "najít a vytěžit železnou rudu");
                }
                if (needs.pickaxeTier() >= 1) {
                    yield new Progress(1, 4, "vyrobit kamenné nástroje");
                }
                yield new Progress(0, 4, "sehnat dřevo a základní nástroje");
            }
            case COZY_HOME -> {
                if (hasHouse && hasBedItem) {
                    yield new Progress(3, 3, "splněno – mám domov s postelí!");
                }
                if (hasHouse) {
                    yield new Progress(2, 3, "sehnat vlnu a vyrobit postel");
                }
                if (needs.buildingBlocks() >= 50) {
                    yield new Progress(1, 3, "najít místo a postavit dům");
                }
                yield new Progress(0, 3, "nasbírat stavební materiál – dřevo a kámen");
            }
            case RICH -> {
                if (balance >= 500) {
                    yield new Progress(3, 3, "splněno – jsem za vodou!");
                }
                if (balance >= 250) {
                    yield new Progress(2, 3, "našetřit 500");
                }
                if (balance >= 100) {
                    yield new Progress(1, 3, "našetřit 250");
                }
                yield new Progress(0, 3, "našetřit první stovku");
            }
        };
    }

    /**
     * Násobič utility pro cíl (ambice táhne related cíle, dokud není splněná).
     *
     * @param goalId   id cíle
     * @param complete ambice už splněná?
     * @return násobič (1.0 mimo ambici)
     */
    public double weight(String goalId, boolean complete) {
        return !complete && drivenGoals.contains(goalId) ? DRIVE : 1.0;
    }

    /**
     * @param stored hodnota z paměti (data {@code type})
     * @return ambice, nebo {@code null} při neznámé hodnotě
     */
    public static Ambition parse(String stored) {
        if (stored == null) {
            return null;
        }
        for (Ambition ambition : values()) {
            if (ambition.name().equalsIgnoreCase(stored)) {
                return ambition;
            }
        }
        return null;
    }
}
