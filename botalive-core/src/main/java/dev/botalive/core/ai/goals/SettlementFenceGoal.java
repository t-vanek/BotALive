package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.memory.MemoryRecord;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.bot.ServerSideView;
import dev.botalive.core.build.BarrierStyle;
import dev.botalive.core.build.Enclosure;
import dev.botalive.core.build.HouseBlueprint;
import dev.botalive.core.chat.PhraseCategory;
import dev.botalive.core.crafting.CraftingService;
import dev.botalive.core.settlement.SettlementService;
import dev.botalive.core.station.CraftingStation;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Cardinal;
import dev.botalive.core.world.WorldView;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

/**
 * Oplocení vlastního domu – bot si kolem parcely postaví plot s brankou ke
 * dveřím. Uzavírá „obehnat domy plotem": funguje pro člena vesnice i samotáře
 * (parcelu zná z {@link SettlementService#claimedPlot} nebo z HOME dat, přesně
 * jako {@link MaintainHomeGoal}).
 *
 * <p>Materiál si bot <b>vyrobí z prken</b>, která běžně má – plot v batohu skoro
 * nikdy nemá ({@link CraftingService#craftFencing}); pak plot postaví sdílený
 * {@link BarrierWorker} nad čistým plánem {@link Enclosure} (obvod parcely,
 * branka na straně dveří). Idempotentní: {@code Enclosure.plan} přeskočí, co už
 * stojí, takže se plot dodělá napříč seancemi a samoopravuje se; zapíná se
 * {@code settlement.fences} (default vypnuto).</p>
 */
public final class SettlementFenceGoal extends AbstractGoal {

    private enum Phase { CRAFT, WORK, DONE }

    /** Odsazení plotu od domu (dvorek) – domek 4×4 → plot 6×6. */
    private static final int MARGIN = 1;
    /** Odhad plaňků na obvod plotu 6×6 (2·6 + 2·6 − 4 = 20) – kolik dorobit. */
    private static final int FENCE_ESTIMATE = 20;
    /** Strop kroků plánu na jednu seanci (zbytek příště, plán je idempotentní). */
    private static final int MAX_STEPS = 40;
    /** Okruh kolem domova, ve kterém se plot řeší (nechodí kvůli němu daleko). */
    private static final int NEAR_HOME = 48;

    private final CraftingStation crafting;

    private Phase phase = Phase.DONE;
    private BlockPos origin;
    private Cardinal facing = Cardinal.NORTH;
    private Material planks;
    private Material post;
    private Material gate;
    private BarrierWorker worker;
    private CompletableFuture<Boolean> craftFuture;
    private int cooldownTicks;

    /**
     * @param crafting služba craftingu – plaňky a branku si bot vyrobí z prken
     */
    public SettlementFenceGoal(CraftingStation crafting) {
        super("settlement-fences");
        this.crafting = crafting;
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        var cfg = ctx.config().settlement();
        if (!cfg.enabled() || !cfg.fences() || outsideOverworld(ctx) || ctx.worldView() == null) {
            return 0;
        }
        if (!resolvePlot(ctx, bot)) {
            return 0; // nemá dům/parcelu k oplocení
        }
        boolean inProgress = worker != null || phase == Phase.CRAFT || phase == Phase.WORK;
        if (!inProgress) {
            // Staví se za světla, jako domy, dům a společné stavby.
            long time = ctx.worldTime();
            if (time >= 11500 && time <= 23000) {
                return 0;
            }
            if (ctx.position().toBlockPos().distanceSquared(plotCenter())
                    > (double) NEAR_HOME * NEAR_HOME) {
                return 0;
            }
            if (!canProvision(ctx)) {
                return 0; // nemá prkna (ani hotové ploty) na materiál
            }
        }
        // Nízká priorita – řeší se, když je klid a chuť pomoci (po domě a údržbě).
        double helpfulness = bot.personality().trait(Trait.HELPFULNESS);
        return 4 + helpfulness * 4;
    }

    @Override
    public void start(Bot bot) {
        BotContext ctx = ctx(bot);
        phase = Phase.CRAFT;
        worker = null;
        craftFuture = null;
        resolvePlot(ctx, bot);
        chooseMaterials(ctx);
        if (ctx.rng().chance(0.5)) {
            ctx.chat().sayFrom(PhraseCategory.SETTLEMENT_FENCE_START, null);
        }
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        switch (phase) {
            case CRAFT -> tickCraft(ctx, bot);
            case WORK -> tickWork(ctx);
            case DONE -> {
            }
        }
    }

    /** Doplní materiál (vyrobí plaňky/branku z prken), pak jde stavět. */
    private void tickCraft(BotContext ctx, Bot bot) {
        ServerSideView.Snapshot snapshot = ctx.serverView().latest();
        if (snapshot == null || post == null) {
            giveUp(1200);
            return;
        }
        int needFences = FENCE_ESTIMATE - ctx.inventory().countItem(snapshot, post);
        int needGates = 1 - ctx.inventory().countItem(snapshot, gate);
        if (needFences <= 0 && needGates <= 0) {
            phase = Phase.WORK; // materiál už má (dorobil dřív, nebo z lootu)
            return;
        }
        if (craftFuture == null) {
            craftFuture = crafting.craftFencing(bot.id(), planks, post, gate,
                    Math.max(0, needFences), Math.max(0, needGates));
            return;
        }
        if (!craftFuture.isDone()) {
            return;
        }
        boolean crafted = craftFuture.getNow(false);
        craftFuture = null;
        if (crafted) {
            phase = Phase.WORK;
        } else {
            giveUp(2400); // prkna mezitím ubyla / chybí ponk – zkusí se zas později
        }
    }

