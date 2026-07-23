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
    /** Ticky od vynucení cíle – jen pro diagnostický log při jeho ukončení. */
    private int forcedGoalTicks;
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
            forcedGoalTicks = 0;
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
        if (forcedGoalId != null) {
            forcedGoalTicks++;
        }
        if (--ticksToDecision <= 0) {
            ticksToDecision = decisionInterval;
            decide();
        }
        Goal goal = current;
        if (goal != null) {
            try {
                goal.tick(bot);
                if (goal.finished(bot)) {
                    // Úspěšné dokončení posílí hybnost cíle (učení z výsledků).
                    if (bot instanceof dev.botalive.core.bot.BotImpl impl) {
                        impl.reinforceGoal(goal.id());
                    }
                    // Vynucení uvolnit HNED: decide() by jinak viděl current == null
                    // a vynucený (už hotový) cíl donekonečna restartoval.
                    if (goal.id().equals(forcedGoalId)) {
                        // Bez tohohle logu nešlo rozeznat „cíl se vůbec nespustil"
                        // od „spustil se a hned se vzdal" – operátor viděl jen
                        // hlášku „cíl vynucen" a pak už nic.
                        LOG.info("[{}] Vynucený cíl '{}' skončil po {} ticích"
                                + " – rozhodování se vrací mozku",
                                bot.name(), goal.id(), forcedGoalTicks);
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
        // Vynucený cíl má absolutní přednost – ale ani vynucení neobchází
        // tvrdé zákazy dimenze (spánek v Endu/Netheru = exploze postele).
        if (forcedGoalId != null) {
            if (DimensionPolicy.weight(forcedGoalId,
                    BotContext.of(bot).dimension()) <= 0) {
                LOG.warn("[{}] Vynucený cíl '{}' je v této dimenzi zakázán – ruším vynucení",
                        bot.name(), forcedGoalId);
                forcedGoalId = null;
                switchTo(null);
                return;
            }
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
        // Hybnost cílů o krok zeslábne (jednou za rozhodnutí, ne per cíl).
        if (bot instanceof dev.botalive.core.bot.BotImpl momentumImpl) {
            momentumImpl.decayMomentum();
        }
        dev.botalive.core.world.WorldDimension dimension = BotContext.of(bot).dimension();
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
            // Profese vychyluje priority (kovář taví ochotněji, lovec loví...) –
            // přes registr rolí, aby fungovaly i cizí role pluginů.
            utility *= bot instanceof dev.botalive.core.bot.BotImpl roleImpl
                    ? roleImpl.roleWeight(goal.id())
                    : dev.botalive.core.role.RoleProfiles.weight(bot.role(), goal.id());
            // Denní rytmus: ráno pole, přes den těžba/stavba, večer družení.
            // V Endu/Netheru není den a noc – rytmus tam neplatí.
            if (rhythm != null && DimensionPolicy.rhythmApplies(dimension)) {
                utility *= rhythm.multiplier(goal.id(), BotContext.of(bot).worldTime());
            }
            // Životní ambice táhne související cíle (dokud není splněná).
            if (bot instanceof dev.botalive.core.bot.BotImpl impl) {
                utility *= impl.ambitionWeight(goal.id());
                // Zaměstnání: dělník se soustředí na práci a míň se fláká.
                utility *= impl.employmentWeight(goal.id());
                // Nálada: aktuální emoce jemně vychyluje priority (viz docs/BOT_LIFE.md).
                utility *= impl.moodWeight(goal.id());
                // Únava: unavený bot odkládá dlouhé výpravy a vyhledá odpočinek.
                utility *= impl.vitalsWeight(goal.id());
                // Pudy: naléhavá základní potřeba tlumí cíle vyšších potřeb (Maslow).
                utility *= impl.drivesWeight(goal.id());
                // Naučená hybnost: co bot úspěšně dokončuje, dělá o kus radši.
                utility *= impl.momentumWeight(goal.id());
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
            // Rozhodovací hák: cizí plugin může přirozené přepnutí vetovat
            // (podrží stávající cíl). Vynucený cíl (výše) sem nikdy nedojde.
            String fromId = current == null ? null : current.id();
            String toId = best == null ? null : best.id();
            dev.botalive.api.event.BotGoalSelectEvent event =
                    new dev.botalive.api.event.BotGoalSelectEvent(bot, fromId, toId);
            event.callEvent();
            if (!event.isCancelled()) {
                switchTo(best);
            }
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
