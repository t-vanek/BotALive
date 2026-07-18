package dev.botalive.core.ai;

import dev.botalive.api.ai.Goal;
import dev.botalive.api.bot.Bot;
import dev.botalive.api.event.BotGoalChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Mozek bota – utility-based výběr cílů.
 *
 * <p>Každých N ticků (konfigurovatelné) spočítá užitečnost všech cílů a vybere
 * nejvyšší. Aktivní cíl dostává hysterezi (např. ×1.15), aby chování nekmitalo
 * mezi dvěma podobně silnými cíli. Administrátor může cíl vynutit – vynucený
 * cíl běží, dokud sám neskončí.</p>
 *
 * <p>Do výběru vstupuje i drobný per-bot šum – dva boti se stejnou situací se
 * tak nerozhodnou vždy stejně.</p>
 */
public final class Brain {

    private static final Logger LOG = LoggerFactory.getLogger(Brain.class);

    private final Bot bot;
    private final List<Goal> goals;
    private final int decisionInterval;
    private final double hysteresis;
    private final dev.botalive.core.util.BotRandom rng;
    private final DayRhythm rhythm;

    /** Volatile: čtou je i cizí vlákna (příkazy, snapshoty). */
    private volatile Goal current;
    private volatile String forcedGoalId;
    private int ticksToDecision;

    /**
     * @param bot              bot
     * @param goals            instance všech cílů tohoto bota
     * @param decisionInterval perioda rozhodování (ticky)
     * @param hysteresis       bonus aktivního cíle (1.15 = +15 %)
     * @param rng              per-bot náhoda
     */
    public Brain(Bot bot, List<Goal> goals, int decisionInterval, double hysteresis,
                 dev.botalive.core.util.BotRandom rng) {
        this(bot, goals, decisionInterval, hysteresis, rng, null);
    }

    /**
     * @param rhythm denní rytmus bota, nebo {@code null} (vypnuto)
     */
    public Brain(Bot bot, List<Goal> goals, int decisionInterval, double hysteresis,
                 dev.botalive.core.util.BotRandom rng, DayRhythm rhythm) {
        this.bot = bot;
        this.goals = goals;
        this.decisionInterval = decisionInterval;
        this.hysteresis = hysteresis;
        this.rng = rng;
        this.rhythm = rhythm;
        this.ticksToDecision = rng.rangeInt(0, decisionInterval); // rozfázování mezi boty
    }

    /** @return id aktivního cíle, nebo {@code null} */
    public String currentGoalId() {
        Goal goal = current;
        return goal == null ? null : goal.id();
    }

    /** @return blokuje aktivní cíl stěhování? (schopnost cíle, ne porovnání id) */
    public boolean currentGoalBlocksRelocation() {
        Goal goal = current;
        return goal != null && goal.blocksRelocation();
    }

    /**
     * Lidsky čitelné vysvětlení, co bot právě dělá a proč (intent vrstva).
     *
     * @return věta v první osobě, nebo {@code null} když cíl nemá co říct
     */
    public String explainCurrent() {
        Goal goal = current;
        if (goal instanceof dev.botalive.core.ai.goals.AbstractGoal explainable) {
            try {
                return explainable.explain(bot);
            } catch (Exception e) {
                return null; // vysvětlení nesmí nikdy shodit chat/příkaz
            }
        }
        return null;
    }

    /**
     * Vynutí cíl podle id.
     *
     * @param goalId id cíle, nebo {@code null} pro zrušení vynucení
     * @return {@code true} pokud cíl existuje
     */
    public boolean forceGoal(String goalId) {
        if (goalId == null) {
            forcedGoalId = null;
            return true;
        }
        boolean exists = goals.stream().anyMatch(g -> g.id().equals(goalId));
        if (exists) {
            forcedGoalId = goalId;
            ticksToDecision = 0;
        }
        return exists;
    }

    /**
     * Přehled užitečností všech cílů (pro {@code /botalive goal}).
     *
     * @return mapa id cíle → aktuální utility
     */
    public Map<String, Double> utilitySnapshot() {
        return goals.stream().collect(Collectors.toMap(Goal::id, g -> {
            try {
                return g.utility(bot);
            } catch (Exception e) {
                return 0.0;
            }
        }));
    }

