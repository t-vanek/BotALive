package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.bot.ServerSideView;
import dev.botalive.core.chat.PhraseCategory;
import dev.botalive.core.crafting.BrewPlanner;
import dev.botalive.core.inventory.InventoryHelper;
import dev.botalive.core.inventory.ItemVariants;
import dev.botalive.core.station.BrewingStation;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.vehicle.Boats;
import dev.botalive.core.world.WorldView;
import org.bukkit.Material;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;

import java.util.concurrent.CompletableFuture;
import dev.botalive.core.pathfinding.PathGoal;

/**
 * Vaření lektvarů – alchymistická dílna bota.
 *
 * <p>Bot s netherovou bradavicí (pevnosti, vlastní záhon) naplní lahve
 * u vody, u varného stojanu (najde, nebo si ho položí – stojan vyrábí
 * {@code CraftPlanner} z blaze rodu) uvaří awkward základ a z něj efekty
 * podle přísad: odolnost ohni (magma krém), léčení (třpytivý meloun), sílu
 * (blaze prach) a jed, který střelným prachem převrací na útočný splash.
 * Co vařit rozhoduje čistý {@link BrewPlanner}; samotné vaření běží vanilla
 * mechanikou (20 s na vsázku, palivo blaze prach) přes {@link BrewingStation}
 * – server-side i paketově.</p>
 *
 * <p>Hotové lektvary pak žijí vlastním životem: odolnost ohni pije
 * {@code NetherGoal} před sestupem za troskami a nouzově cíl {@code drink},
 * splash jed hází {@code CombatGoal} po nepřátelích.</p>
 */
public final class BrewGoal extends AbstractGoal {

    /** Cílový počet lahví s vodou pro jednu seanci (sloty stojanu). */
    private static final int WATER_TARGET = 3;

    /** Rozpočet čekání na jednu vsázku (ticky; vanilla vaří 400). */
    private static final int BREW_WAIT_BUDGET = 700;

    private enum Phase { PLAN, WATER, STAND_FIND, GO_STAND, LOAD, WAIT, DONE }

    private final BrewingStation station;

    private Phase phase = Phase.PLAN;
    private BrewPlanner.Batch batch;
    private BlockPos standPos;
    private StationPlacement placement;
    private CompletableFuture<BrewingStation.LoadReport> loadFuture;
    private CompletableFuture<Integer> collectFuture;
    private int waitTicks;
    private int fillTicks;
    private int goTicks;
    private int batches;
    private int cooldownTicks;

    /**
     * @param station stanice varných stojanů (server-side/paketová)
     */
    public BrewGoal(BrewingStation station) {
        super("brew");
        this.station = station;
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        if (!ctx.config().nether().brewing() || outsideOverworld(ctx)
                || ctx.clientState().dead()) {
            return 0;
        }
        var snapshot = ctx.serverView().latest();
        if (snapshot == null) {
            return 0;
        }
        BrewPlanner.State state = plannerState(snapshot);
        boolean canFill = state.anythingMissing() && state.wart() > 0
                && InventoryHelper.countItem(snapshot, Material.GLASS_BOTTLE) > 0
                && state.waterBottles() < WATER_TARGET;
        if (BrewPlanner.next(state) == null && !canFill) {
            return 0;
        }
        // Alchymie je disciplína chytrých a trpělivých; chybějící odolnost
        // ohni přitlačí (bez ní se hlubiny Netheru nekopou).
        double intelligence = bot.personality().trait(Trait.INTELLIGENCE);
        double patience = bot.personality().trait(Trait.PATIENCE);
        return 5 + intelligence * 8 + patience * 3 + (state.fireResistance() ? 0 : 3);
    }

    @Override
    public void start(Bot bot) {
        phase = Phase.PLAN;
        batch = null;
        standPos = null;
        placement = null;
        loadFuture = null;
        collectFuture = null;
        batches = 0;
        goTicks = 0;
        fillTicks = 0;
        waitTicks = 0;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        if (ctx.worldView() == null) {
            return;
        }
        switch (phase) {
            case PLAN -> tickPlan(ctx);
            case WATER -> tickWater(ctx);
            case STAND_FIND -> tickStandFind(ctx);
            case GO_STAND -> tickGoStand(ctx);
            case LOAD -> tickLoad(ctx);
            case WAIT -> tickWait(ctx);
            case DONE -> {
                // finished() ukončí
            }
        }
    }

