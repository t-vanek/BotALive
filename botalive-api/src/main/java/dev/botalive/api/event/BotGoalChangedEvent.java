package dev.botalive.api.event;

import dev.botalive.api.bot.Bot;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Vyvolán při přepnutí aktivního AI cíle bota.
 */
public class BotGoalChangedEvent extends BotEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String previousGoalId;
    private final String newGoalId;

    /**
     * @param bot            bot měnící cíl
     * @param previousGoalId id předchozího cíle, nebo {@code null}
     * @param newGoalId      id nového cíle, nebo {@code null} (bot je bez cíle)
     */
    public BotGoalChangedEvent(Bot bot, String previousGoalId, String newGoalId) {
        super(bot);
        this.previousGoalId = previousGoalId;
        this.newGoalId = newGoalId;
    }

    /** @return id předchozího cíle, nebo {@code null} */
    public String previousGoalId() {
        return previousGoalId;
    }

    /** @return id nového cíle, nebo {@code null} */
    public String newGoalId() {
        return newGoalId;
    }

    @Override
    public @NotNull HandlerList getHandlers() {
        return HANDLERS;
    }

    /**
     * @return handler list (Bukkit konvence)
     */
    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
