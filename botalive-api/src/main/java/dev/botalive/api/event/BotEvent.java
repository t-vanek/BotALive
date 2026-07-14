package dev.botalive.api.event;

import dev.botalive.api.bot.Bot;
import org.bukkit.event.Event;

import java.util.Objects;

/**
 * Společný předek všech Bukkit eventů týkajících se botů.
 *
 * <p>Eventy jsou vyvolávány asynchronně (z AI/síťových vláken botů), proto jsou
 * konstruované s {@code async = true}. Posluchači nesmí z handleru sahat na
 * Bukkit API vyžadující hlavní vlákno.</p>
 */
public abstract class BotEvent extends Event {

    private final Bot bot;

    /**
     * @param bot bot, kterého se událost týká
     */
    protected BotEvent(Bot bot) {
        super(true);
        this.bot = Objects.requireNonNull(bot, "bot");
    }

    /**
     * @return bot, kterého se událost týká
     */
    public Bot bot() {
        return bot;
    }
}
