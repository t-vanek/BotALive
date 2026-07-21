package dev.botalive.api.event;

import dev.botalive.api.bot.Bot;
import org.bukkit.event.Cancellable;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

/**
 * Vyvolán těsně předtím, než bot <b>přirozeně</b> přepne aktivní AI cíl
 * (mozek vybral jiný cíl s nejvyšší užitečností). <b>Zrušením</b> eventu se
 * přepnutí zablokuje – bot v tomto rozhodovacím cyklu podrží stávající cíl.
 * V dalším cyklu se rozhoduje znovu (a event se může vyvolat zas), takže
 * posluchač může přepnutí držet, jak dlouho potřebuje.
 *
 * <p>Je to rozhodovací hák: cizí plugin může botovi <i>rozmluvit</i> konkrétní
 * chování (třeba nedovolit „steal" na chráněném území), aniž by nahrazoval
 * celý AI subsystém.</p>
 *
 * <p><b>Nevyvolává se</b> pro vynucený cíl ({@code /botalive goal set},
 * {@link Bot#forceGoal(String)}) – vynucení je záměrný, explicitní povel.</p>
 *
 * <p><b>Vlákno.</b> Vzniká z tick vlákna bota (async z pohledu Bukkitu).
 * Posluchač nesmí z handleru sahat na Bukkit API vyžadující hlavní vlákno.</p>
 */
public class BotGoalSelectEvent extends BotEvent implements Cancellable {

    private static final HandlerList HANDLERS = new HandlerList();

    private final String fromGoalId;
    private final String toGoalId;
    private boolean cancelled;

    /**
     * @param bot        rozhodující se bot
     * @param fromGoalId id dosavadního cíle, nebo {@code null} (žádný aktivní cíl)
     * @param toGoalId   id cíle, na který se chystá přepnout, nebo {@code null}
     *                   (bot se chystá přejít do nečinnosti)
     */
    public BotGoalSelectEvent(Bot bot, String fromGoalId, String toGoalId) {
        super(bot);
        this.fromGoalId = fromGoalId;
        this.toGoalId = toGoalId;
    }

    /**
     * @return id dosavadního cíle, nebo {@code null}
     */
    public String fromGoalId() {
        return fromGoalId;
    }

    /**
     * @return id cíle, na který se bot chystá přepnout, nebo {@code null}
     *         (přechod do nečinnosti)
     */
    public String toGoalId() {
        return toGoalId;
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
