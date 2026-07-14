package dev.botalive.api.event;

import dev.botalive.api.bot.Bot;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Vyvolán po trvalém odstranění bota ({@code /botalive remove} nebo API).
 */
public class BotRemovedEvent extends BotEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final boolean purged;

    /**
     * @param bot    odstraněný bot
     * @param purged {@code true} pokud byla smazána i persistentní data
     */
    public BotRemovedEvent(Bot bot, boolean purged) {
        super(bot);
        this.purged = purged;
    }

    /**
     * @return {@code true} pokud byla smazána i persistentní data bota
     */
    public boolean purged() {
        return purged;
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
