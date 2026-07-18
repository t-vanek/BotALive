package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.inventory.InventoryHelper;

/**
 * Najedení – když klesne hlad a bot má jídlo.
 *
 * <p>Stavový automat: vybrat jídlo do ruky → začít jíst (UseItem) → držet
 * ~1.8 s (server jídlo dokončí sám) → hotovo. Bot při jídle zpomalí (stojí).</p>
 */
public final class EatGoal extends AbstractGoal {

    private enum Phase { EQUIP, EATING, DONE }

    private Phase phase = Phase.EQUIP;
    private int eatingTicks;
    private int startFood;

    /** Vytvoří cíl. */
    public EatGoal() {
        super("eat");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        int food = ctx.clientState().food();
        if (food >= 18 || ctx.clientState().dead()) {
            return 0;
        }
        var snapshot = ctx.serverView().latest();
        if (snapshot == null) {
            return 0;
        }
        // Nouzové jídlo (chorus ovoce – teleportuje) se počítá až při
        // opravdovém hladu; typicky uvázlý průzkumník Endu bez zásob.
        boolean hasFood = snapshot.hasItem(InventoryHelper::isFood)
                || (food <= 8 && snapshot.hasItem(InventoryHelper::isEmergencyFood));
        if (!hasFood) {
            return 0;
        }
        // Hlad 17 → mírná priorita, hlad 0 → kritická.
        return 10 + (18 - food) * 6;
    }

    @Override
    public void start(Bot bot) {
        phase = Phase.EQUIP;
        eatingTicks = 0;
        startFood = ctx(bot).clientState().food();
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        switch (phase) {
            case EQUIP -> {
                ctx.navigator().stop();
                var snapshot = ctx.serverView().latest();
                boolean ready = ctx.inventory().equipFood(snapshot)
                        // Z nouze i chorus ovoce – teleport je menší zlo než hlad.
                        || (ctx.clientState().food() <= 8 && snapshot != null
                                && ctx.inventory().equipItem(snapshot,
                                        org.bukkit.Material.CHORUS_FRUIT));
                if (ready) {
                    // krátká lidská prodleva mezi výběrem slotu a jídlem
                    if (eatingTicks++ >= ctx.rng().rangeInt(3, 8)) {
                        ctx.actions().useItem(ctx.humanizer().yaw(), ctx.humanizer().pitch());
                        phase = Phase.EATING;
                        eatingTicks = 0;
                    }
                } else {
                    phase = Phase.DONE; // jídlo mezitím zmizelo
                }
            }
            case EATING -> {
                // Jídlo trvá 32 ticků; držíme use a čekáme, až server přidá hlad.
                if (++eatingTicks > 45 || ctx.clientState().food() > startFood) {
                    phase = Phase.DONE;
                }
            }
            case DONE -> {
                // nic – finished() cíl ukončí
            }
        }
    }

    @Override
    public boolean finished(Bot bot) {
        return phase == Phase.DONE;
    }

    @Override
    public String explain(dev.botalive.api.bot.Bot bot) {
        return "mám hlad, dávám si něco k jídlu";
    }
}
