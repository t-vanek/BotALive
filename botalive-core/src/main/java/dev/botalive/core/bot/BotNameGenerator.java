package dev.botalive.core.bot;

import java.util.List;
import java.util.Set;
import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Predicate;

/**
 * Generátor herních jmen botů.
 *
 * <p>Cílem je populace, která vypadá jako hráči na skutečném serveru: část
 * používá obyčejná křestní jména, většina ale herní přezdívky (nicky). Když
 * administrátor nedodá vlastní pool, generátor <b>míchá</b> reálná jména
 * s procedurálně skládanými nicky ({@code xX_Reaper_Xx}, {@code ShadowWolf42},
 * {@code iSn1per}, {@code tomas_cz}, {@code Honza2011}…). Dodá-li vlastní pool,
 * respektuje se doslova (jen s číselnou příponou při kolizi).</p>
 *
 * <p>Všechna jména splňují pravidla Minecraft účtu: {@code [A-Za-z0-9_]{3,16}}.</p>
 */
public final class BotNameGenerator {

    /** Podíl (v %) čistě reálných jmen v míchaném režimu; zbytek jsou herní nicky. */
    private static final int REAL_NAME_PERCENT = 45;
    /** Šance (v %), že i reálné jméno dostane herní nádech (číslo/suffix). */
    private static final int REAL_NAME_FLAVOR_PERCENT = 40;

    /** Výchozí pool reálných křestních jmen, když administrátor žádný nedodá. */
    private static final List<String> DEFAULT_POOL = List.of(
            "Martin", "Petr", "Honza", "Lukas", "Tomas", "Jakub", "Ondra", "Vojta",
            "Filip", "David", "Adam", "Matej", "Karel", "Standa", "Radek", "Milan",
            "Zdenek", "Pavel", "Michal", "Roman", "Alex", "Sam", "Max", "Leo",
            "Nikol", "Katka", "Lucka", "Anicka", "Terka", "Bara", "Elis", "Sofie"
    );

    /** Přídavná jména do skládaných nicků (krátká, ať se vejdou do 16 znaků). */
    private static final List<String> ADJECTIVES = List.of(
            "Dark", "Shadow", "Silent", "Toxic", "Epic", "Frozen", "Rapid", "Ghost",
            "Savage", "Mad", "Lone", "Cyber", "Pixel", "Noob", "Pro", "Swift",
            "Grim", "Void", "Neon", "Iron", "Blaze", "Storm", "Angry", "Lucky",
            "Sneaky", "Royal", "Crazy", "Elite", "Hyper", "Turbo"
    );

    /** Podstatná jména do skládaných nicků. */
    private static final List<String> NOUNS = List.of(
            "Wolf", "Sniper", "Gamer", "Slayer", "Ninja", "Dragon", "Reaper", "Hunter",
            "Miner", "Creeper", "Blade", "King", "Lord", "Beast", "Fox", "Raven",
            "Tiger", "Shark", "Yeti", "Golem", "Knight", "Mage", "Bear", "Hawk",
            "Viper", "Demon", "Phantom", "Wizard", "Warrior", "Panda"
    );

    /** Předpony ve stylu herních přezdívek. */
    private static final List<String> PREFIXES = List.of(
            "x", "i", "The", "Mr", "Its", "Real", "Yt", "Ez", "Its", "Sir"
    );

    /** Textové přípony (podpisové zkratky komunit a serverů). */
    private static final List<String> TAG_SUFFIXES = List.of(
            "YT", "TTV", "XD", "HD", "CZ", "SK", "GG", "OP"
    );

    /** „Kulatá" čísla, která hráči rádi lepí za nick. */
    private static final List<String> NUMBER_SUFFIXES = List.of(
            "07", "42", "69", "99", "13", "21", "23", "360", "420", "777",
            "2010", "2011", "2012", "123", "007"
    );

    /** Styl generovaných jmen (uplatní se jen u výchozího poolu). */
    public enum Style {
        /** Reálná křestní jména i herní nicky (výchozí). */
        MIXED,
        /** Jen reálná křestní jména. */
        REAL,
        /** Jen skládané herní přezdívky. */
        GAMER;

        static Style from(String raw) {
            if (raw != null) {
                for (Style s : values()) {
                    if (s.name().equalsIgnoreCase(raw.trim())) {
                        return s;
                    }
                }
            }
            return MIXED;
        }
    }

    private final List<String> pool;
    /** True, když se používá vestavěný pool – jen tehdy se uplatní {@link #style}. */
    private final boolean useDefault;
    private final Style style;

    /**
     * @param configuredPool pool z konfigurace (prázdný = výchozí + míchané nicky)
     */
    public BotNameGenerator(List<String> configuredPool) {
        this(configuredPool, Style.MIXED);
    }

    /**
     * @param configuredPool pool z konfigurace (prázdný = výchozí)
     * @param style          styl jmen pro výchozí pool ({@code mixed|real|gamer})
     */
    public BotNameGenerator(List<String> configuredPool, String style) {
        this(configuredPool, Style.from(style));
    }

    /**
     * @param configuredPool pool z konfigurace (prázdný = výchozí)
     * @param style          styl jmen pro výchozí pool
     */
    public BotNameGenerator(List<String> configuredPool, Style style) {
        this.useDefault = configuredPool == null || configuredPool.isEmpty();
        this.pool = useDefault ? DEFAULT_POOL : List.copyOf(configuredPool);
        this.style = style == null ? Style.MIXED : style;
    }

