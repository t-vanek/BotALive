package dev.botalive.core.ai;

import dev.botalive.api.personality.Personality;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.personality.PersonalityEvolution.BotExperience;

import java.util.EnumMap;
import java.util.Locale;
import java.util.Map;

/**
 * Nálada bota – krátkodobý emoční stav mezi celoživotní osobností a per-tik
 * utilitou cílů (viz docs/BOT_LIFE.md).
 *
 * <p>Čtyři emoce (strach, vztek, spokojenost, samota), každá 0–1. Sytí je
 * <b>prožitky</b> (tytéž události jako vývoj osobnosti, ale rychle a
 * odeznívavě, ne trvalým driftem) a <b>tělo</b> (vitály, hrozby, samota).
 * Osobnost škáluje reaktivitu – opatrný se bojí snáz, agresivní se vzteká snáz.
 * Emoce {@link #decay() odeznívají} zpět ke klidu.</p>
 *
 * <p>Mozek náladu čte přes {@link #modulate(String)}: jemný násobič utility
 * (á la {@code DayRhythm}), jehož vliv škáluje intenzita emoce – v klidu vrací
 * 1.0, takže chování zůstává beze změny. Třída je čistá a jednotkově testovaná.</p>
 */
public final class BotMood {

    /** Základní emoce bota. */
    public enum Emotion { FEAR, ANGER, CONTENT, LONELY }

    /** Násobiče utility cílů podle emoce (mírné, 0.6–1.6; á la DayRhythm). */
    private static final Map<Emotion, Map<String, Double>> MODULATION = Map.of(
            // Map.ofEntries – strach cílí na víc než 10 cílů (strop Map.of).
            Emotion.FEAR, Map.ofEntries(
                    Map.entry("survive", 1.6), Map.entry("escape", 1.6),
                    Map.entry("shelter", 1.4), Map.entry("home", 1.4),
                    Map.entry("sleep", 1.2), Map.entry("creeper-dodge", 1.4),
                    Map.entry("explore", 0.6), Map.entry("mine", 0.7),
                    Map.entry("combat", 0.7), Map.entry("pvp", 0.6),
                    Map.entry("hunt", 0.7), Map.entry("nether", 0.6)),
            Emotion.ANGER, Map.of(
                    "combat", 1.5, "pvp", 1.6, "rob", 1.4, "war-raid", 1.4,
                    "guard", 1.2, "hunt", 1.2,
                    "socialize", 0.7, "share", 0.7, "trade", 0.8, "reconcile", 0.7),
            Emotion.CONTENT, Map.of(
                    "socialize", 1.4, "share", 1.4, "house", 1.3, "communal-build", 1.3,
                    "explore", 1.2, "reconcile", 1.3, "tame", 1.2),
            Emotion.LONELY, Map.of(
                    "socialize", 1.6, "follow", 1.4, "share", 1.3, "trade", 1.2,
                    "reconcile", 1.2,
                    "mine", 0.8, "explore", 0.9));

    /** Násobič odeznívání za jeden krok ({@link #decay()}). */
    private static final double DECAY = 0.94;

    /** Pod tuto intenzitu se emoce považuje za odezněnou (nula). */
    private static final double FLOOR = 0.02;

    /** Intenzita dominantní emoce, pod kterou je bot „v klidu". */
    private static final double CALM_THRESHOLD = 0.15;

    private final EnumMap<Emotion, Double> levels = new EnumMap<>(Emotion.class);

    /** Nový bot je v klidu (všechny emoce na nule). */
    public BotMood() {
        for (Emotion emotion : Emotion.values()) {
            levels.put(emotion, 0.0);
        }
    }

    /**
     * @param emotion emoce
     * @return intenzita 0–1
     */
    public double level(Emotion emotion) {
        return levels.get(emotion);
    }

    /**
     * Přičte (nebo odečte) k emoci, ořízne do 0–1.
     *
     * @param emotion emoce
     * @param amount  změna (může být záporná)
     */
    public void feel(Emotion emotion, double amount) {
        levels.merge(emotion, amount, (old, delta) -> clamp(old + delta));
    }

    /** Nechá všechny emoce jeden krok odeznít ke klidu. */
    public void decay() {
        for (Emotion emotion : Emotion.values()) {
            double faded = levels.get(emotion) * DECAY;
            levels.put(emotion, faded < FLOOR ? 0.0 : faded);
        }
    }

