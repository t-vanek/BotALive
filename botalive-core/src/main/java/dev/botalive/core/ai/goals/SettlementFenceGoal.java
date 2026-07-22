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
 * Oplocení a <b>oprava plotu</b> vlastního domu – bot kolem parcely postaví
 * (a udržuje) plot s brankou ke dveřím. Funguje pro člena vesnice i samotáře
 * (parcelu zná z {@link SettlementService#claimedPlot} nebo z HOME dat).
 *
 * <p><b>Oprava</b>: {@link Enclosure#assess} pozná pár děr v jinak stojícím
 * plotu; poškození zvedne prioritu (nad běžnou stavbu, ale pod ohradou zvířat –
 * {@link BarrierRepair}). Materiál si bot <b>vyrobí z prken</b>, a když nemá ani
 * ta, <b>dojde nasekat dřevo</b> ({@link BarrierGather} → {@link
 * CraftingService#craftPlanks} → {@code craftFencing}); pak plot postaví/dospraví
 * sdílený {@link BarrierWorker} (idempotentně). Zapíná se {@code settlement.fences}.</p>
 */
public final class SettlementFenceGoal extends AbstractGoal {

    private enum Phase { PROVISION, WORK, DONE }

    /** Odsazení plotu od domu (dvorek) – domek 4×4 → plot 6×6. */
    private static final int MARGIN = 1;
    /** Odhad plaňků na obvod plotu 6×6 (2·6 + 2·6 − 4 = 20). */
    private static final int FENCE_ESTIMATE = 20;
    /** Strop kroků plánu na jednu seanci (zbytek příště, plán je idempotentní). */
    private static final int MAX_STEPS = 40;
    /** Okruh kolem domova, ve kterém se plot řeší (nechodí kvůli němu daleko). */
    private static final int NEAR_HOME = 48;

    private final CraftingStation crafting;
    private final RepairAssessor assessor = new RepairAssessor();

    private Phase phase = Phase.DONE;
    private BlockPos origin;
    private Cardinal facing = Cardinal.NORTH;
    private Material planks;
    private Material post;
    private Material gate;
    private BarrierWorker worker;
    private BarrierGather gather;
    private CompletableFuture<Boolean> craftFuture;
    private int cooldownTicks;

    /**
     * @param crafting služba craftingu – plaňky/branku vyrobí z prken (a klády z lesa)
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
        boolean inProgress = worker != null || gather != null
                || phase == Phase.PROVISION || phase == Phase.WORK;
        if (!inProgress) {
            // Plot domu se řeší jen za světla (není urgentní jako hradby/ohrada).
            long time = ctx.worldTime();
            if (time >= 11500 && time <= 23000) {
                return 0;
            }
            if (ctx.position().toBlockPos().distanceSquared(plotCenter())
                    > (double) NEAR_HOME * NEAR_HOME) {
                return 0;
            }
        }
        FenceBounds b = fenceBounds(origin, facing);
        Enclosure.Assessment a = assessor.assess(ctx, b.min(), b.max(), origin.y(),
                Set.of(b.gate()));
        boolean damaged = BarrierRepair.isDamaged(a);
        if (!inProgress) {
            if (a.total() > 0 && a.missing() == 0) {
                return 0; // celý plot stojí – nic k dělání
            }
            if (!damaged && !canProvision(ctx)) {
                return 0; // nová stavba čeká na materiál (oprava si ho dojde sehnat)
            }
        }
        if (damaged) {
            return BarrierRepair.houseFenceUrgency(a); // oprava – nad běžnou práci
        }
        double helpfulness = bot.personality().trait(Trait.HELPFULNESS);
        return 4 + helpfulness * 4; // nová stavba – nízká priorita
    }

    @Override
    public void start(Bot bot) {
        BotContext ctx = ctx(bot);
        phase = Phase.PROVISION;
        worker = null;
        gather = null;
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
            case PROVISION -> tickProvision(ctx, bot);
            case WORK -> tickWork(ctx);
            case DONE -> {
            }
        }
    }

    /**
     * Zajistí materiál: dost plaňků (vyrobí z prken; klády nasekané v lese
     * napřed zpracuje na prkna); když prkna ani klády nejsou, dojde nasekat
     * dřevo ({@link BarrierGather}). Branka je bonus – neblokuje. Pak jde stavět.
     */
    private void tickProvision(BotContext ctx, Bot bot) {
        if (craftFuture != null) {
            if (!craftFuture.isDone()) {
                return;
            }
            craftFuture.getNow(false);
            craftFuture = null;
            return; // přehodnotit příští tick (materiál se změnil)
        }
        ServerSideView.Snapshot snapshot = ctx.serverView().latest();
        if (snapshot == null) {
            giveUp(1200);
            return;
        }
        chooseMaterials(ctx); // druh dřeva podle aktuálního inventáře (i po sehnání)
        int haveFences = ctx.inventory().countItem(snapshot, post);
        boolean hasTable = snapshot.hasItem(m -> m == Material.CRAFTING_TABLE);

        if (haveFences < FENCE_ESTIMATE) {
            int need = FENCE_ESTIMATE - haveFences;
            if (planks != null && hasTable
                    && CraftingService.canCraftFencing(snapshot, planks, need, 0)) {
                craftFuture = crafting.craftFencing(bot.id(), planks, post, gate, need, 0);
                return;
            }
            if (snapshot.hasItem(m -> m.name().endsWith("_LOG"))) {
                craftFuture = crafting.craftPlanks(bot.id(), 8); // klády → prkna
                return;
            }
            if (!hasTable) {
                giveUp(2400); // bez ponku plot nevyrobí
                return;
            }
            if (gather == null) {
                gather = new BarrierGather(m -> m.name().endsWith("_LOG"));
            }
            if (gather.tick(ctx) == BarrierGather.State.EXHAUSTED) {
                gather = null;
                giveUp(2400); // v okolí není dřevo
            }
            return;
        }
        // Dost plaňků; branka je bonus – dorob ji, když jde, ale neblokuj na ní.
        if (ctx.inventory().countItem(snapshot, gate) < 1 && planks != null && hasTable
                && CraftingService.canCraftFencing(snapshot, planks, 0, 1)) {
            craftFuture = crafting.craftFencing(bot.id(), planks, post, gate, 0, 1);
            return;
        }
        gather = null;
        phase = Phase.WORK;
    }

    /** Postaví/dospraví plot sdíleným vykonavatelem; plán je idempotentní. */
    private void tickWork(BotContext ctx) {
        if (worker == null) {
            chooseMaterials(ctx); // z čeho bot nakonec staví
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

    /** Má bot čím plot pořídit hned – hotové plaňky, nebo prkna (+ ponk)? */
    private boolean canProvision(BotContext ctx) {
        ServerSideView.Snapshot snapshot = ctx.serverView().latest();
        Material dominant = CraftingService.dominantPlanks(snapshot);
        if (dominant == null) {
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
        gather = null;
        cooldownTicks = cooldown;
        phase = Phase.DONE;
    }

    private void giveUp(int cooldown) {
        worker = null;
        gather = null;
        craftFuture = null;
        cooldownTicks = cooldown;
        phase = Phase.DONE;
    }

    @Override
    public void stop(Bot bot) {
        BotContext ctx = ctx(bot);
        if (worker != null) {
            worker.cancel(ctx);
            worker = null;
        }
        if (gather != null) {
            gather.cancel(ctx);
            gather = null;
        }
        ctx.navigator().stop();
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
