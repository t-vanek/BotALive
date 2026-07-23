package dev.botalive.core.ai;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;

/**
 * Naučená „hybnost" cílů – jediná zpětná vazba z výsledků v jinak a‑priori
 * systému utility (role, ambice, rytmus, nálada… jsou pevné mapy). Když bot
 * <b>úspěšně dokončí</b> produktivní cíl, dostane ten cíl malou hybnost, která
 * časem slábne. Bot tak „chytí grif" na práci, která mu v jeho situaci vychází
 * (dobrý terén na těžbu, blízko vesnice na stavbu…), aniž by se rozbila
 * vyváženost – bonus je malý a klesavý.
 *
 * <p>Reflexní a údržbové cíle (jídlo, spánek, boj, útěk, domů…) se záměrně
 * neposilují: dokončují se pořád a bonus by je nechal monopolizovat. Posiluje
 * se jen zvolená práce. Selhání se schválně nepenalizuje – „přepnul jsem na
 * lepší cíl" není spolehlivý signál selhání a penalizace by rozhodování
 * rozhoupala.</p>
 *
 * <p>Čistá třída bez závislostí, jednovláknový přístup z rozhodování mozku;
 * metody jsou {@code synchronized} pro jistotu (nízký provoz).</p>
 */
public final class GoalMomentum {

    /** Přírůstek hybnosti za jedno úspěšné dokončení (~3 úspěchy = strop). */
    private static final double SUCCESS_STEP = 0.34;
    /** Slábnutí za jeden rozhodovací krok (poločas ~35 s při intervalu 5 ticků). */
    private static final double DECAY = 0.995;
    /** Maximální bonus utility z plné hybnosti (drží se malý – jen vychýlení). */
    private static final double BONUS = 0.15;
    /** Pod touto hodnotou se hybnost zahodí (úklid mapy). */
    private static final double FLOOR = 0.02;

    /** Produktivní cíle, které se učí (zvolená práce, ne reflexy). */
    private static final Set<String> PRODUCTIVE = Set.of(
            "mine", "house", "craft", "farm", "fish", "smelt", "sell", "trade",
            "communal-build", "hunt", "enchant", "smith", "brew", "stash", "shear",
            "nether", "compost");

    private final Map<String, Double> momentum = new HashMap<>();

    /**
     * Úspěšně dokončený produktivní cíl → přidá hybnost (jiné cíle ignoruje).
     *
     * @param goalId dokončený cíl
     */
    public synchronized void reinforce(String goalId) {
        if (!PRODUCTIVE.contains(goalId)) {
            return;
        }
        momentum.merge(goalId, SUCCESS_STEP, (a, b) -> Math.min(1.0, a + b));
    }

    /** Jeden rozhodovací krok – veškerá hybnost o kus slábne. */
    public synchronized void decay() {
        momentum.replaceAll((id, value) -> value * DECAY);
        momentum.values().removeIf(value -> value < FLOOR);
    }

    /**
     * Násobič utility z naučené hybnosti.
     *
     * @param goalId cíl
     * @return {@code 1.0} bez hybnosti, nejvýš {@code 1 + BONUS}
     */
    public synchronized double weight(String goalId) {
        return 1.0 + momentum.getOrDefault(goalId, 0.0) * BONUS;
    }
}
