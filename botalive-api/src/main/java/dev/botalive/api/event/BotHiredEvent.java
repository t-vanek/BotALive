package dev.botalive.api.event;

import dev.botalive.api.bot.Bot;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;

import java.util.UUID;

/**
 * Vyvolán, když si hráč najme bota (smlouva vstoupila v platnost –
 * po zaplacení mzdy, resp. hned při potvrzení bez vyžadované platby).
 *
 * <p>Druh práce je textový identifikátor ({@code WORKER} = dělník nosící
 * výtěžek, {@code GUARD} = bodyguard) – datové typy smluv jsou interní
 * záležitostí implementace.</p>
 */
public class BotHiredEvent extends BotEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final UUID employer;
    private final String employerName;
    private final String kind;
    private final double wage;
    private final int days;

    /**
     * @param bot          najatý bot
     * @param employer     UUID zaměstnavatele (hráče)
     * @param employerName jméno zaměstnavatele
     * @param kind         druh práce ({@code WORKER} / {@code GUARD})
     * @param wage         zaplacená mzda (0 při vypnuté platbě)
     * @param days         délka smlouvy ve dnech
     */
    public BotHiredEvent(Bot bot, UUID employer, String employerName,
                         String kind, double wage, int days) {
        super(bot);
        this.employer = employer;
        this.employerName = employerName;
        this.kind = kind;
        this.wage = wage;
        this.days = days;
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

    /** @return zaplacená mzda (0 při vypnuté platbě) */
    public double wage() {
        return wage;
    }

    /** @return délka smlouvy ve dnech */
    public int days() {
        return days;
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