    /** Postaví plot sdíleným vykonavatelem; plán je idempotentní. */
    private void tickWork(BotContext ctx) {
        if (worker == null) {
            List<Enclosure.Placement> cells = planFence(ctx.worldView());
            if (cells.isEmpty()) {
                finish(ctx, 6000, false); // plot už stojí (nebo není kde) – tiše konec
                return;
            }
            worker = new BarrierWorker(cells, plotCenter(), post, gate);
        }
        if (worker.tick(ctx)) {
            finish(ctx, 6000, true);
        }
    }

    /** Obdélník plotu a strana branky pro parcelu domu (čistá geometrie, testovatelné). */
    record FenceBounds(BlockPos min, BlockPos max, Cardinal gate) {
    }

    /**
     * Plot kolem domku {@link HouseBlueprint#SIZE}×SIZE s odsazením {@link #MARGIN}
     * (dvorek) a brankou na straně dveří.
     *
     * @param origin roh půdorysu domu
     * @param facing orientace dveří (sem přijde branka)
     * @return obdélník plotu a strana branky
     */
    static FenceBounds fenceBounds(BlockPos origin, Cardinal facing) {
        return new FenceBounds(
                origin.offset(-MARGIN, 0, -MARGIN),
                origin.offset(HouseBlueprint.SIZE - 1 + MARGIN, 0, HouseBlueprint.SIZE - 1 + MARGIN),
                facing);
    }

    /** Naplánuje plaňky+branku po obvodu parcely (odsazení MARGIN, branka ke dveřím). */
    private List<Enclosure.Placement> planFence(WorldView world) {
        FenceBounds bounds = fenceBounds(origin, facing);
        List<Enclosure.Placement> cells = new ArrayList<>();
        for (Enclosure.Post p : Enclosure.plan(world, bounds.min(), bounds.max(), origin.y(),
                Set.of(bounds.gate()), MAX_STEPS)) {
            cells.addAll(Enclosure.column(p, 1)); // plot je 1 blok vysoký
        }
        return cells;
    }

    /** Střed parcely (pro vnitřní stanoviště branky a měření vzdálenosti). */
    private BlockPos plotCenter() {
        int half = HouseBlueprint.SIZE / 2;
        return origin.offset(half, 0, half);
    }

    /** Vybere druh dřeva plotu podle převažujících prken v batohu. */
    private void chooseMaterials(BotContext ctx) {
        ServerSideView.Snapshot snapshot = ctx.serverView().latest();
        planks = CraftingService.dominantPlanks(snapshot);
        BarrierStyle.Materials mats = BarrierStyle.FENCE.materials(planks);
        post = mats.post();
        gate = mats.gate();
    }

    /** Má bot čím plot pořídit – hotové plaňky, nebo prkna (+ ponk) na výrobu? */
    private boolean canProvision(BotContext ctx) {
        ServerSideView.Snapshot snapshot = ctx.serverView().latest();
        Material dominant = CraftingService.dominantPlanks(snapshot);
        if (dominant == null) {
            // Bez prken jen tehdy, když už ploty v batohu jsou (loot/obchod).
            return snapshot != null && snapshot.hasItem(m -> m.name().endsWith("_FENCE"));
        }
        Material fence = BarrierStyle.FENCE.materials(dominant).post();
        if (ctx.inventory().countItem(snapshot, fence) >= FENCE_ESTIMATE) {
            return true;
        }
        return snapshot.hasItem(m -> m == Material.CRAFTING_TABLE)
                && CraftingService.canCraftFencing(snapshot, dominant, FENCE_ESTIMATE, 1);
    }

    /** Origin a orientace domu: parcela vesnice → HOME data → rekonstrukce. */
    private boolean resolvePlot(BotContext ctx, Bot bot) {
        SettlementService settlements = ctx.settlements();
        if (settlements != null) {
            var plot = settlements.claimedPlot(bot.id());
            if (plot.isPresent() && plot.get().origin() != null) {
                origin = plot.get().origin();
                facing = plot.get().facing();
                return true;
            }
        }
        for (MemoryRecord record : bot.memory().recall(MemoryKind.HOME)) {
            if (!"house".equals(record.data().get("type"))) {
                continue;
            }
            String ox = record.data().get("ox");
            if (ox != null) {
                try {
                    origin = new BlockPos(Integer.parseInt(ox),
                            Integer.parseInt(record.data().get("oy")),
                            Integer.parseInt(record.data().get("oz")));
                    String storedFacing = record.data().get("facing");
                    facing = storedFacing != null ? Cardinal.valueOf(storedFacing) : Cardinal.NORTH;
                    return true;
                } catch (RuntimeException e) {
                    // poškozená metadata – rekonstrukce ze stand pointu (sever)
                }
            }
            origin = new BlockPos(record.x(), record.y(), record.z()).offset(-2, 0, -2);
            facing = Cardinal.NORTH;
            return true;
        }
        return false;
    }

    private void finish(BotContext ctx, int cooldown, boolean announce) {
        if (announce && ctx.rng().chance(0.4)) {
            ctx.chat().sayFrom(PhraseCategory.SETTLEMENT_FENCE_DONE, null);
        }
        worker = null;
        cooldownTicks = cooldown;
        phase = Phase.DONE;
    }

    private void giveUp(int cooldown) {
        worker = null;
        craftFuture = null;
        cooldownTicks = cooldown;
        phase = Phase.DONE;
    }

    @Override
    public void stop(Bot bot) {
        if (worker != null) {
            worker.cancel(ctx(bot));
            worker = null;
        }
        ctx(bot).navigator().stop();
    }

    @Override
    public boolean finished(Bot bot) {
        return phase == Phase.DONE;
    }

    @Override
    public String explain(Bot bot) {
        return "stavím plot kolem domu";
    }
}
