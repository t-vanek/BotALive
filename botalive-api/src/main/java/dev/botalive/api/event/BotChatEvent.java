package dev.botalive.api.event;

import dev.botalive.api.bot.Bot;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.Objects;

/**
 * Vyvolán těsně předtím, než bot odešle zprávu do chatu (už po humanizaci,
 * tedy včetně překlepů). Zrušením eventu se odeslání zablokuje.
 */
public class BotChatEvent extends BotEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private String message;
    private boolean cancelled;

    /**
     * @param bot     mluvící bot
     * @param message finální text zprávy
     */
    public BotChatEvent(Bot bot, String message) {
        super(bot);
        this.message = Objects.requireNonNull(message, "message");
    }

    /**
     * @return text zprávy, který bude odeslán
     */
    public String message() {
        return message;
    }

    /**
     * Umožňuje posluchači zprávu přepsat.
     *
     * @param message nový text
     */
    public void message(String message) {
        this.message = Objects.requireNonNull(message, "message");
    }

    @Override
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean cancel) {
        this.cancelled = cancel;
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