    /**
     * Vygeneruje jméno, které projde filtrem obsazenosti.
     *
     * @param taken predikát „jméno už je obsazené"
     * @return volné jméno (3–16 znaků, {@code [A-Za-z0-9_]})
     */
    public String next(Predicate<String> taken) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        // Nejdřív zkusit „čisté" kandidáty (reálné jméno nebo herní nick).
        for (int attempt = 0; attempt < 24; attempt++) {
            String name = candidate(random);
            if (isValid(name) && !taken.test(name)) {
                return name;
            }
        }
        // Pak vynutit unikátnost číselnou příponou (Honza27, ShadowWolf27…).
        for (int attempt = 0; attempt < 64; attempt++) {
            String base = suffixBase(random);
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

    /** Kandidát podle poolu a stylu. Vlastní pool má přednost před stylem. */
    private String candidate(ThreadLocalRandom random) {
        if (!useDefault) {
            return pool.get(random.nextInt(pool.size()));
        }
        return switch (style) {
            case REAL -> realName(random);
            case GAMER -> gamerNick(random);
            case MIXED -> generateMixed(random);
        };
    }

    /** Základ pro číselnou příponu při kolizi – drží styl jmen. */
    private String suffixBase(ThreadLocalRandom random) {
        if (!useDefault) {
            return pool.get(random.nextInt(pool.size()));
        }
        return style == Style.GAMER ? pick(NOUNS, random) : realName(random);
    }

    /** Reálné jméno (~{@value #REAL_NAME_PERCENT} %) nebo herní nick. */
    private String generateMixed(ThreadLocalRandom random) {
        if (random.nextInt(100) < REAL_NAME_PERCENT) {
            String name = realName(random);
            if (random.nextInt(100) < REAL_NAME_FLAVOR_PERCENT) {
                name = flavorReal(name, random);
            }
            return name;
        }
        String nick = gamerNick(random);
        // Pojistka délky/validity – při přetečení spadni na holé reálné jméno.
        return isValid(nick) ? nick : realName(random);
    }

    private String realName(ThreadLocalRandom random) {
        return DEFAULT_POOL.get(random.nextInt(DEFAULT_POOL.size()));
    }

    /** Přidá reálnému jménu herní nádech: číslo, tag, nebo „_cz" podpis. */
    private String flavorReal(String name, ThreadLocalRandom random) {
        return switch (random.nextInt(4)) {
            case 0 -> fit(name + pick(NUMBER_SUFFIXES, random));
            case 1 -> fit(name + pick(TAG_SUFFIXES, random));
            case 2 -> fit(name + "_" + pick(TAG_SUFFIXES, random).toLowerCase());
            default -> fit(name.toLowerCase() + "_" + pick(NUMBER_SUFFIXES, random));
        };
    }

    /** Poskládá herní přezdívku jedním z běžných stylů. */
    private String gamerNick(ThreadLocalRandom random) {
        String adj = pick(ADJECTIVES, random);
        String noun = pick(NOUNS, random);
        return switch (random.nextInt(7)) {
            // AdjektivumPodstatné (+ občas číslo): ShadowWolf, DarkSniper42
            case 0 -> fit(adj + noun + (random.nextBoolean() ? pick(NUMBER_SUFFIXES, random) : ""));
            // xX_Slovo_Xx: xX_Reaper_Xx, Xx_Tomas_xX
            case 1 -> {
                String core = random.nextBoolean() ? noun : realName(random);
                yield random.nextBoolean() ? fit("xX_" + core + "_Xx") : fit("Xx_" + core + "_xX");
            }
            // Předpona + podstatné: iNinja, TheDragon, MrSlayer, ProMiner
            case 2 -> fit(pick(PREFIXES, random) + noun);
            // Podstatné + číslo: Sniper420, Wolf99
            case 3 -> fit(noun + pick(NUMBER_SUFFIXES, random));
            // lowercase slovo_slovo / slovo_cz: dark_wolf, void_king
            case 4 -> fit((adj + "_" + noun).toLowerCase());
            // Leetspeak varianta: Sh4dowW0lf, Pr0Gamer, T0xicN1nja
            case 5 -> fit(leet(adj + noun, random));
            // Reálné jméno v herním kabátě: TomasYT, Honza2011, Filip_CZ
            default -> flavorReal(realName(random), random);
        };
    }

    /** Lehký leetspeak – nahradí část samohlásek/číslic, ať zůstane čitelný. */
    private static String leet(String s, ThreadLocalRandom random) {
        StringBuilder sb = new StringBuilder(s.length());
        for (char c : s.toCharArray()) {
            char lc = Character.toLowerCase(c);
            char repl = switch (lc) {
                case 'o' -> '0';
                case 'e' -> '3';
                case 'i' -> '1';
                case 'a' -> '4';
                case 's' -> '5';
                case 't' -> '7';
                default -> c;
            };
            // Nahradit jen občas, ať to není přehnané.
            sb.append(repl != c && random.nextInt(100) < 60 ? repl : c);
        }
        return sb.toString();
    }

    private static String pick(List<String> list, ThreadLocalRandom random) {
        return list.get(random.nextInt(list.size()));
    }

    /** Ořízne na max. 16 znaků (Minecraft limit); kratší nechá být. */
    private static String fit(String name) {
        return name.length() <= 16 ? name : name.substring(0, 16);
    }

    private static boolean isValid(String name) {
        return name.matches("[A-Za-z0-9_]{3,16}");
    }

    /** @return sada výchozích reálných jmen (pro dokumentaci/konfiguraci) */
    public static Set<String> defaultPool() {
        return Set.copyOf(DEFAULT_POOL);
    }
}
