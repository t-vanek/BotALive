package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.WorldView;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;

import java.util.Map;
import dev.botalive.core.pathfinding.PathGoal;

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
    /** Kandidátní postele posledního hledání – uléhá se do té, ke které se došlo. */
    private java.util.List<BlockPos> beds = java.util.List.of();
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
        beds = java.util.List.of();
        attempts = 0;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        switch (phase) {
            case FIND -> {
                beds = findBeds(ctx, bot);
                if (beds.isEmpty()) {
                    cooldownTicks = 1200; // žádná postel – zkusit za minutu
                    phase = Phase.DONE;
                    return;
                }
                bed = beds.getFirst();
                phase = Phase.GO;
            }
            case GO -> {
                // Uléhá se do postele, ke které se skutečně došlo – nejbližší
                // vzdušnou čarou může být za zdí či plotem (anyNear nechá
                // o pořadí rozhodnout dosažitelnost).
                BlockPos reachable = null;
                double bestDist = 2.5 * 2.5;
                for (BlockPos candidate : beds) {
                    double dist = candidate.center().distanceSquared(ctx.position());
                    if (dist <= bestDist) {
                        bestDist = dist;
                        reachable = candidate;
                    }
                }
                if (reachable == null) {
                    ctx.navigator().navigateTo(ctx.position(), beds.size() > 1
                            ? PathGoal.anyNear(beds, 1)
                            : PathGoal.near(bed, 1));
                    if (!ctx.navigator().navigating()) {
                        phase = Phase.FIND;
                    }
                    return;
                }
                bed = reachable;
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

    /** Najde postele: nejdřív v paměti (HOME/bed), pak skenem okolí. */
    private java.util.List<BlockPos> findBeds(BotContext ctx, Bot bot) {
        WorldView world = ctx.worldView();
        if (world == null) {
            return java.util.List.of();
        }
        BlockPos center = ctx.position().toBlockPos();
        // Paměť: dům s postelí – vlastní postel má přednost před cizími.
        var remembered = bot.memory().recall(MemoryKind.HOME).stream()
                .filter(r -> "bed".equals(r.data().get("type")))
                .filter(r -> world.worldName().equals(r.world()))
                .filter(r -> r.distanceSquared(center.x(), center.y(), center.z()) < 96 * 96)
                .findFirst();
        if (remembered.isPresent()) {
            var r = remembered.get();
            return java.util.List.of(new BlockPos(r.x(), r.y(), r.z()));
        }
        // Sken okolí – všechny postele, seřazené podle vzdálenosti.
        int radius = 12;
        java.util.ArrayList<BlockPos> found = new java.util.ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    var material = world.materialAt(pos);
                    if (dev.botalive.core.inventory.Items.isBed(material)) {
                        found.add(pos);
                    }
                }
            }
        }
        found.sort(java.util.Comparator.comparingDouble(p -> p.distanceSquared(center)));
        return found.size() > 4 ? java.util.List.copyOf(found.subList(0, 4))
                : java.util.List.copyOf(found);
    }

    @Override
    public String explain(dev.botalive.api.bot.Bot bot) {
        return "je noc, jdu spát";
    }
}