    /**
     * Jeden tick mozku – případné přerozhodnutí + tick aktivního cíle.
     */
    public void tick() {
        if (--ticksToDecision <= 0) {
            ticksToDecision = decisionInterval;
            decide();
        }
        Goal goal = current;
        if (goal != null) {
            try {
                goal.tick(bot);
                if (goal.finished(bot)) {
                    // Vynucení uvolnit HNED: decide() by jinak viděl current == null
                    // a vynucený (už hotový) cíl donekonečna restartoval.
                    if (goal.id().equals(forcedGoalId)) {
                        forcedGoalId = null;
                    }
                    switchTo(null);
                    ticksToDecision = 0;
                }
            } catch (Exception e) {
                LOG.error("[{}] Cíl '{}' selhal, deaktivuji", bot.name(), goal.id(), e);
                switchTo(null);
            }
        }
    }

    /** Zastaví aktivní cíl (pauza, odpojení). */
    public void halt() {
        switchTo(null);
    }

    /** Výběr nejlepšího cíle. */
    private void decide() {
        // Vynucený cíl má absolutní přednost.
        if (forcedGoalId != null) {
            if (current == null || !current.id().equals(forcedGoalId)) {
                goals.stream()
                        .filter(g -> g.id().equals(forcedGoalId))
                        .findFirst()
                        .ifPresent(this::switchTo);
            }
            if (current != null && current.id().equals(forcedGoalId) && current.finished(bot)) {
                forcedGoalId = null;
            }
            return;
        }

        Goal best = null;
        double bestUtility = 0;
        dev.botalive.core.world.Dimension dimension = BotContext.of(bot).dimension();
        for (Goal goal : goals) {
            double utility;
            try {
                utility = goal.utility(bot);
            } catch (Exception e) {
                LOG.warn("[{}] utility() cíle '{}' selhala: {}", bot.name(), goal.id(), e.toString());
                continue;
            }
            if (utility <= 0) {
                continue;
            }
            // Dimenze škrtá, co v ní nedává smysl (v Endu postel exploduje...).
            utility *= DimensionPolicy.weight(goal.id(), dimension);
            if (utility <= 0) {
                continue;
            }
            // Profese vychyluje priority (kovář taví ochotněji, lovec loví...).
            utility *= dev.botalive.core.role.RoleProfiles.weight(bot.role(), goal.id());
            // Denní rytmus: ráno pole, přes den těžba/stavba, večer družení.
            // V Endu/Netheru není den a noc – rytmus tam neplatí.
            if (rhythm != null && DimensionPolicy.rhythmApplies(dimension)) {
                utility *= rhythm.multiplier(goal.id(), BotContext.of(bot).worldTime());
            }
            // Životní ambice táhne související cíle (dokud není splněná).
            if (bot instanceof dev.botalive.core.bot.BotImpl impl) {
                utility *= impl.ambitionWeight(goal.id());
            }
            // Hystereze aktivního cíle + drobný rozhodovací šum.
            if (goal == current) {
                utility *= hysteresis;
            }
            utility *= 1.0 + rng.gaussian(0, 0.03);
            if (utility > bestUtility) {
                bestUtility = utility;
                best = goal;
            }
        }
        if (best != current) {
            switchTo(best);
        }
    }

    /** Přepnutí aktivního cíle + event. */
    private void switchTo(Goal next) {
        Goal previous = current;
        if (previous != null) {
            try {
                previous.stop(bot);
            } catch (Exception e) {
                LOG.warn("[{}] stop() cíle '{}' selhal: {}", bot.name(), previous.id(), e.toString());
            }
        }
        current = next;
        if (next != null) {
            try {
                next.start(bot);
            } catch (Exception e) {
                LOG.error("[{}] start() cíle '{}' selhal", bot.name(), next.id(), e);
                current = null;
            }
        }
        String previousId = previous == null ? null : previous.id();
        String nextId = current == null ? null : current.id();
        if (previousId != null || nextId != null) {
            new BotGoalChangedEvent(bot, previousId, nextId).callEvent();
        }
    }
}
