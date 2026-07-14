package dev.botalive.core.ai.goals;

import dev.botalive.api.ai.Goal;
import dev.botalive.api.bot.Bot;
import dev.botalive.core.ai.BotContext;

/**
 * Společný základ vestavěných cílů.
 *
 * <p>Poskytuje pohodlný přístup k internímu {@link BotContext} a prázdné
 * implementace lifecycle metod – konkrétní cíle přepisují jen to, co potřebují.</p>
 */
public abstract class AbstractGoal implements Goal {

    private final String id;

    /**
     * @param id stabilní identifikátor cíle
     */
    protected AbstractGoal(String id) {
        this.id = id;
    }

    @Override
    public final String id() {
        return id;
    }

    /**
     * @param bot API bot
     * @return interní kontext bota
     */
    protected static BotContext ctx(Bot bot) {
        return BotContext.of(bot);
    }

    @Override
    public void start(Bot bot) {
        // výchozí: nic
    }

    @Override
    public void stop(Bot bot) {
        // výchozí: zrušit navigaci, ať po cíli nezůstane rozjetá cesta
        ctx(bot).navigator().stop();
    }

    @Override
    public boolean finished(Bot bot) {
        return false;
    }
}
