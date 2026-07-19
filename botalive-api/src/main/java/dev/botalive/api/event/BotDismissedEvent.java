package dev.botalive.api.event;

import dev.botalive.api.bot.Bot;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Vyvolán při konci pracovní smlouvy bota – vypršením, výpovědí
 * zaměstnavatele, nebo výpovědí bota (napadl ho vlastní zaměstnavatel).
 */
public class BotDismissedEvent extends BotEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    /** Důvod konce smlouvy. */
    public enum Reason { EXPIRED, DISMISSED, QUIT }

    private final UUID employer;
    private final String employerName;
    private final String kind;
    private final Reason reason;

    /**
     * @param bot          bot, jehož smlouva skončila
     * @param employer     UUID zaměstnavatele (hráče)
     * @param employerName jméno zaměstnavatele
     * @param kind         druh práce ({@code WORKER} / {@code GUARD})
     * @param reason       důvod konce
     */
    public BotDismissedEvent(Bot bot, UUID employer, String employerName,
                             String kind, Reason reason) {
        super(bot);
        this.employer = employer;
        this.employerName = employerName;
        this.kind = kind;
        this.reason = reason;
    }

    /** @return UUID zaměstnavatele */
    public UUID employer() {
        return employer;
    }

    /** @return jméno zaměstnavatele */
    public String employerName() {
        return employerName;
    }

    /** @return druh práce ({@code WORKER} / {@code GUARD}) */
    public String kind() {
        return kind;
    }

    /** @return důvod konce smlouvy */
    public Reason reason() {
        return reason;
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
