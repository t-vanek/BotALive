package dev.botalive.api.event;

import dev.botalive.api.bot.Bot;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Vyvolán při uzavření příměří mezi válčícími vesnicemi botů.
 *
 * <p>Příměří navrhuje starosta unavený válkou (padlí, délka konfliktu);
 * poražená strana může vítězi zaplatit reparace z peněženky starosty.</p>
 */
public class SettlementTruceEvent extends BotEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final long proposerId;
    private final String proposerName;
    private final long otherId;
    private final String otherName;
    private final double reparations;

    /**
     * @param mayor        starosta navrhující příměří
     * @param proposerId   id navrhující vesnice
     * @param proposerName jméno navrhující vesnice
     * @param otherId      id druhé vesnice
     * @param otherName    jméno druhé vesnice
     * @param reparations  zaplacené reparace (0 = žádné)
     */
    public SettlementTruceEvent(Bot mayor, long proposerId, String proposerName,
                                long otherId, String otherName, double reparations) {
        super(mayor);
        this.proposerId = proposerId;
        this.proposerName = proposerName;
        this.otherId = otherId;
        this.otherName = otherName;
        this.reparations = reparations;
    }

    /** @return id navrhující vesnice */
    public long proposerId() {
        return proposerId;
    }

    /** @return jméno navrhující vesnice */
    public String proposerName() {
        return proposerName;
    }

    /** @return id druhé vesnice */
    public long otherId() {
        return otherId;
    }

    /** @return jméno druhé vesnice */
    public String otherName() {
        return otherName;
    }

    /** @return zaplacené reparace (0 = žádné) */
    public double reparations() {
        return reparations;
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
