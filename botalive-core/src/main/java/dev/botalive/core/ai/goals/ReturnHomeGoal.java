package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.util.BlockPos;

import java.util.Optional;
import dev.botalive.core.pathfinding.PathGoal;

/**
 * Návrat domů – na noc nebo když je bot moc daleko od základny.
 *
 * <p>Domov je {@link MemoryKind#HOME} vzpomínka (postavený úkryt, spawn).
 * Opatrní boti se vrací dřív a z menší vzdálenosti. Po příchodu bot u domova
 * „odpočívá“ (přečká noc na místě – hlídá to nízká utility ostatních cílů
 * v kombinaci s tím, že bot stojí u domova).</p>
 */
public final class ReturnHomeGoal extends AbstractGoal {

    /** Pauza po marném pokusu dojít domů – ať dostane slovo jiný cíl. */
    private static final int UNREACHABLE_COOLDOWN_TICKS = 600;

    private BlockPos home;
    private int restTicks;
    /** Cíl už běží – drží se, dokud sám neskončí (viz {@link #utility}). */
    private boolean active;
    /** Bot se během běhu cíle dostal k domovu (odlišuje odpočinek od selhání). */
    private boolean arrived;
    private int cooldownTicks;

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
        // Po marném pokusu chvíli nezkoušet: s hysterezí níže by se jinak cíl
        // hned znovu vybral, bot by u nedosažitelného domova jen stál a žádný
        // jiný cíl by se ke slovu nedostal.
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
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

        // Hystereze: zapíná se na 12 blocích, ale běžící cíl se drží až do
        // finished() (odpočinek u domova). Dřív byl zapínací i vypínací poloměr
        // stejný, takže bot na prstenci osciloval a k domovu (3 bloky) nikdy
        // nedošel – restTicks se neinkrementoval a finished() byl mrtvý kód.
        if (active) {
            if (night) {
                return 15 + caution * 25;
            }
            return ctx.thundering() ? 13 + caution * 20 : 8 + caution * 15;
        }
        if (night && distance > 12) {
            return 15 + caution * 25; // na noc domů
        }
        if (ctx.thundering() && distance > 12) {
            return 13 + caution * 20; // bouřka – blesky, mokro, domů
        }
        if (distance > 150 + (1.0 - caution) * 150) {
            return 8 + caution * 15;  // zabloudili jsme moc daleko
        }
        return 0;
    }

    @Override
    public void stop(Bot bot) {
        active = false;
        // Skončili jsme, aniž bot domov viděl → cesta tam nevede (jeskyně,
        // jiný svět, zavalený vchod). Pauza, ať se nezacyklí na místě.
        if (!arrived) {
            cooldownTicks = UNREACHABLE_COOLDOWN_TICKS;
        }
    }

    @Override
    public void start(Bot bot) {
        BotContext ctx = ctx(bot);
        active = true;
        arrived = false;
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
            ctx.navigator().navigateTo(ctx.position(), PathGoal.near(home, 2));
            if (!ctx.navigator().navigating() && !ctx.navigator().hasPath()) {
                restTicks++; // cesta selhala – po chvíli to vzdát
            }
        } else {
            arrived = true;
            ctx.navigator().stop();
            restTicks++;
        }
    }

    @Override
    public boolean finished(Bot bot) {
        // U domova chvíli poklimbat, pak pustit rozhodování dál.
        return restTicks > 200;
    }

    @Override
    public String explain(dev.botalive.api.bot.Bot bot) {
        return "vracím se domů";
    }
}