    /**
     * @return nejsilnější emoce nad prahem klidu, nebo {@code null} (klid)
     */
    public Emotion dominant() {
        Emotion best = null;
        double bestLevel = CALM_THRESHOLD;
        for (Emotion emotion : Emotion.values()) {
            double level = levels.get(emotion);
            if (level > bestLevel) {
                bestLevel = level;
                best = emotion;
            }
        }
        return best;
    }

    /**
     * Násobič utility cíle podle aktuální nálady. Vliv každé emoce škáluje její
     * intenzita, takže v klidu (nulové emoce) vrací přesně 1.0.
     *
     * @param goalId id cíle
     * @return násobič užitečnosti
     */
    public double modulate(String goalId) {
        double multiplier = 1.0;
        for (Emotion emotion : Emotion.values()) {
            double intensity = levels.get(emotion);
            if (intensity <= 0) {
                continue;
            }
            double weight = MODULATION.get(emotion).getOrDefault(goalId, 1.0);
            multiplier *= 1.0 + (weight - 1.0) * intensity;
        }
        return multiplier;
    }

    /**
     * Rychlá emoční reakce na prožitek (tentýž zdroj jako vývoj osobnosti).
     * Intenzitu strachu a vzteku škáluje povaha.
     *
     * @param experience  prožitek
     * @param personality osobnost bota (reaktivita)
     */
    public void reactTo(BotExperience experience, Personality personality) {
        double fear = 0.6 + personality.trait(Trait.CAUTION) * 0.8;
        double anger = 0.6 + personality.trait(Trait.AGGRESSION) * 0.8;
        switch (experience) {
            case DEATH -> {
                feel(Emotion.FEAR, 0.6 * fear);
                feel(Emotion.CONTENT, -0.5);
            }
            case WAS_ROBBED -> {
                feel(Emotion.ANGER, 0.5 * anger);
                feel(Emotion.FEAR, 0.2 * fear);
            }
            case ROB_SUCCESS -> feel(Emotion.CONTENT, 0.2);
            case STEAL_SUCCESS -> {
                feel(Emotion.CONTENT, 0.15);
                feel(Emotion.FEAR, 0.1 * fear);
            }
            case SHARE_GIVEN -> {
                feel(Emotion.CONTENT, 0.3);
                feel(Emotion.LONELY, -0.4);
            }
            case HOUSE_BUILT -> feel(Emotion.CONTENT, 0.5);
            case PVP_KILL -> {
                feel(Emotion.CONTENT, 0.3);
                feel(Emotion.ANGER, -0.2);
                feel(Emotion.FEAR, -0.15);
            }
            case DRAGON_SLAIN, WITHER_SLAIN -> {
                feel(Emotion.CONTENT, 0.8);
                feel(Emotion.FEAR, -0.6);
            }
        }
    }

    /**
     * Průběžné signály z těla a okolí (volá se řídce, ne každý tik).
     *
     * @param health         zdraví 0–20
     * @param food           sytost 0–20
     * @param hostilesNearby počet nepřátel v okolí
     * @param companyNearby  počet hráčů/botů v okolí (společnost)
     * @param night          herní noc
     * @param sociability    společenskost bota (rychlost osamění)
     */
    public void observe(double health, int food, int hostilesNearby, int companyNearby,
                        boolean night, double sociability) {
        if (health <= 6) {
            feel(Emotion.FEAR, 0.15);
        }
        if (hostilesNearby > 0) {
            feel(Emotion.FEAR, 0.05 * Math.min(hostilesNearby, 4));
            if (night) {
                feel(Emotion.FEAR, 0.05);
            }
        }
        if (health >= 18 && food >= 16 && hostilesNearby == 0) {
            feel(Emotion.CONTENT, 0.04);
        }
        if (companyNearby > 0) {
            // Společnost zahání samotu; společenskému botovi dělá i radost.
            feel(Emotion.LONELY, -0.15 * Math.min(companyNearby, 3));
            feel(Emotion.CONTENT, 0.03 * sociability);
        } else {
            // O samotě samota roste pomalu; společenský bot ji cítí silněji.
            feel(Emotion.LONELY, 0.02 * (0.5 + sociability));
        }
    }

    /**
     * @return krátký český popis dominantní nálady pro chat a diagnostiku
     */
    public String describe() {
        Emotion dominant = dominant();
        if (dominant == null) {
            return "klid";
        }
        String name = switch (dominant) {
            case FEAR -> "strach";
            case ANGER -> "vztek";
            case CONTENT -> "spokojenost";
            case LONELY -> "samota";
        };
        return name + " " + String.format(Locale.ROOT, "%.0f %%", levels.get(dominant) * 100);
    }

    private static double clamp(double value) {
        return value < 0 ? 0 : Math.min(value, 1);
    }
}
