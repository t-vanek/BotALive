package dev.botalive.core.ai;

import java.util.Map;

/**
 * Denní rytmus bota – jemně vychyluje priority cílů podle herní denní doby.
 *
 * <p>Boti si den strukturují jako hráči: ráno pole a výroba, přes den těžba
 * a stavba, večer družení a úklid do truhel, v noci domů a spát. Násobiče
 * jsou záměrně mírné (0.6–1.6), aby osobnost a profese zůstaly hlavním
 * rozlišením – rytmus dává dnům botů tvar, nediktuje je.</p>
 *
 * <p>Každý bot má osobní posun rytmu (skřivan/sova) odvozený z lenosti:
 * líní boti vstávají později a ponocují. Třída je čistá a bez závislostí –
 * jednotkově testovatelná.</p>
 */
public final class DayRhythm {

    /** Fáze herního dne. */
    enum Phase { MORNING, DAY, EVENING, NIGHT }

    private static final Map<Phase, Map<String, Double>> WEIGHTS = Map.of(
            Phase.MORNING, Map.of(
                    "farm", 1.6, "craft", 1.3, "eat", 1.3, "fish", 1.3, "hunt", 1.2,
                    "compost", 1.4, "repair", 1.3),
            Phase.DAY, Map.of(
                    "mine", 1.4, "house", 1.5, "explore", 1.3, "trade", 1.3,
                    "hunt", 1.2, "farm", 1.1),
            Phase.EVENING, Map.of(
                    "socialize", 1.6, "home", 1.5, "stash", 1.4, "share", 1.5,
                    "craft", 1.2, "smelt", 1.3, "mine", 0.8),
            Phase.NIGHT, Map.of(
                    "home", 1.6, "shelter", 1.3, "socialize", 0.7, "mine", 0.6,
                    "explore", 0.6, "farm", 0.5, "trade", 0.6, "guard", 1.2)
    );

    /** Maximální osobní posun rytmu (ticky) – sova vs. skřivan. */
    private static final long MAX_SHIFT_TICKS = 1500;

    private final long shiftTicks;

    /**
     * @param laziness rys lenosti 0–1; líní boti mají den posunutý k večeru
     */
    public DayRhythm(double laziness) {
        this.shiftTicks = Math.round((laziness - 0.5) * 2 * MAX_SHIFT_TICKS);
    }

    /**
     * Násobič utility cíle v daný herní čas.
     *
     * @param goalId    id cíle
     * @param worldTime herní čas světa (0–23999); záporný = neznámý (bez vlivu)
     * @return násobič (1.0 pro cíle mimo tabulku fáze)
     */
    public double multiplier(String goalId, long worldTime) {
        if (worldTime < 0) {
            return 1.0;
        }
        Phase phase = phaseAt(worldTime);
        return WEIGHTS.get(phase).getOrDefault(goalId, 1.0);
    }

    /**
     * Fáze dne pro daný čas (s osobním posunem bota).
     *
     * @param worldTime herní čas světa
     * @return fáze dne
     */
    Phase phaseAt(long worldTime) {
        long time = Math.floorMod(worldTime - shiftTicks, 24_000L);
        if (time < 3_000) {
            return Phase.MORNING;
        }
        if (time < 9_500) {
            return Phase.DAY;
        }
        if (time < 12_500) {
            return Phase.EVENING;
        }
        if (time < 23_000) {
            return Phase.NIGHT;
        }
        return Phase.MORNING; // svítání
    }

    /** @return osobní posun rytmu v ticích (pro diagnostiku) */
    long shiftTicks() {
        return shiftTicks;
    }
}
