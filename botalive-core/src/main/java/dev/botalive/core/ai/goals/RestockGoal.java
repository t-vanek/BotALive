package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.ai.BotNeeds;
import dev.botalive.core.bot.ServerSideView;
import dev.botalive.core.inventory.InventoryHelper;
import dev.botalive.core.pathfinding.PathGoal;
import dev.botalive.core.station.ChestStation;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.WorldView;

import org.bukkit.Material;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;

import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Obecný zpětný odběr materiálu ze společného skladu – opak {@link SupplyGoal}.
 *
 * <p>Sklad ({@code WAREHOUSE}) plní horníci přebytky ({@link StashGoal} přes
 * {@code depositSurplus}: rudy, ingoty, uhlí, stavební bloky). Doteď z něj
 * ale bral jen stavitel při společné stavbě ({@code CommunalBuildGoal}) –
 * poolované rudy jinak ležely ladem. Tento cíl to obrací: <b>člen sídla,
 * kterému schází materiál pro jeho práci, si ho ze skladu vybere</b>, místo
 * aby ho šel těžit. Miner plní, ostatní berou – emergentní dělba práce.</p>
 *
 * <p>Co se bere, řídí {@link BotNeeds} (čistá funkce {@link #restockPlan}):</p>
 * <ul>
 *   <li><b>Komodity</b> – železo (surové/ingot), když ho bot s kamenným+
 *       krumpáčem nemá; uhlí, když nemá pochodně. Bere každý člen.</li>
 *   <li><b>Stavební bloky</b> – jen <b>bezdomovec</b> (potřebuje je na první
 *       dům; odemyká stavební pilíř), do stropu na dávku.</li>
 * </ul>
 *
 * <p>Role jen vychylují váhu ({@code RoleProfiles} – kovář/horník/stavitel
 * ochotněji), mechanismus je pro všechny stejný. Férovost vůči sdílenému
 * skladu: bere se jen deficit (ne celý volný batoh), se stropem na dávku a
 * cooldownem (delším, když sklad nic nedal), a v nízkém utility pásmu (pod
 * přežitím, bojem i jídlem).</p>
 */
public final class RestockGoal extends AbstractGoal {

    private enum Phase { GO, OPEN, TAKE, CLOSE, DONE }

    /** Za tímto poloměrem (v blocích) už sklad není „rozumně blízko". */
    private static final int DEPOT_REACH = 128;
    /** Kolik surového železa a ingotů si nejvýš dojít pro doplnění. */
    private static final int IRON_RAW_WANT = 8;
    private static final int IRON_INGOT_WANT = 8;
    /** Kolik uhlí si nejvýš dojít pro doplnění (pochodně, palivo). */
    private static final int COAL_WANT = 16;
    /** Kolik stavebních bloků chce mít bezdomovec, než sklad nechá být. */
    private static final int BLOCK_TARGET = 64;
    /** Strop stavebních bloků na jednu dávku (ať se sklad nevydrancuje). */
    private static final int BLOCK_TRIP_CAP = 64;

    private final ChestStation containers;

    private Phase phase = Phase.DONE;
    private BlockPos chest;
    private RestockPlan plan = RestockPlan.empty();
    private int waitTicks;
    private CompletableFuture<Integer> op;
    private boolean tookNothing;
    private int cooldownTicks;

    /**
     * @param containers sdílená stanice truhel
     */
    public RestockGoal(ChestStation containers) {
        super("restock");
        this.containers = containers;
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        if (outsideOverworld(ctx) || ctx.worldView() == null || ctx.settlements() == null) {
            return 0;
        }
        ServerSideView.Snapshot snapshot = ctx.serverView().latest();
        if (snapshot == null) {
            return 0;
        }
        boolean room = InventoryHelper.freeSlots(snapshot) > 1;
        BlockPos depot = depotChest(ctx, bot);
        RestockPlan p = restockPlan(BotNeeds.assess(snapshot), wantsBuildingBlocks(bot));
        return utilityFor(depot != null, room, p.deficitScore());
    }

    /**
     * Váha odběru (čistá funkce – testovatelná bez živého kontextu). Nese jen
     * když je sklad rozumně blízko, bot má v batohu místo a opravdu mu něco
     * schází. Roste s velikostí deficitu, ale zůstává v nízkém pásmu pomocných
     * úkonů – pod přežitím, bojem, jídlem i vlastní správou inventáře.
     *
     * @param depot        sklad je dosažitelný (kam si dojít)
     * @param room         bot má volné sloty (kam materiál dát)
     * @param deficitScore velikost deficitu (součet stropů komodit + bloků)
     * @return váha cíle (0 = neodebírat)
     */
    static double utilityFor(boolean depot, boolean room, int deficitScore) {
        if (!depot || !room || deficitScore <= 0) {
            return 0;
        }
        return 5 + Math.min(deficitScore, 40) * 0.15;
    }

    /**
     * Co si bot potřebuje ze skladu dobrat (čistá funkce nad potřebami).
     * Komodity bere každý, kdo je postrádá a jeho tier je uplatní; stavební
     * bloky jen bezdomovec ({@code wantsBuild}), do stropu na dávku.
     *
     * @param needs      potřeby bota z inventáře
     * @param wantsBuild bot potřebuje stavební bloky (nemá dům)
     * @return plán odběru (prázdný = není co brát)
     */
    static RestockPlan restockPlan(BotNeeds needs, boolean wantsBuild) {
        Map<Material, Integer> wants = new EnumMap<>(Material.class);
        // Železo: s kamenným+ krumpáčem, ale bez železa v batohu – dobrat ze skladu.
        if (needs.pickaxeTier() >= 3 && !needs.hasIronMaterial()) {
            wants.put(Material.RAW_IRON, IRON_RAW_WANT);
            wants.put(Material.IRON_INGOT, IRON_INGOT_WANT);
        }
        // Uhlí: kdykoli chybí pochodně (a bot má čím kopat, aby ho uplatnil).
        if (needs.pickaxeTier() >= 1 && !needs.hasTorches()) {
            wants.put(Material.COAL, COAL_WANT);
        }
        int blockCap = 0;
        if (wantsBuild && needs.buildingBlocks() < BLOCK_TARGET) {
            blockCap = Math.min(BLOCK_TRIP_CAP, BLOCK_TARGET - needs.buildingBlocks());
        }
        return new RestockPlan(wants, blockCap);
    }

    @Override
    public void start(Bot bot) {
        BotContext ctx = ctx(bot);
        chest = depotChest(ctx, bot);
        ServerSideView.Snapshot snapshot = ctx.serverView().latest();
        plan = snapshot == null
                ? RestockPlan.empty()
                : restockPlan(BotNeeds.assess(snapshot), wantsBuildingBlocks(bot));
        op = null;
        tookNothing = false;
        phase = chest == null || plan.isEmpty() ? Phase.DONE : Phase.GO;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        switch (phase) {
            case GO -> tickGo(ctx);
            case OPEN -> tickOpen(ctx);
            case TAKE -> tickTake(ctx);
            case CLOSE -> tickClose(ctx);
            case DONE -> {
            }
        }
    }

    private void tickGo(BotContext ctx) {
        if (chest.center().distanceSquared(ctx.position()) > 3.0 * 3.0) {
            ctx.navigator().navigateTo(ctx.position(), PathGoal.near(chest, 2));
            if (!ctx.navigator().navigating()) {
                finish(1800); // sklad nedosažitelný
            }
            return;
        }
        ctx.navigator().stop();
        ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0), chest.center().add(0, 0.5, 0));
        waitTicks = ctx.rng().rangeInt(4, 10);
        phase = Phase.OPEN;
    }

    private void tickOpen(BotContext ctx) {
        if (--waitTicks > 0) {
            return;
        }
        ctx.actions().useItemOn(chest, Direction.UP);
        waitTicks = ctx.rng().rangeInt(12, 28);
        phase = Phase.TAKE;
    }

    private void tickTake(BotContext ctx) {
        if (--waitTicks > 0) {
            return;
        }
        if (op == null) {
            op = containers.withdrawRestock(ctx, ctx.worldView().worldName(), chest,
                    plan.wants(), plan.blockCap());
            return;
        }
        if (!op.isDone()) {
            return;
        }
        int moved = op.getNow(0);
        op = null;
        tookNothing = moved <= 0;
        if (moved > 0 && ctx.rng().chance(0.5)) {
            ctx.chat().say("beru si materiál ze společného skladu, ať nemusím shánět");
        }
        waitTicks = ctx.rng().rangeInt(5, 12);
        phase = Phase.CLOSE;
    }

    private void tickClose(BotContext ctx) {
        if (--waitTicks > 0) {
            return;
        }
        ctx.actions().closeContainer();
        // Prázdný sklad (nic nedal) → delší pauza, ať se u něj bot nezacyklí.
        finish(tookNothing ? ctx.rng().rangeInt(2400, 4000) : ctx.rng().rangeInt(1200, 3000));
    }

    private void finish(int cooldown) {
        cooldownTicks = cooldown;
        phase = Phase.DONE;
    }

    @Override
    public void stop(Bot bot) {
        ctx(bot).actions().closeContainer();
        ctx(bot).navigator().stop();
    }

    @Override
    public boolean finished(Bot bot) {
        return phase == Phase.DONE;
    }

    @Override
    public String explain(Bot bot) {
        return "beru si materiál ze společného skladu";
    }

    // ==================================================================

    /**
     * Zásobovací truhla skladu, je-li rozumně blízko a opravdu ve světě stojí
     * (jinak {@code null}). Stejná geometrie jako {@link SupplyGoal} – sklad
     * existuje až po dostavbě {@code WAREHOUSE}.
     */
    private BlockPos depotChest(BotContext ctx, Bot bot) {
        WorldView world = ctx.worldView();
        if (world == null) {
            return null;
        }
        BlockPos depot = MaterialDepot.chest(ctx.settlements(), bot.id()).orElse(null);
        if (depot == null) {
            return null;
        }
        if (depot.distanceSquared(ctx.position().toBlockPos()) > DEPOT_REACH * DEPOT_REACH) {
            return null; // sklad přes půl světa – dojít si netáhne
        }
        Material material = world.materialAt(depot);
        if (material != null && material != Material.CHEST
                && material != Material.TRAPPED_CHEST) {
            return null; // sklad zbořený / jiný svět – truhla tam není
        }
        return depot;
    }

    /** Nemá bot vlastní dům? Pak si smí ze skladu dobrat stavební bloky. */
    private boolean wantsBuildingBlocks(Bot bot) {
        for (var home : bot.memory().recall(MemoryKind.HOME)) {
            if ("house".equals(home.data().get("type"))) {
                return false;
            }
        }
        return true;
    }

    /**
     * Plán odběru: mapa {@code materiál → strop kusů} pro komodity a strop
     * stavebních bloků. Čistá hodnota – jádro rozhodování {@link #restockPlan}.
     *
     * @param wants    komodity a jejich stropy (rudy, ingoty, uhlí…)
     * @param blockCap strop stavebních bloků (0 = žádné)
     */
    record RestockPlan(Map<Material, Integer> wants, int blockCap) {

        private static final RestockPlan EMPTY = new RestockPlan(Map.of(), 0);

        static RestockPlan empty() {
            return EMPTY;
        }

        boolean isEmpty() {
            return wants.isEmpty() && blockCap <= 0;
        }

        /** Velikost deficitu: součet stropů komodit + bloků (řídí utilitu). */
        int deficitScore() {
            int score = blockCap;
            for (int want : wants.values()) {
                score += want;
            }
            return score;
        }
    }
}
