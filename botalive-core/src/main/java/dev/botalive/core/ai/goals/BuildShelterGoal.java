package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.inventory.InventoryHelper;
import dev.botalive.core.tasks.PlaceBlockTask;
import dev.botalive.core.util.BlockPos;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;

/**
 * Stavba nouzového úkrytu na noc.
 *
 * <p>Opatrný bot si za soumraku, pokud má stavební bloky, postaví kolem sebe
 * jednoduchý úkryt: obvodové zdi 1×1×2 kolem vlastní pozice a strop. Pozici si
 * uloží jako {@link MemoryKind#HOME} – další noci se sem může vracet místo
 * stavění nového úkrytu.</p>
 */
public final class BuildShelterGoal extends AbstractGoal {

    private final Deque<PlaceBlockTask> plan = new ArrayDeque<>();
    private PlaceBlockTask current;
    private boolean planned;
    private int cooldownTicks;

    /** Vytvoří cíl. */
    public BuildShelterGoal() {
        super("shelter");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        // Úkryty na noc patří do overworldu (v Netheru není noc).
        if (outsideOverworld(ctx)) {
            return 0;
        }
        // Úkryt se staví na noc – a taky v bouřce (blesky zakládají požáry),
        // když je domov z ruky.
        if (!isNight(ctx.worldTime()) && !ctx.thundering()) {
            return 0;
        }
        // Úkryt nedává smysl, když je domov blízko.
        var home = bot.memory().recallNearest(MemoryKind.HOME,
                ctx.worldView() == null ? "" : ctx.worldView().worldName(),
                (int) ctx.position().x(), (int) ctx.position().y(), (int) ctx.position().z());
        if (home.isPresent() && home.get().distanceSquared(
                (int) ctx.position().x(), (int) ctx.position().y(), (int) ctx.position().z()) < 48 * 48) {
            return 0;
        }
        var snapshot = ctx.serverView().latest();
        if (snapshot == null || !snapshot.hasItem(InventoryHelper::isBuildingBlock)) {
            return 0;
        }
        // V katastru vesnice se panikářské budky nestaví – náves není
        // staveniště a mezi domy je světlo; noc se přečká mezi lidmi.
        var settlements = ctx.settlements();
        if (settlements != null && ctx.config().settlement().enabled()
                && ctx.worldView() != null
                && settlements.nearestSettlement(ctx.worldView().worldName(),
                        ctx.position().toBlockPos(),
                        ctx.config().settlement().plotSpacing() * 2).isPresent()) {
            return 0;
        }
        double caution = bot.personality().trait(Trait.CAUTION);
        return 10 + caution * 30;
    }

    /** Noc/soumrak (neznámý čas se nepovažuje za noc). */
    private static boolean isNight(long time) {
        return time >= 12500 && time <= 23000;
    }

    @Override
    public void start(Bot bot) {
        plan.clear();
        current = null;
        planned = false;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        if (!planned) {
            planShelter(ctx);
            planned = true;
        }
        ctx.inventory().equipBuildingBlock(ctx.serverView().latest());

        if (current == null) {
            current = plan.poll();
            if (current == null) {
                finishShelter(ctx, bot);
                return;
            }
        }
        if (current.tick(ctx)) {
            current = null;
        }
    }

    @Override
    public void stop(Bot bot) {
        if (current != null) {
            current.cancel(ctx(bot));
            current = null;
        }
        plan.clear();
        super.stop(bot);
    }

    @Override
    public boolean finished(Bot bot) {
        return planned && plan.isEmpty() && current == null;
    }

    /** Naplánuje obvodové zdi (2 bloky vysoké) + strop kolem aktuální pozice. */
    private void planShelter(BotContext ctx) {
        BlockPos feet = ctx.position().toBlockPos();
        int[][] ring = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int level = 0; level <= 1; level++) {
            for (int[] offset : ring) {
                BlockPos wall = feet.offset(offset[0], level, offset[1]);
                if (ctx.worldView() != null && ctx.worldView().traitsAt(wall).passable()) {
                    plan.add(new PlaceBlockTask(wall));
                }
            }
        }
        BlockPos roof = feet.offset(0, 2, 0);
        if (ctx.worldView() != null && ctx.worldView().traitsAt(roof).passable()) {
            plan.add(new PlaceBlockTask(roof));
        }
    }

    /** Uloží domov a nastaví cooldown do dalšího večera. */
    private void finishShelter(BotContext ctx, Bot bot) {
        BlockPos feet = ctx.position().toBlockPos();
        if (ctx.worldView() != null) {
            bot.memory().remember(MemoryKind.HOME, ctx.worldView().worldName(),
                    feet.x(), feet.y(), feet.z(), null, Map.of("type", "shelter"), 0.8);
        }
        cooldownTicks = 6000; // ~5 minut
    }

    @Override
    public String explain(dev.botalive.api.bot.Bot bot) {
        return "stmívá se, stavím si nouzový úkryt";
    }
}
