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
    // "sell" tu dřív chybělo: „zbohatnutí" táhlo jen těžbu, tedy ražbu peněz
    // z ničeho, a obchod se na cestě k bohatství vůbec neuplatnil.
    RICH("zbohatnout", Set.of("mine", "trade", "farm", "fish", "stash", "sell")),
    /** Chce dobýt Nether a povýšit výbavu na netherit. */
    NETHERITE("mít netheritovou výbavu", Set.of("nether", "mine", "smelt", "craft", "smith")),
    /** Chce skolit ender draka. */
    DRAGON_SLAYER("skolit ender draka",
            // „explore" táhne krok „najít portál" – jiný cíl ho splnit neumí
            // (pasivní sken portálů běží při toulkách po světě).
            Set.of("mine", "smelt", "craft", "explore", "end-travel", "dragon-fight"));

    /** Násobič utility cílů, které k ambici vedou (dokud není splněná). */
    private static final double DRIVE = 1.25;
    /**
     * Silnější násobič pro cíle, které posouvají PRÁVĚ AKTUÁLNÍ krok ambice
     * ({@link #frontierGoals}). Obecný balík {@link #drivenGoals} táhne všechno
     * stejně, takže bot u kroku „sehnat vlnu a vyrobit postel" pořád stejně
     * boostoval i {@code house} (hotový) a {@code shear} (nutný) vůbec – řetěz
     * se pak dokončil jen náhodou. Frontier drží prioritu na tom, co krok
     * reálně odemyká, včetně upstream záložek (např. těžba železa pro nůžky).
     */
    private static final double DRIVE_FRONTIER = 1.6;

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
        return ranked(personality).getFirst();
    }

    /**
     * Ambice seřazené podle povahy (od nejlákavější). Používá se při volbě
     * další ambice po splnění té současné – druhý sen odpovídá druhému
     * nejsilnějšímu rysu a povaha se vývojem mění.
     *
     * @param personality osobnost bota
     * @return všechny ambice, nejlákavější první
     */
    public static java.util.List<Ambition> ranked(Personality personality) {
        record Scored(Ambition ambition, double score) {
        }
        return java.util.stream.Stream.of(
                        new Scored(RICH, personality.trait(Trait.GREED)),
                        new Scored(COZY_HOME, personality.trait(Trait.CAUTION)),
                        new Scored(FULL_IRON, personality.trait(Trait.COURAGE)),
                        // Netherit chce odvahu i chamtivost zároveň – průměr
                        // drží sen o pekle typicky až jako druhý (po základech).
                        new Scored(NETHERITE, (personality.trait(Trait.COURAGE)
                                + personality.trait(Trait.GREED)) / 2),
                        // Drak je „druhý sen" odvážných: železná výbava
                        // (COURAGE ×1.0) vede vždy a u chamtivých má přednost
                        // i netherit (průměr s GREED) – faktor 0.9 drží draka
                        // pod oběma, dokud odvaha není jasně dominantní rys.
                        new Scored(DRAGON_SLAYER, personality.trait(Trait.COURAGE) * 0.9))
                .sorted(java.util.Comparator.comparingDouble(Scored::score).reversed())
                .map(Scored::ambition)
                .toList();
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
     * Stav bota pro výpočet postupu – vše, co milníky ambicí potřebují.
     *
     * @param needs          potřeby (inventář)
     * @param hasHouse       má dům (paměť HOME typu house)
     * @param hasBedItem     má postel (item)
     * @param balance        zůstatek peněženky
     * @param endGeared      má výbavu na End (železný meč + většina brnění)
     * @param hasBow         má luk nebo kuši
     * @param knowsEndPortal zná portál do Endu (PORTAL paměť)
     * @param dragonSlain    už skolil draka (TROPHY paměť)
     */
    public record State(BotNeeds needs, boolean hasHouse, boolean hasBedItem,
                        double balance, boolean endGeared, boolean hasBow,
                        boolean knowsEndPortal, boolean dragonSlain) {
    }

    /**
     * Spočítá postup ze stavu bota.
     *
     * @param state stav bota (inventář, dům, peníze, znalosti Endu)
     * @return postup k ambici
     */
    public Progress progress(State state) {
        BotNeeds needs = state.needs();
        boolean hasHouse = state.hasHouse();
        boolean hasBedItem = state.hasBedItem();
        double balance = state.balance();
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
            case NETHERITE -> {
                if (needs.pickaxeTier() >= 6) {
                    yield new Progress(3, 3, "splněno – netherit!");
                }
                // Kroky jsou kumulativní: pazourek bez diamantového krumpáče
                // ještě není „portál na dosah" (obsidián by nevytěžil).
                if (needs.pickaxeTier() >= 5 && needs.hasFlintKit()) {
                    yield new Progress(2, 3,
                            "postavit portál a přinést starodávné trosky");
                }
                if (needs.pickaxeTier() >= 5) {
                    yield new Progress(1, 3, "sehnat obsidián a křesadlo");
                }
                yield new Progress(0, 3, "dopracovat se k diamantovému krumpáči");
            }
            case DRAGON_SLAYER -> {
                if (state.dragonSlain()) {
                    yield new Progress(4, 4, "splněno – drak je poražen!");
                }
                if (state.endGeared() && state.hasBow() && state.knowsEndPortal()) {
                    yield new Progress(3, 4, "vypravit se do Endu a skolit draka");
                }
                if (state.endGeared() && state.hasBow()) {
                    yield new Progress(2, 4, "najít portál do Endu");
                }
                if (state.endGeared()) {
                    yield new Progress(1, 4, "sehnat luk a šípy");
                }
                yield new Progress(0, 4, "vykovat si železnou výbavu");
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
     * Krokově uvědomělý násobič: cíle posouvající aktuální krok dostanou
     * {@link #DRIVE_FRONTIER}, zbytek balíku slabší {@link #DRIVE}. Frontier smí
     * obsahovat i cíle mimo {@link #drivenGoals} (např. {@code shear} u kroku
     * „postel") – jinak by je ambice netáhla vůbec.
     *
     * @param goalId   id cíle
     * @param progress aktuální postup ambice ({@code null} = žádná ambice)
     * @return násobič utility
     */
    public double weight(String goalId, Progress progress) {
        if (progress == null || progress.complete()) {
            return 1.0;
        }
        if (frontierGoals(progress.step()).contains(goalId)) {
            return DRIVE_FRONTIER;
        }
        return drivenGoals.contains(goalId) ? DRIVE : 1.0;
    }

    /**
     * Cíle, které odemykají daný krok ambice – včetně upstream záložek, takže
     * když je bezprostřední krok zablokovaný nedostatkem materiálu, táhne se
     * jeho prerekvizita (vlastní brána cíle už vybere ten právě proveditelný).
     *
     * @param step index splněných kroků (0 = úplný začátek)
     * @return frontier cíle kroku
     */
    private Set<String> frontierGoals(int step) {
        return switch (this) {
            case FULL_IRON -> switch (step) {
                case 0, 1 -> Set.of("mine", "craft");
                case 2 -> Set.of("mine");
                default -> Set.of("smelt", "craft");
            };
            case COZY_HOME -> switch (step) {
                case 0 -> Set.of("mine", "collect");
                case 1 -> Set.of("house");
                // Postel: ostříhat (má-li nůžky), vyrobit (nůžky/postel), a jako
                // upstream záložka těžit železo na nůžky.
                default -> Set.of("shear", "craft", "mine");
            };
            case RICH -> Set.of("mine", "sell", "trade", "fish", "farm");
            case NETHERITE -> switch (step) {
                case 0 -> Set.of("mine", "smelt", "craft");
                case 1 -> Set.of("mine", "craft");
                default -> Set.of("nether", "mine");
            };
            case DRAGON_SLAYER -> switch (step) {
                case 0 -> Set.of("mine", "smelt", "craft", "smith");
                case 1 -> Set.of("craft", "hunt");
                case 2 -> Set.of("explore", "stronghold");
                default -> Set.of("end-travel", "dragon-fight");
            };
        };
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
