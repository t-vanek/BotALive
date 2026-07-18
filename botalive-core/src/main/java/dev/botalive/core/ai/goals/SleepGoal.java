package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.WorldView;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;

import java.util.Map;

/**
 * Spánek – v noci bot vyhledá postel a vyspí se.
 *
 * <p>Postel hledá nejprve v paměti (HOME s příznakem postele), pak skenem
 * okolí. Ulehnutí je pravý klik na postel (UseItemOn) – server pak řídí spánek
 * i probuzení sám; bot jen leží (idle) a stav sleduje přes server-side
 * snapshot ({@code sleeping}). Použitou postel si uloží jako domov.</p>
 */
public final class SleepGoal extends AbstractGoal {

    private enum Phase { FIND, GO, LIE_DOWN, SLEEPING, DONE }

    private Phase phase = Phase.FIND;
    private BlockPos bed;
    private int retryTicks;
    private int attempts;
    private int cooldownTicks;

    /** Vytvoří cíl. */
    public SleepGoal() {
        super("sleep");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        // Mimo overworld se nespí NIKDY – použití postele v Netheru/Endu
        // vybuchuje (vanilla) a noc tam stejně neexistuje.
        if (ctx.dimension() != dev.botalive.core.world.WorldDimension.OVERWORLD) {
            return 0;
        }
        long time = ctx.worldTime();
        boolean night = time >= 12500 && time <= 23000;
        if (!night || ctx.clientState().dead()) {
            return 0;
        }
        double laziness = bot.personality().trait(Trait.LAZINESS);
        double caution = bot.personality().trait(Trait.CAUTION);
        return 8 + laziness * 12 + caution * 8;
    }

    @Override
    public void start(Bot bot) {
        phase = Phase.FIND;
        bed = null;
        attempts = 0;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        switch (phase) {
            case FIND -> {
                bed = findBed(ctx, bot);
                if (bed == null) {
                    cooldownTicks = 1200; // žádná postel – zkusit za minutu
                    phase = Phase.DONE;
                    return;
                }
                phase = Phase.GO;
            }
            case GO -> {
                double distSq = bed.center().distanceSquared(ctx.position());
                if (distSq > 2.5 * 2.5) {
                    ctx.navigator().navigateTo(ctx.position(), bed);
                    if (!ctx.navigator().navigating()) {
                        phase = Phase.FIND;
                    }
                    return;
                }
                ctx.navigator().stop();
                phase = Phase.LIE_DOWN;
                retryTicks = 0;
            }
            case LIE_DOWN -> {
                var snapshot = ctx.serverView().latest();
                if (snapshot != null && snapshot.sleeping()) {
                    rememberBed(ctx, bot);
                    phase = Phase.SLEEPING;
                    return;
                }
                // Klik na postel; opakovat s odstupem (server může odmítnout –
                // monstra poblíž apod.).
                if (retryTicks-- <= 0) {
                    ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0), bed.center());
                    ctx.actions().useItemOn(bed, Direction.UP);
                    retryTicks = 30;
                    if (++attempts > 5) {
                        cooldownTicks = 1200; // nejde ulehnout (mobové poblíž)
                        phase = Phase.DONE;
                    }
                }
            }
            case SLEEPING -> {
                long time = ctx.worldTime();
                var snapshot = ctx.serverView().latest();
                boolean awake = snapshot != null && !snapshot.sleeping();
                if ((time >= 0 && time < 12000) || awake) {
                    phase = Phase.DONE; // ráno / probuzení
                }
            }
            case DONE -> {
                // finished() ukončí
            }
        }
    }

    @Override
    public boolean finished(Bot bot) {
        return phase == Phase.DONE;
    }

    /** Uloží postel jako domov bota. */
    private void rememberBed(BotContext ctx, Bot bot) {
        if (ctx.worldView() != null && bed != null) {
            bot.memory().remember(MemoryKind.HOME, ctx.worldView().worldName(),
                    bed.x(), bed.y(), bed.z(), null, Map.of("type", "bed"), 0.9);
        }
    }

    /** Najde postel: nejdřív v paměti (HOME/bed), pak skenem okolí. */
    private BlockPos findBed(BotContext ctx, Bot bot) {
        WorldView world = ctx.worldView();
        if (world == null) {
            return null;
        }
        BlockPos center = ctx.position().toBlockPos();
        // Paměť: dům s postelí.
        var remembered = bot.memory().recall(MemoryKind.HOME).stream()
                .filter(r -> "bed".equals(r.data().get("type")))
                .filter(r -> world.worldName().equals(r.world()))
                .filter(r -> r.distanceSquared(center.x(), center.y(), center.z()) < 96 * 96)
                .findFirst();
        if (remembered.isPresent()) {
            var r = remembered.get();
            return new BlockPos(r.x(), r.y(), r.z());
        }
        // Sken okolí.
        int radius = 12;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    var material = world.materialAt(pos);
                    if (material != null && material.name().endsWith("_BED")) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    @Override
    public String explain(dev.botalive.api.bot.Bot bot) {
        return "je noc, jdu spát";
    }
}
