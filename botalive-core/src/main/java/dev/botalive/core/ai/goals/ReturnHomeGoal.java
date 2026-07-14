package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.util.BlockPos;

import java.util.Optional;

/**
 * Návrat domů – na noc nebo když je bot moc daleko od základny.
 *
 * <p>Domov je {@link MemoryKind#HOME} vzpomínka (postavený úkryt, spawn).
 * Opatrní boti se vrací dřív a z menší vzdálenosti. Po příchodu bot u domova
 * „odpočívá“ (přečká noc na místě – hlídá to nízká utility ostatních cílů
 * v kombinaci s tím, že bot stojí u domova).</p>
 */
public final class ReturnHomeGoal extends AbstractGoal {

    private BlockPos home;
    private int restTicks;

    /** Vytvoří cíl. */
    public ReturnHomeGoal() {
        super("home");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (ctx.worldView() == null) {
            return 0;
        }
        BlockPos pos = ctx.position().toBlockPos();
        Optional<dev.botalive.api.memory.MemoryRecord> record = bot.memory()
                .recallNearest(MemoryKind.HOME, ctx.worldView().worldName(), pos.x(), pos.y(), pos.z());
        if (record.isEmpty()) {
            return 0;
        }
        double distance = Math.sqrt(record.get().distanceSquared(pos.x(), pos.y(), pos.z()));
        long time = ctx.worldTime();
        boolean night = time >= 12000 && time <= 23500;
        double caution = bot.personality().trait(Trait.CAUTION);

        if (night && distance > 12) {
            return 15 + caution * 25; // na noc domů
        }
        if (distance > 150 + (1.0 - caution) * 150) {
            return 8 + caution * 15;  // zabloudili jsme moc daleko
        }
        return 0;
    }

    @Override
    public void start(Bot bot) {
        BotContext ctx = ctx(bot);
        restTicks = 0;
        BlockPos pos = ctx.position().toBlockPos();
        home = bot.memory()
                .recallNearest(MemoryKind.HOME, ctx.worldView().worldName(), pos.x(), pos.y(), pos.z())
                .map(r -> new BlockPos(r.x(), r.y(), r.z()))
                .orElse(null);
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        if (home == null) {
            restTicks = Integer.MAX_VALUE;
            return;
        }
        double distSq = ctx.position().toBlockPos().distanceSquared(home);
        if (distSq > 9) {
            ctx.navigator().navigateTo(ctx.position(), home);
            if (!ctx.navigator().navigating() && !ctx.navigator().hasPath()) {
                restTicks++; // cesta selhala – po chvíli to vzdát
            }
        } else {
            ctx.navigator().stop();
            restTicks++;
        }
    }

    @Override
    public boolean finished(Bot bot) {
        // U domova chvíli poklimbat, pak pustit rozhodování dál.
        return restTicks > 200;
    }
}
