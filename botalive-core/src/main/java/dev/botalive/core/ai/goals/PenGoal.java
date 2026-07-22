package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.bot.ServerSideView;
import dev.botalive.core.build.BarrierStyle;
import dev.botalive.core.build.Enclosure;
import dev.botalive.core.chat.PhraseCategory;
import dev.botalive.core.crafting.CraftingService;
import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.husbandry.BreedService;
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
 * Ohrada kolem stáda – bot obežene shluk hospodářských zvířat plotem s brankou.
 * Uzavírá „obehnat zvířata plotem": chov ({@link BreedGoal}) stádo rozmnoží,
 * ohrada mu dá výběh (a přestane utíkat – plot drží, {@code BreedGoal} už
 * počítá se zvířaty „za plotem").
 *
 * <p>Otevřený problém (kam a jak velkou ohradu kolem <b>pohyblivého</b> stáda)
 * řeší <b>pevná ohrada přichycená na mřížku</b>: spočítá se těžiště stáda,
 * zaokrouhlí na mřížku {@link #PEN_SIZE} a ohradí se ta buňka. Tím je obdélník
 * deterministický – opakovaný běh dá tentýž, takže se ohrady nepřekrývají a
 * plán je idempotentní ({@link Enclosure} přeskočí, co už stojí). Branka je
 * otevíratelná, takže se bot nezavře – vyjde jí ven.</p>
 *
 * <p>Materiál si bot <b>vyrobí z prken</b> jako {@link SettlementFenceGoal}
 * ({@link CraftingService#craftFencing}); staví ho sdílený {@link BarrierWorker}.
 * Chová se u chovatelských profesí (farmář, pastýř, krotitel); zapíná se
 * {@code settlement.fences} (týž vypínač jako ploty domů).</p>
 */
public final class PenGoal extends AbstractGoal {

    private enum Phase { CRAFT, WORK, DONE }

    /** Poloměr hledání stáda (bloky) – jako {@link BreedGoal}. */
    private static final int SCAN_RADIUS = 16;
    /** Kolik zvířat v jedné buňce mřížky dělá stádo hodné ohrady. */
    private static final int MIN_HERD = 3;
    /** Rozměr ohrady (vnější); 7×7 = výběh 5×5, obvod 24 sloupků. */
    private static final int PEN_SIZE = 7;
    /** Odhad plaňků na obvod ohrady (2·7 + 2·7 − 4 = 24). */
    private static final int FENCE_ESTIMATE = 24;
    /** Strop kroků plánu na jednu seanci. */
    private static final int MAX_STEPS = 40;

    private final CraftingStation crafting;

    private Phase phase = Phase.DONE;
    private BlockPos penMin;
    private BlockPos penMax;
    private Material planks;
    private Material post;
    private Material gate;
    private BarrierWorker worker;
    private CompletableFuture<Boolean> craftFuture;
    private int cooldownTicks;

    /**
     * @param crafting služba craftingu – plaňky a branku bot vyrobí z prken
     */
    public PenGoal(CraftingStation crafting) {
        super("pen");
        this.crafting = crafting;
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        if (outsideOverworld(ctx) || ctx.worldView() == null) {
            return 0;
        }
        var cfg = ctx.config().settlement();
        if (!cfg.enabled() || !cfg.fences()) {
            return 0;
        }
        boolean inProgress = worker != null || phase == Phase.CRAFT || phase == Phase.WORK;
        if (!inProgress) {
            long time = ctx.worldTime();
            if (time >= 11500 && time <= 23000) {
                return 0; // staví se za světla
            }
            if (resolvePen(ctx) == null) {
                return 0; // není soustředěné stádo k ohrazení
            }
            if (!canProvision(ctx)) {
                return 0; // nemá prkna (ani hotové ploty) na materiál
            }
        }
        // Ohrada je klidná chovatelská práce – trpělivost a ochota pomoci.
        double patience = bot.personality().trait(Trait.PATIENCE);
        double helpfulness = bot.personality().trait(Trait.HELPFULNESS);
        return 3.5 + patience * 4 + helpfulness * 2;
    }

    @Override
    public void start(Bot bot) {
        BotContext ctx = ctx(bot);
        phase = Phase.CRAFT;
        worker = null;
        craftFuture = null;
        PenRect rect = resolvePen(ctx);
        if (rect == null) {
            cooldownTicks = 2400;
            phase = Phase.DONE;
            return;
        }
        penMin = rect.min();
        penMax = rect.max();
        chooseMaterials(ctx);
        if (ctx.rng().chance(0.5)) {
            ctx.chat().sayFrom(PhraseCategory.PEN_START, null);
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
            phase = Phase.WORK;
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
            giveUp(2400);
        }
    }

    /** Postaví ohradu sdíleným vykonavatelem; plán je idempotentní. */
    private void tickWork(BotContext ctx) {
        if (worker == null) {
            List<Enclosure.Placement> cells = planPen(ctx.worldView());
            if (cells.isEmpty()) {
                finish(ctx, false); // ohrada už stojí (nebo není kde) – tiše konec
                return;
            }
            worker = new BarrierWorker(cells, penCenter(), post, gate);
        }
        if (worker.tick(ctx)) {
            finish(ctx, true);
        }
    }

    /** Naplánuje plaňky+branku po obvodu ohrady (branka na severní straně). */
    private List<Enclosure.Placement> planPen(WorldView world) {
        List<Enclosure.Placement> cells = new ArrayList<>();
        for (Enclosure.Post p : Enclosure.plan(world, penMin, penMax, penMin.y(),
                Set.of(Cardinal.NORTH), MAX_STEPS)) {
            cells.addAll(Enclosure.column(p, 1)); // plot je 1 blok vysoký
        }
        return cells;
    }

    /** Střed ohrady (pro vnitřní stanoviště branky). */
    private BlockPos penCenter() {
        return new BlockPos((penMin.x() + penMax.x()) / 2, penMin.y(),
                (penMin.z() + penMax.z()) / 2);
    }

    /** Obdélník ohrady kolem shluku hospodářských zvířat v okolí, nebo {@code null}. */
    private PenRect resolvePen(BotContext ctx) {
        List<int[]> herd = new ArrayList<>();
        for (TrackedEntity animal : ctx.entities().nearby(ctx.position(), SCAN_RADIUS,
                e -> BreedService.isLivestock(e.type()))) {
            herd.add(new int[]{(int) Math.floor(animal.position().x()),
                    (int) Math.floor(animal.position().z())});
        }
        return penRect(herd, ctx.position().toBlockPos().y());
    }

    /** Obdélník ohrady a strana branky (čistá geometrie). */
    record PenRect(BlockPos min, BlockPos max) {
    }

    /**
     * Ohrada kolem stáda: buňka mřížky {@link #PEN_SIZE} obsahující těžiště
     * stáda, má-li v ní být aspoň {@link #MIN_HERD} zvířat. Mřížka dělá obdélník
     * deterministickým (opakovaný běh = tentýž → idempotence, žádné překryvy).
     *
     * @param animalsXZ souřadnice XZ zvířat v okolí
     * @param y         výška staveniště (skutečnou zem si dohledá {@link Enclosure})
     * @return obdélník ohrady, nebo {@code null} když stádo není dost soustředěné
     */
    static PenRect penRect(List<int[]> animalsXZ, int y) {
        if (animalsXZ.size() < MIN_HERD) {
            return null;
        }
        long sx = 0;
        long sz = 0;
        for (int[] a : animalsXZ) {
            sx += a[0];
            sz += a[1];
        }
        int cx = (int) Math.floor((double) sx / animalsXZ.size());
        int cz = (int) Math.floor((double) sz / animalsXZ.size());
        int gx = Math.floorDiv(cx, PEN_SIZE) * PEN_SIZE;
        int gz = Math.floorDiv(cz, PEN_SIZE) * PEN_SIZE;
        int inCell = 0;
        for (int[] a : animalsXZ) {
            if (a[0] >= gx && a[0] < gx + PEN_SIZE && a[1] >= gz && a[1] < gz + PEN_SIZE) {
                inCell++;
            }
        }
        if (inCell < MIN_HERD) {
            return null; // stádo je rozptýlené přes hranu buňky – teď neohradíme
        }
        return new PenRect(new BlockPos(gx, y, gz),
                new BlockPos(gx + PEN_SIZE - 1, y, gz + PEN_SIZE - 1));
    }

    /** Vybere druh dřeva plotu podle převažujících prken v batohu. */
    private void chooseMaterials(BotContext ctx) {
        ServerSideView.Snapshot snapshot = ctx.serverView().latest();
        planks = CraftingService.dominantPlanks(snapshot);
        BarrierStyle.Materials mats = BarrierStyle.FENCE.materials(planks);
        post = mats.post();
        gate = mats.gate();
    }

    /** Má bot čím ohradu pořídit – hotové plaňky, nebo prkna (+ ponk) na výrobu? */
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

    private void finish(BotContext ctx, boolean announce) {
        if (announce && ctx.rng().chance(0.4)) {
            ctx.chat().sayFrom(PhraseCategory.PEN_DONE, null);
        }
        worker = null;
        cooldownTicks = ctx.rng().rangeInt(6000, 12000);
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
        return "stavím ohradu kolem zvířat";
    }
}
