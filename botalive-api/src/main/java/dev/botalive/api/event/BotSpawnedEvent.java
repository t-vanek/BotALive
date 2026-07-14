package dev.botalive.api.event;

import dev.botalive.api.bot.Bot;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Vyvolán, jakmile se bot úspěšně připojil a naspawnoval ve světě.
 */
public class BotSpawnedEvent extends BotEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    /**
     * @param bot naspawnovaný bot
     */
    public BotSpawnedEvent(Bot bot) {
        super(bot);
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
