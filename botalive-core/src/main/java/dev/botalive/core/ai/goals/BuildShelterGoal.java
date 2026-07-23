package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.memory.MemoryRecord;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.inventory.InventoryHelper;
import dev.botalive.core.tasks.BotTask;
import dev.botalive.core.tasks.MineBlockTask;
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

    /** Kolem přístřešku se řeší jen když je po ruce (netrmácet se kvůli úklidu). */
    private static final int DEMOLISH_REACH = 80;
    /** Osmi­směrný věnec zdí kolem stavitele (parita s {@link #planShelter}). */
    private static final int[][] RING =
            {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private enum Mode { BUILD, DEMOLISH }

    private final Deque<PlaceBlockTask> plan = new ArrayDeque<>();
    private PlaceBlockTask current;
    private boolean planned;
    private int cooldownTicks;

    /** Staví přístřešek (noc), nebo bourá dočasný přístřešek (má už reálný dům)? */
    private Mode mode = Mode.BUILD;
    /** Bloky bouraného přístřešku a jeho pozice (střed, uložený v HOME). */
    private final Deque<BlockPos> demolish = new ArrayDeque<>();
    private BlockPos shelterFeet;
    private BotTask demolishTask;
    private boolean demolishPlanned;

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
        // Dočasný přístřešek je jen na první noc: jakmile má bot reálný dům, ve dne
        // ho zbourá, ať po světě nezůstávají panikářské budky. Nízká priorita –
        // úklid se řeší, jen když není nic naléhavějšího (nikdy nepřebije přežití).
        if (canDemolishShelter(ctx, bot)) {
            mode = Mode.DEMOLISH;
            return 3;
        }
        mode = Mode.BUILD;
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
        demolish.clear();
        demolishTask = null;
        demolishPlanned = false;
        shelterFeet = null;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        if (mode == Mode.DEMOLISH) {
            tickDemolish(ctx, bot);
            return;
        }
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
        if (demolishTask != null) {
            demolishTask.cancel(ctx(bot));
            demolishTask = null;
        }
        plan.clear();
        demolish.clear();
        super.stop(bot);
    }

    @Override
    public boolean finished(Bot bot) {
        if (mode == Mode.DEMOLISH) {
            return demolishPlanned && demolish.isEmpty() && demolishTask == null;
        }
        return planned && plan.isEmpty() && current == null;
    }

    // ============================================================ demolice

    /**
     * Smí bot ve dne zbourat svůj dočasný přístřešek? Jen když už má REÁLNÝ dům
     * (přístřešek z první noci je pak zbytečný), je den a klid (v noci/bouřce si
     * ho nech) a přístřešek je po ruce (nechodit kvůli úklidu přes celý svět).
     */
    private boolean canDemolishShelter(BotContext ctx, Bot bot) {
        if (ctx.worldView() == null || isNight(ctx.worldTime()) || ctx.thundering()) {
            return false;
        }
        if (!hasRealHouse(bot)) {
            return false; // bez trvalého domova si přístřešek nech
        }
        MemoryRecord shelter = shelterRecord(bot);
        if (shelter == null || !ctx.worldView().worldName().equals(shelter.world())) {
            return false;
        }
        BlockPos feet = ctx.position().toBlockPos();
        return shelter.distanceSquared(feet.x(), feet.y(), feet.z())
                < DEMOLISH_REACH * DEMOLISH_REACH;
    }

    /** Zboří přístřešek: dojde k němu a vytěží jeho zdi a strop, pak ho zapomene. */
    private void tickDemolish(BotContext ctx, Bot bot) {
        if (!demolishPlanned) {
            MemoryRecord shelter = shelterRecord(bot);
            if (shelter == null) {
                cooldownTicks = 6000;
                return;
            }
            shelterFeet = new BlockPos(shelter.x(), shelter.y(), shelter.z());
            for (int level = 0; level <= 1; level++) {
                for (int[] offset : RING) {
                    demolish.add(shelterFeet.offset(offset[0], level, offset[1]));
                }
            }
            demolish.add(shelterFeet.offset(0, 2, 0)); // strop
            demolishPlanned = true;
        }
        if (demolishTask != null) {
            if (demolishTask.tick(ctx)) {
                demolishTask = null;
            }
            return;
        }
        // Dojít k přístřešku (odtud dosáhne na celou budku 1×1).
        if (ctx.position().toBlockPos().distanceSquared(shelterFeet) > 9) {
            ctx.navigator().navigateTo(ctx.position(), shelterFeet);
            if (!ctx.navigator().navigating()) {
                finishDemolish(bot); // nedostupné – zapomenout, ať se necyklí
            }
            return;
        }
        ctx.navigator().stop();
        BlockPos block = demolish.poll();
        if (block == null) {
            finishDemolish(bot);
            return;
        }
        if (ctx.worldView() != null && ctx.worldView().traitsAt(block).solid()) {
            demolishTask = new MineBlockTask(block);
        }
    }

    /** Zapomene přístřešek (dům je teď skutečný domov) a nastaví klid. */
    private void finishDemolish(Bot bot) {
        bot.memory().forgetIf(MemoryKind.HOME, r -> "shelter".equals(r.data().get("type")));
        demolish.clear();
        cooldownTicks = 6000;
    }

    /** HOME záznam dočasného přístřešku (type=shelter), nebo {@code null}. */
    private static MemoryRecord shelterRecord(Bot bot) {
        for (MemoryRecord record : bot.memory().recall(MemoryKind.HOME)) {
            if ("shelter".equals(record.data().get("type"))) {
                return record;
            }
        }
        return null;
    }

    /** Má bot skutečný dům (ne jen nouzový přístřešek)? */
    private static boolean hasRealHouse(Bot bot) {
        for (MemoryRecord record : bot.memory().recall(MemoryKind.HOME)) {
            if ("house".equals(record.data().get("type"))) {
                return true;
            }
        }
        return false;
    }

    /** Naplánuje obvodové zdi (2 bloky vysoké) + strop kolem aktuální pozice. */
    private void planShelter(BotContext ctx) {
        BlockPos feet = ctx.position().toBlockPos();
        for (int level = 0; level <= 1; level++) {
            for (int[] offset : RING) {
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
        return mode == Mode.DEMOLISH
                ? "bourám starý nouzový úkryt, mám už pořádný dům"
                : "stmívá se, stavím si nouzový úkryt";
    }
}
