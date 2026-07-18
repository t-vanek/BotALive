package dev.botalive.core.personality;

import dev.botalive.api.personality.Trait;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Vývoj osobnosti podle prožitků – povaha botů se formuje tím, co dělají.
 *
 * <p>Komu projde krádež nebo přepadení, tomu roste chamtivost a agrese
 * a klesá ochota – a protože utility cílů z rysů vychází, začne krást
 * ochotněji („zalíbilo se mu to"). Kdo rozdává a pomáhá, tomu ochota roste.
 * Smrt učí opatrnosti, vítězství odvaze.</p>
 *
 * <p>Posuny jsou malé (setiny) a drift vůči základu ze seedu je omezen
 * ({@link #MAX_DRIFT}) – povaha se vyvíjí, jádro zůstává. Při překročení
 * prahů driftu bot svou proměnu občas okomentuje v chatu.</p>
 */
public final class PersonalityEvolution {

    /** Maximální odchylka rysu od základu ze seedu. */
    public static final double MAX_DRIFT = 0.35;

    /** Práh driftu, jehož překročení bot komentuje (a jeho násobky). */
    static final double ANNOUNCE_STEP = 0.12;

    /** Prožitky, které formují povahu. */
    public enum BotExperience {
        /** Povedená krádež z truhly. */
        STEAL_SUCCESS,
        /** Povedené přepadení. */
        ROB_SUCCESS,
        /** Rozdal jídlo potřebnému. */
        SHARE_GIVEN,
        /** Dostavěl dům. */
        HOUSE_BUILT,
        /** Zemřel. */
        DEATH,
        /** Vyhrál souboj. */
        PVP_KILL,
        /** Zjistil, že ho někdo okradl. */
        WAS_ROBBED,
        /** Skolil ender draka. */
        DRAGON_SLAIN
    }

    private static final Map<BotExperience, Map<Trait, Double>> DELTAS = Map.of(
            BotExperience.STEAL_SUCCESS, Map.of(
                    Trait.GREED, 0.020, Trait.HELPFULNESS, -0.015, Trait.CAUTION, -0.005),
            BotExperience.ROB_SUCCESS, Map.of(
                    Trait.AGGRESSION, 0.025, Trait.GREED, 0.015,
                    Trait.HELPFULNESS, -0.020, Trait.COURAGE, 0.010),
            BotExperience.SHARE_GIVEN, Map.of(
                    Trait.HELPFULNESS, 0.020, Trait.GREED, -0.015, Trait.SOCIABILITY, 0.010),
            BotExperience.HOUSE_BUILT, Map.of(
                    Trait.PATIENCE, 0.010, Trait.INTELLIGENCE, 0.005),
            BotExperience.DEATH, Map.of(
                    Trait.CAUTION, 0.020, Trait.COURAGE, -0.010),
            BotExperience.PVP_KILL, Map.of(
                    Trait.COURAGE, 0.015, Trait.AGGRESSION, 0.010),
            BotExperience.WAS_ROBBED, Map.of(
                    Trait.CAUTION, 0.015, Trait.AGGRESSION, 0.015,
                    Trait.HELPFULNESS, -0.010),
            BotExperience.DRAGON_SLAIN, Map.of(
                    Trait.COURAGE, 0.030, Trait.CAUTION, -0.010)
    );

    /**
     * Výsledek prožitku.
     *
     * @param changed       {@code true} pokud se aspoň jeden rys posunul
     * @param announcements hlášky k proměně povahy (může být prázdné)
     */
    public record Result(boolean changed, List<String> announcements) {
        static final Result NONE = new Result(false, List.of());
    }

    private PersonalityEvolution() {
    }

    /**
     * Aplikuje prožitek na osobnost.
     *
     * @param personality osobnost bota
     * @param experience  prožitek
     * @return co se změnilo + případné hlášky k proměně
     */
    public static Result apply(PersonalityImpl personality, BotExperience experience) {
        Map<Trait, Double> deltas = DELTAS.get(experience);
        if (deltas == null) {
            return Result.NONE;
        }
        Map<Trait, double[]> changed = personality.applyDeltas(deltas, MAX_DRIFT);
        if (changed.isEmpty()) {
            return Result.NONE;
        }
        List<String> announcements = new ArrayList<>(1);
        for (Map.Entry<Trait, double[]> entry : changed.entrySet()) {
            Trait trait = entry.getKey();
            double base = personality.baseTrait(trait);
            double driftBefore = entry.getValue()[0] - base;
            double driftAfter = entry.getValue()[1] - base;
            if (crossedStep(driftBefore, driftAfter)) {
                String line = announcement(trait, driftAfter > 0);
                if (line != null) {
                    announcements.add(line);
                }
            }
        }
        return new Result(true, List.copyOf(announcements));
    }

    /** Překročil drift další násobek prahu (v kterémkoli směru)? */
    private static boolean crossedStep(double before, double after) {
        int stepsBefore = (int) (Math.abs(before) / ANNOUNCE_STEP);
        int stepsAfter = (int) (Math.abs(after) / ANNOUNCE_STEP);
        return stepsAfter > stepsBefore;
    }

    /** Hláška k proměně povahy (rys + směr), nebo {@code null}. */
    private static String announcement(Trait trait, boolean up) {
        return switch (trait) {
            case GREED -> up ? "hm... brat si cizi veci, zacina me to bavit"
                    : "penize uz pro me nejsou vsechno";
            case AGGRESSION -> up ? "nasili je vlastne docela ucinny zpusob" : null;
            case HELPFULNESS -> up ? "pomahat druhym me fakt naplnuje"
                    : "starat se sam o sebe je proste jednodussi";
            case COURAGE -> up ? "uz se skoro niceho nebojim" : "neco ve mne se zlomilo...";
            case CAUTION -> up ? "priste si dam vetsi pozor" : null;
            case SOCIABILITY -> up ? "lidi kolem me zacinaji bavit" : null;
            default -> null;
        };
    }
}
