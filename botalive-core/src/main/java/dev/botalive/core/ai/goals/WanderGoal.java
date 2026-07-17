package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.WorldView;

/**
 * Krátké procházky po okolí.
 *
 * <p>Bot si vybírá cíle do ~16 bloků, mezi přesuny dělá pauzy. Líní boti se
 * toulají méně, zvědaví více. Nízká priorita – jde o „výplňové" chování.</p>
 */
public final class WanderGoal extends AbstractGoal {

    private int pauseTicks;

    /** Vytvoří cíl. */
    public WanderGoal() {
        super("wander");
    }

    @Override
    public double utility(Bot bot) {
        double curiosity = bot.personality().trait(Trait.CURIOSITY);
        double laziness = bot.personality().trait(Trait.LAZINESS);
        return 4 + curiosity * 4 - laziness * 3;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        if (ctx.navigator().navigating()) {
            return;
        }
        if (pauseTicks > 0) {
            pauseTicks--;
            return;
        }
        BlockPos target = randomNearbyTarget(ctx);
        if (target != null) {
            ctx.navigator().navigateTo(ctx.position(), target);
        }
        // pauza po (ne)nalezení cíle – bot neběhá bez oddechu
        double laziness = bot.personality().trait(Trait.LAZINESS);
        pauseTicks = ctx.rng().rangeInt(40, 100 + (int) (laziness * 200));
    }

    /** Náhodný pochozí bod v okolí (hledá povrch v malém sloupci). */
    private BlockPos randomNearbyTarget(BotContext ctx) {
        WorldView world = ctx.worldView();
        if (world == null) {
            return null;
        }
        Vec3 pos = ctx.position();
        for (int attempt = 0; attempt < 8; attempt++) {
            int dx = ctx.rng().rangeInt(-16, 16);
            int dz = ctx.rng().rangeInt(-16, 16);
            BlockPos base = pos.add(dx, 0, dz).toBlockPos();
            for (int dy = 3; dy >= -4; dy--) {
                BlockPos feet = base.offset(0, dy, 0);
                if (world.traitsAt(feet).passable()
                        && world.traitsAt(feet.up()).passable()
                        && world.traitsAt(feet.down()).solid()) {
                    return feet;
                }
            }
        }
        return null;
    }

    @Override
    public String explain(dev.botalive.api.bot.Bot bot) {
        return "jen se tak procházím";
    }
}
