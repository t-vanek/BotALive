package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.bot.ServerSideView;
import dev.botalive.core.inventory.InventoryHelper;
import dev.botalive.core.pathfinding.PathGoal;
import dev.botalive.core.settlement.SettlementService;
import dev.botalive.core.station.ChestStation;
import dev.botalive.core.util.BlockPos;

import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;

import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;

/**
 * Společná sýpka jako instituce – členové ji plní přebytky jídla a hladoví
 * si z ní vezmou <b>dřív, než jdou krást</b> (růstová roadmapa fáze C).
 *
 * <p>Bez tohoto cíle byla sýpka jen dekorace s prázdnými truhlami ({@code
 * granaryOf} se nikde nevolala). Teď:</p>
 * <ul>
 *   <li><b>Vklad</b>: člen s přebytkem jídla dojde do sýpky a přebytek uloží
 *       (nechá si zásobu na cestu) – sdílení jídla přestává být osobní laskavost
 *       a stává se institucí.</li>
 *   <li><b>Výběr</b>: hladový člen bez jídla si dojde do sýpky pro jídlo –
 *       s vyšší užitečností než {@code StealGoal}, takže sáhne do vlastní
 *       společné špajzky dřív než do cizí truhly.</li>
 * </ul>
 *
 * <p>Pozici truhly si cíl dopočítá z geometrie sýpky ({@code Blueprints.storageChest}
 * nad blueprintem sýpky její persistované velikosti – sedí i na širší městskou
 * sýpku, ne jen na legacy 4×4). Přesun řeší {@link ChestStation}.</p>
 */
public final class GranaryGoal extends AbstractGoal {

    private enum Phase { GO, OPEN, TRANSFER, CLOSE, DONE }

    /** Nad tolik kusů jídla v batohu je zbytek přebytek do sýpky. */
    private static final int SURPLUS_FOOD = 32;
    /** Kolik jídla si nechat při vkladu (zásoba na cestu). */
    private static final int KEEP_FOOD = 16;
    /** Pod tolik kusů jídla = „nemám co jíst" (spustí výběr). */
    private static final int LOW_FOOD = 4;
    /** Úroveň sytosti, pod kterou má smysl jít pro jídlo do sýpky. */
    private static final int HUNGRY_LEVEL = 14;

    private final ChestStation containers;

    private Phase phase = Phase.DONE;
    private boolean depositing;
    private BlockPos chest;
    private int waitTicks;
    private CompletableFuture<Integer> op;
    private int cooldownTicks;

    /**
     * @param containers sdílená stanice truhel
     */
    public GranaryGoal(ChestStation containers) {
        super("granary");
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
        if (granaryChest(ctx, bot).isEmpty()) {
            return 0; // není členem sídla s hotovou sýpkou
        }
        ServerSideView.Snapshot snapshot = ctx.serverView().latest();
        if (snapshot == null) {
            return 0;
        }
        int food = countFood(snapshot);
        int foodLevel = ctx.clientState().food();
        // Výběr: hladový a bez jídla → dojít pro jídlo do sýpky, dřív než krást.
        if (foodLevel <= HUNGRY_LEVEL && food <= LOW_FOOD) {
            return 34; // nad StealGoal (30) – vlastní špajzka má přednost před krádeží
        }
        // Vklad: přebytek jídla → doplnit společnou špajzku (ochota pomoci).
        if (food > SURPLUS_FOOD && foodLevel > HUNGRY_LEVEL) {
            return 5 + bot.personality().trait(Trait.HELPFULNESS) * 6;
        }
        return 0;
    }

    @Override
    public void start(Bot bot) {
        BotContext ctx = ctx(bot);
        chest = granaryChest(ctx, bot).orElse(null);
        ServerSideView.Snapshot snapshot = ctx.serverView().latest();
        int food = snapshot == null ? 0 : countFood(snapshot);
        // Hlad rozhoduje o směru: prázdný batoh + hlad = beru, jinak dávám.
        depositing = !(ctx.clientState().food() <= HUNGRY_LEVEL && food <= LOW_FOOD);
        op = null;
        phase = chest == null ? Phase.DONE : Phase.GO;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        switch (phase) {
            case GO -> tickGo(ctx);
            case OPEN -> tickOpen(ctx);
            case TRANSFER -> tickTransfer(ctx, bot);
            case CLOSE -> tickClose(ctx);
            case DONE -> {
            }
        }
    }

    private void tickGo(BotContext ctx) {
        if (chest.center().distanceSquared(ctx.position()) > 3.0 * 3.0) {
            ctx.navigator().navigateTo(ctx.position(), PathGoal.near(chest, 2));
            if (!ctx.navigator().navigating()) {
                finish(1800); // sýpka nedosažitelná
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
        phase = Phase.TRANSFER;
    }

    private void tickTransfer(BotContext ctx, Bot bot) {
        if (--waitTicks > 0) {
            return;
        }
        if (op == null) {
            String world = ctx.worldView().worldName();
            op = depositing
                    ? containers.depositFood(ctx, world, chest, KEEP_FOOD)
                    : containers.withdrawSupplies(ctx, world, chest, false);
            return;
        }
        if (!op.isDone()) {
            return;
        }
        int moved = op.getNow(0);
        op = null;
        if (moved > 0 && ctx.rng().chance(0.5)) {
            ctx.chat().say(depositing
                    ? "nosím přebytky do sýpky, ať máme společné zásoby"
                    : "beru si ze sýpky, mám hlad – vrátím, až budu mít");
        }
        waitTicks = ctx.rng().rangeInt(5, 12);
        phase = Phase.CLOSE;
    }

    private void tickClose(BotContext ctx) {
        if (--waitTicks > 0) {
            return;
        }
        ctx.actions().closeContainer();
        finish(ctx.rng().rangeInt(1200, 3000));
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

    /** Pozice truhly hotové sýpky bota (dvojtruhla je na {@code bedSpot}). */
    private Optional<BlockPos> granaryChest(BotContext ctx, Bot bot) {
        SettlementService settlements = ctx.settlements();
        OptionalLong id = settlements.settlementIdOf(bot.id());
        if (id.isEmpty()) {
            return Optional.empty();
        }
        return settlements.doneProject(id.getAsLong(), SettlementService.ProjectKind.GRANARY)
                .filter(p -> ctx.worldView() != null)
                .flatMap(p -> dev.botalive.core.build.plan.Blueprints.storageChest(
                        dev.botalive.core.build.plan.Blueprints.granary(p.size()),
                        p.origin(), p.facing()));
    }

    /** Součet kusů jídla v batohu (hotbar + hlavní inventář). */
    private static int countFood(ServerSideView.Snapshot snapshot) {
        int total = 0;
        for (int i = 0; i < snapshot.hotbar().length; i++) {
            if (snapshot.hotbar()[i] != null && InventoryHelper.isFood(snapshot.hotbar()[i])) {
                total += snapshot.hotbarCounts()[i];
            }
        }
        for (int i = 0; i < snapshot.mainInventory().length; i++) {
            if (snapshot.mainInventory()[i] != null
                    && InventoryHelper.isFood(snapshot.mainInventory()[i])) {
                total += snapshot.mainCounts()[i];
            }
        }
        return total;
    }

    @Override
    public String explain(Bot bot) {
        return depositing ? "nosím přebytky jídla do sýpky" : "beru si jídlo ze sýpky";
    }
}
