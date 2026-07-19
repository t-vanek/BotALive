package dev.botalive.api.event;

import dev.botalive.api.bot.Bot;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Vyvolán, když starosta vesnice botů vyhlásí válku jiné vesnici.
 *
 * <p>Války jsou emergentní: napětí mezi vesnicemi roste z křivd mezi jejich
 * členy (odhalené krádeže, napadení) a válku vyhlašuje starosta s dostatečně
 * bojovnou povahou. Event nese jen identifikátory a jména sídel – datové typy
 * sídel jsou interní záležitostí implementace.</p>
 */
public class SettlementWarDeclaredEvent extends BotEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final long aggressorId;
    private final String aggressorName;
    private final long defenderId;
    private final String defenderName;
    private final double tension;

    /**
     * @param mayor         starosta vyhlašující válku
     * @param aggressorId   id vyhlašující vesnice
     * @param aggressorName jméno vyhlašující vesnice
     * @param defenderId    id napadené vesnice
     * @param defenderName  jméno napadené vesnice
     * @param tension       napětí v okamžiku vyhlášení
     */
    public SettlementWarDeclaredEvent(Bot mayor, long aggressorId, String aggressorName,
                                      long defenderId, String defenderName, double tension) {
        super(mayor);
        this.aggressorId = aggressorId;
        this.aggressorName = aggressorName;
        this.defenderId = defenderId;
        this.defenderName = defenderName;
        this.tension = tension;
    }

    /** @return id vyhlašující vesnice */
    public long aggressorId() {
        return aggressorId;
    }

    /** @return jméno vyhlašující vesnice */
    public String aggressorName() {
        return aggressorName;
    }

    /** @return id napadené vesnice */
    public long defenderId() {
        return defenderId;
    }

    /** @return jméno napadené vesnice */
    public String defenderName() {
        return defenderName;
    }

    /** @return napětí v okamžiku vyhlášení */
    public double tension() {
        return tension;
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
