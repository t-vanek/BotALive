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

    private int emptyTicks;

    /** Vytvoří cíl. */
    public CollectItemsGoal() {
        super("collect");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        double greed = bot.personality().trait(Trait.GREED);
        double radius = 8 + greed * 16;
        Optional<TrackedEntity> item = ctx.entities().nearest(ctx.position(), radius, TrackedEntity::isItem);
        if (item.isEmpty()) {
            return 0;
        }
        return 12 + greed * 20;
    }

    @Override
    public void start(Bot bot) {
        emptyTicks = 0;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        Optional<TrackedEntity> item = ctx.entities().nearest(ctx.position(), 32, TrackedEntity::isItem);
        if (item.isEmpty()) {
            emptyTicks++;
            return;
        }
        emptyTicks = 0;
        TrackedEntity target = item.get();
        ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0), target.position());
        // Navigace přímo na pozici itemu (pickup radius ~1 blok).
        ctx.navigator().navigateTo(ctx.position(), target.position().toBlockPos());
    }

    @Override
    public boolean finished(Bot bot) {
        return emptyTicks > 20;
    }
}
