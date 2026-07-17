package dev.botalive.api.event;

import dev.botalive.api.bot.Bot;
import org.bukkit.Bukkit;
import org.bukkit.event.Event;

import java.util.Objects;

/**
 * Společný předek všech Bukkit eventů týkajících se botů.
 *
 * <p>Eventy vznikají většinou asynchronně (z AI/síťových vláken botů), ale
 * některé cesty je vyvolají i synchronně z hlavního vlákna (např. příkazy
 * {@code /botalive pause}/{@code remove} zastaví mozek bota a ten při přepnutí
 * cíle event vystřelí). Async příznak proto určujeme podle skutečného vlákna –
 * Paper jinak vyhodí {@code IllegalStateException} při vystřelení async eventu
 * z hlavního vlákna. Posluchači nesmí z async handleru sahat na Bukkit API
 * vyžadující hlavní vlákno.</p>
 */
public abstract class BotEvent extends Event {

    private final Bot bot;

    /**
     * @param bot bot, kterého se událost týká
     */
    protected BotEvent(Bot bot) {
        super(!Bukkit.isPrimaryThread());
        this.bot = Objects.requireNonNull(bot, "bot");
    }

    /**
     * @return bot, kterého se událost týká
     */
    public Bot bot() {
        return bot;
    }
}
