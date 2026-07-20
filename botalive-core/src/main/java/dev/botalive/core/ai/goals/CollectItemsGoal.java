package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.entity.TrackedEntity;

import java.util.Optional;

/**
 * Sbírání upuštěných předmětů v okolí.
 *
 * <p>Bot dojde k nejbližšímu item-entity; sebrání proběhne automaticky
 * kontaktem (server-side pickup). Chamtiví boti sbírají horlivěji a z větší
 * dálky.</p>
 */
public final class CollectItemsGoal extends AbstractGoal {

    /** Ticků bez přiblížení k itemu, než ho bot vzdá jako nedosažitelný. */
    private static final int STALL_TICKS = 100;

    private int emptyTicks;
    private int cooldownTicks;
    private int stallTicks;
    private double nearestSoFarSq = Double.MAX_VALUE;

    /** Vytvoří cíl. */
    public CollectItemsGoal() {
        super("collect");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        double greed = bot.personality().trait(Trait.GREED);
        Optional<TrackedEntity> item = ctx.entities()
                .nearest(ctx.position(), radiusFor(bot), TrackedEntity::isItem);
        if (item.isEmpty()) {
            return 0;
        }
        // Sběr je doplněk, ne priorita. Se základem 12 + greed*20 (max 32)
        // přebíjel farm, craft, smelt, socialize i mine – každý dropnutý item
        // tak přerušil desetiminutovou práci kvůli dvousekundovému úkonu.
        return 6 + greed * 12;
    }

    /** Dosah sběru; sdílí ho utility i tick (dřív se rozcházely 8–24 vs. 32). */
    private static double radiusFor(Bot bot) {
        return 8 + bot.personality().trait(Trait.GREED) * 16;
    }

    @Override
    public void start(Bot bot) {
        emptyTicks = 0;
        stallTicks = 0;
        nearestSoFarSq = Double.MAX_VALUE;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        Optional<TrackedEntity> item = ctx.entities()
                .nearest(ctx.position(), radiusFor(bot), TrackedEntity::isItem);
        if (item.isEmpty()) {
            emptyTicks++;
            return;
        }
        emptyTicks = 0;
        TrackedEntity target = item.get();
        // Nedosažitelný item (za vodou, v lávě, ve stěně) dřív držel bota až do
        // despawnu (5 min): cíl neměl cooldown ani detekci neúspěchu.
        double distSq = ctx.position().distanceSquared(target.position());
        if (distSq < nearestSoFarSq - 0.25) {
            nearestSoFarSq = distSq;
            stallTicks = 0;
        } else if (++stallTicks > STALL_TICKS) {
            cooldownTicks = 200;
            emptyTicks = Integer.MAX_VALUE;
            return;
        }
        ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0), target.position());
        // Navigace přímo na pozici itemu (pickup radius ~1 blok).
        ctx.navigator().navigateTo(ctx.position(), target.position().toBlockPos());
    }

    @Override
    public boolean finished(Bot bot) {
        return emptyTicks > 20;
    }

    @Override
    public String explain(dev.botalive.api.bot.Bot bot) {
        return "sbírám věci ze země";
    }
}
