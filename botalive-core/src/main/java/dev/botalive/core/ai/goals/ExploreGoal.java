package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.WorldView;

import java.util.Map;

/**
 * Průzkum – dlouhé výpravy do neznámých oblastí.
 *
 * <p>Bot volí vzdálený cíl (32–80 bloků) ve směru, kde podle paměti
 * ({@link MemoryKind#VISITED_PLACE}) ještě nebyl. Navštívené oblasti si
 * průběžně ukládá, takže průzkum postupně expanduje. Po dokončení výpravy
 * má cíl cooldown podle trpělivosti bota.</p>
 */
public final class ExploreGoal extends AbstractGoal {

    private int cooldownTicks;
    private BlockPos expedition;
    private int rememberCooldown;

    /** Vytvoří cíl. */
    public ExploreGoal() {
        super("explore");
    }

    @Override
    public double utility(Bot bot) {
        if (cooldownTicks > 0) {
            // Cooldown se odpočítává rytmem rozhodování mozku.
            cooldownTicks -= ctx(bot).config().ai().decisionIntervalTicks();
            return 0;
        }
        double curiosity = bot.personality().trait(Trait.CURIOSITY);
        double laziness = bot.personality().trait(Trait.LAZINESS);
        return 6 + curiosity * 18 - laziness * 6;
    }

    @Override
    public void start(Bot bot) {
        expedition = null;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        if (expedition == null) {
            expedition = pickExpeditionTarget(ctx);
            if (expedition == null) {
                cooldownTicks = 200;
                return;
            }
            ctx.navigator().navigateTo(ctx.position(), expedition);
        }
        // Průběžné ukládání navštívených míst (jednou za ~10 s).
        if (--rememberCooldown <= 0) {
            rememberCooldown = 200;
            Vec3 pos = ctx.position();
            WorldView world = ctx.worldView();
            if (world != null) {
                bot.memory().remember(MemoryKind.VISITED_PLACE, world.worldName(),
                        (int) pos.x(), (int) pos.y(), (int) pos.z(), null, Map.of(), 0.3);
            }
        }
    }

    @Override
    public boolean finished(Bot bot) {
        BotContext ctx = ctx(bot);
        if (expedition == null) {
            return false;
        }
        boolean arrived = ctx.position().toBlockPos().distanceSquared(expedition) < 9;
        boolean gaveUp = !ctx.navigator().navigating();
        if (arrived || gaveUp) {
            double patience = bot.personality().trait(Trait.PATIENCE);
            cooldownTicks = ctx.rng().rangeInt(400, 1200 + (int) (patience * 1200));
            expedition = null;
            return true;
        }
        return false;
    }

    /**
     * Vybere směr výpravy – preferuje azimuty, kde bot nemá navštívená místa.
     */
    private BlockPos pickExpeditionTarget(BotContext ctx) {
        WorldView world = ctx.worldView();
        if (world == null) {
            return null;
        }
        Vec3 pos = ctx.position();
        var visited = ctx.bot().memory().recall(MemoryKind.VISITED_PLACE);

        double bestScore = Double.NEGATIVE_INFINITY;
        BlockPos best = null;
        for (int attempt = 0; attempt < 12; attempt++) {
            double angle = ctx.rng().range(0, Math.PI * 2);
            double distance = ctx.rng().range(32, 80);
            int tx = (int) (pos.x() + Math.cos(angle) * distance);
            int tz = (int) (pos.z() + Math.sin(angle) * distance);

            // Skóre = vzdálenost od nejbližšího navštíveného místa.
            double nearestVisited = Double.MAX_VALUE;
            for (var record : visited) {
                double d = record.distanceSquared(tx, (int) pos.y(), tz);
                nearestVisited = Math.min(nearestVisited, d);
            }
            double score = visited.isEmpty() ? ctx.rng().next() : nearestVisited;
            if (score > bestScore) {
                bestScore = score;
                best = findSurface(world, tx, (int) pos.y(), tz);
            }
        }
        return best;
    }

    /** Najde pochozí povrch v cílovém sloupci (v rozsahu ±8 bloků výšky). */
    private BlockPos findSurface(WorldView world, int x, int aroundY, int z) {
        for (int dy = 8; dy >= -8; dy--) {
            BlockPos feet = new BlockPos(x, aroundY + dy, z);
            if (world.traitsAt(feet).passable()
                    && world.traitsAt(feet.up()).passable()
                    && world.traitsAt(feet.down()).solid()) {
                return feet;
            }
        }
        // Neznámý (nenačtený) terén – vrátit odhad, pathfinder dojde na hranici známého.
        return new BlockPos(x, aroundY, z);
    }
}