    /** Rozhodnutí, co dál: naplnit lahve → uvařit vsázku → hotovo. */
    private void tickPlan(BotContext ctx) {
        var snapshot = ctx.serverView().latest();
        if (snapshot == null) {
            return;
        }
        BrewPlanner.State state = plannerState(snapshot);
        if (state.anythingMissing() && state.wart() > 0
                && state.waterBottles() < WATER_TARGET
                && InventoryHelper.countItem(snapshot, Material.GLASS_BOTTLE) > 0) {
            fillTicks = 0;
            goTicks = 0;
            phase = Phase.WATER;
            return;
        }
        batch = BrewPlanner.next(state);
        if (batch == null) {
            ctx.actions().closeContainer();
            if (batches > 0 && ctx.rng().chance(0.7)) {
                ctx.chat().sayFrom(PhraseCategory.BREW_DONE, null);
            }
            finish(ctx, ctx.rng().rangeInt(4800, 9600));
            return;
        }
        phase = standPos == null ? Phase.STAND_FIND : Phase.GO_STAND;
    }

    /** Plnění lahví u vody (use s pohledem na hladinu, jako hráč). */
    private void tickWater(BotContext ctx) {
        var snapshot = ctx.serverView().latest();
        if (snapshot == null) {
            return;
        }
        if (ItemVariants.countPotions(snapshot, ItemVariants.WATER) >= WATER_TARGET
                || InventoryHelper.countItem(snapshot, Material.GLASS_BOTTLE) == 0) {
            phase = Phase.PLAN;
            return;
        }
        BlockPos water = Boats.nearestWater(ctx.worldView(),
                ctx.position().toBlockPos(), 6);
        if (water == null) {
            // Voda tu není – zkusit jindy jinde (bot se pohybuje světem).
            finish(ctx, 1800);
            return;
        }
        if (water.center().distanceSquared(ctx.position()) > 3.2 * 3.2) {
            ctx.navigator().navigateTo(ctx.position(), PathGoal.near(water, 2));
            if (++goTicks > 200) {
                finish(ctx, 1800);
            }
            return;
        }
        ctx.navigator().stop();
        if (fillTicks == 0) {
            if (!ctx.inventory().equipItem(snapshot, Material.GLASS_BOTTLE)) {
                fillTicks = 4; // láhev se přitahuje do hotbaru
                return;
            }
            ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0),
                    water.center().add(0, 0.9, 0));
            fillTicks = 1;
        } else if (++fillTicks >= 8) {
            // Pohled ustálený na hladině → nabrat (server láhev promění).
            ctx.actions().useItem(ctx.humanizer().yaw(), ctx.humanizer().pitch());
            fillTicks = 0;
        }
    }

    /** Najít varný stojan v okolí, nebo si ho položit z inventáře. */
    private void tickStandFind(BotContext ctx) {
        if (placement != null) {
            if (placement.tick(ctx)) {
                BlockPos found = scanStand(ctx);
                if (found != null) {
                    placement = null;
                    standPos = found;
                    goTicks = 0;
                    phase = Phase.GO_STAND;
                }
                return;
            }
            placement = null; // pokládka to vzdala
            finish(ctx, 2400);
            return;
        }
        BlockPos found = scanStand(ctx);
        if (found != null) {
            standPos = found;
            goTicks = 0;
            phase = Phase.GO_STAND;
            return;
        }
        var snapshot = ctx.serverView().latest();
        if (snapshot != null && snapshot.hasItem(m -> m == Material.BREWING_STAND)) {
            placement = new StationPlacement(Material.BREWING_STAND);
            return;
        }
        finish(ctx, 2400); // stojan vyrobí CraftPlanner (blaze rod + kámen)
    }

    /** Dojít ke stojanu a otevřít ho (reálný interact paket). */
    private void tickGoStand(BotContext ctx) {
        if (ctx.worldView().materialAt(standPos) != Material.BREWING_STAND) {
            standPos = null;
            phase = Phase.STAND_FIND;
            return;
        }
        if (standPos.center().distanceSquared(ctx.position()) > 3.2 * 3.2) {
            ctx.navigator().navigateTo(ctx.position(), PathGoal.near(standPos, 2));
            if (++goTicks > 300) {
                finish(ctx, 2400);
            }
            return;
        }
        ctx.navigator().stop();
        ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0), standPos.center());
        ctx.actions().useItemOn(standPos, Direction.UP);
        loadFuture = station.load(ctx, ctx.worldView().worldName(), standPos,
                batch.ingredient(), batch.base());
        phase = Phase.LOAD;
    }

    /** Čekání na naložení stojanu. */
    private void tickLoad(BotContext ctx) {
        if (loadFuture == null || !loadFuture.isDone()) {
            return;
        }
        BrewingStation.LoadReport report = loadFuture.getNow(BrewingStation.LoadReport.EMPTY);
        loadFuture = null;
        if (report.bottles() == 0 || !report.ingredientLoaded()) {
            // Nebylo co naložit (lahve/přísada nesedí) – nevisí tu nic víc.
            ctx.actions().closeContainer();
            finish(ctx, 2400);
            return;
        }
        waitTicks = 0;
        collectFuture = null;
        phase = Phase.WAIT;
    }

    /** Čekání na dovaření (~20 s) s periodickým pokusem o výběr. */
    private void tickWait(BotContext ctx) {
        waitTicks++;
        if (collectFuture != null) {
            if (!collectFuture.isDone()) {
                return;
            }
            int taken = collectFuture.getNow(0);
            collectFuture = null;
            if (taken > 0) {
                batches++;
                batch = null;
                phase = Phase.PLAN; // další vsázka (nebo konec seance)
            } else if (waitTicks > BREW_WAIT_BUDGET) {
                finish(ctx, 4800); // force výběr nic nedal – vzdát seanci
                ctx.actions().closeContainer();
            }
            return;
        }
        // Výběr se zkouší po vteřinách; po rozpočtu naposled s force
        // (nevalidní kombinace se vrací do batohu, nic nepropadne).
        if (waitTicks % 20 == 0) {
            collectFuture = station.collect(ctx, ctx.worldView().worldName(), standPos,
                    waitTicks > BREW_WAIT_BUDGET);
        }
    }

    /** Sken varného stojanu v okolí (r=12). */
    private BlockPos scanStand(BotContext ctx) {
        WorldView world = ctx.worldView();
        BlockPos feet = ctx.position().toBlockPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -12; dx <= 12; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -12; dz <= 12; dz++) {
                    BlockPos pos = feet.offset(dx, dy, dz);
                    if (world.materialAt(pos) == Material.BREWING_STAND) {
                        double dist = feet.distanceSquared(pos);
                        if (dist < bestDist) {
                            bestDist = dist;
                            best = pos;
                        }
                    }
                }
            }
        }
        return best;
    }

    /** Souhrn lektvarového inventáře pro plánovač (varianty ze snapshotu). */
    private static BrewPlanner.State plannerState(ServerSideView.Snapshot s) {
        boolean fireRes = ItemVariants.hasPotion(s, ItemVariants.FIRE_RESISTANCE)
                || ItemVariants.findSplashSlot(s, ItemVariants.FIRE_RESISTANCE) >= 0;
        boolean healing = ItemVariants.hasPotion(s, ItemVariants.HEALING)
                || ItemVariants.findSplashSlot(s, ItemVariants.HEALING) >= 0;
        boolean strength = ItemVariants.hasPotion(s, ItemVariants.STRENGTH);
        boolean offensive = ItemVariants.findSplashSlot(s, ItemVariants.HARMING) >= 0
                || ItemVariants.findSplashSlot(s, ItemVariants.POISON) >= 0;
        return new BrewPlanner.State(
                ItemVariants.countPotions(s, ItemVariants.WATER),
                ItemVariants.countPotions(s, ItemVariants.AWKWARD),
                ItemVariants.countPotions(s, ItemVariants.POISON),
                fireRes, healing, strength, offensive,
                InventoryHelper.countItem(s, Material.NETHER_WART),
                InventoryHelper.countItem(s, Material.MAGMA_CREAM),
                InventoryHelper.countItem(s, Material.GLISTERING_MELON_SLICE),
                InventoryHelper.countItem(s, Material.SPIDER_EYE),
                InventoryHelper.countItem(s, Material.GUNPOWDER),
                InventoryHelper.countItem(s, Material.BLAZE_POWDER));
    }

    private void finish(BotContext ctx, int cooldown) {
        cooldownTicks = cooldown;
        phase = Phase.DONE;
    }

    @Override
    public void stop(Bot bot) {
        BotContext ctx = ctx(bot);
        ctx.actions().closeContainer();
        placement = null;
        super.stop(bot);
    }

    @Override
    public boolean finished(Bot bot) {
        return phase == Phase.DONE;
    }

    @Override
    public String explain(Bot bot) {
        return batch != null
                ? "vařím lektvar: " + batch.id()
                : "chystám se k vaření lektvarů";
    }
}
