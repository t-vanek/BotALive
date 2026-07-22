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
import dev.botalive.core.world.WorldView;

import org.bukkit.Material;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;

import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;

/**
 * Zásobování společné stavby materiálem – dělba práce u velkých staveb.
 *
 * <p>Velkou prestižní stavbu (radnice ~71 bloků, kostel) neutáhne jeden
 * stavitel sám. Ochotný člen sídla proto při rozestavěné stavbě
 * ({@link SettlementService#activeProject}) nanosí přebytek stavebních
 * bloků do skladu ({@link MaterialDepot} – zásobovací dvojtruhla), odkud
 * si je stavitel v PROVISION dobírá ({@code CommunalBuildGoal}). Sběrač
 * sbírá, stavitel staví – přesně jak roadmapa V2c chce.</p>
 *
 * <p>Řetězec je bonus, ne podmínka: bez skladu nebo bez rozestavěné stavby
 * cíl mlčí (utilita 0) a stavitel se zásobuje sám jako dosud. Truhla se
 * hledá stejně jako v {@code GranaryGoal} – dopočítaná z geometrie skladu,
 * přesun řeší {@link ChestStation#depositBuildingBlocks}.</p>
 */
public final class SupplyGoal extends AbstractGoal {

    private enum Phase { GO, OPEN, DEPOSIT, CLOSE, DONE }

    /** Nad tolik stavebních bloků v batohu je zbytek přebytek na staveniště. */
    private static final int SURPLUS_BLOCKS = 32;
    /** Kolik bloků si sběrač nechá (rezerva na pilířování a cestu). */
    private static final int KEEP_BLOCKS = 16;
    /** Za tímto poloměrem (v blocích) už sklad není „rozumně blízko". */
    private static final int DEPOT_REACH = 128;

    private final ChestStation containers;

    private Phase phase = Phase.DONE;
    private BlockPos chest;
    private int waitTicks;
    private CompletableFuture<Integer> deposit;
    private int cooldownTicks;

    /**
     * @param containers sdílená stanice truhel
     */
    public SupplyGoal(ChestStation containers) {
        super("supply");
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
        // Zásobuje se jen když se v sídle právě něco staví a je kam ukládat.
        boolean building = ctx.settlements().activeProject(bot.id()).isPresent();
        // Rozpis (BOM): když je stavba dozásobená (needs 0), sběrač ji nechá
        // být. Bez známého BOM (prázdno) padá na „nos, dokud máš přebytek".
        OptionalLong sid = ctx.settlements().settlementIdOf(bot.id());
        if (sid.isPresent()) {
            OptionalInt needs = ctx.settlements().contributionNeeds(sid.getAsLong());
            if (needs.isPresent() && needs.getAsInt() <= 0) {
                return 0;
            }
        }
        BlockPos depot = depotChest(ctx, bot);
        ServerSideView.Snapshot snapshot = ctx.serverView().latest();
        int blocks = snapshot == null ? 0 : InventoryHelper.countBuildingBlocks(snapshot);
        return utilityFor(blocks, building, depot != null,
                bot.personality().trait(Trait.HELPFULNESS));
    }

    /**
     * Váha zásobování (čistá funkce – testovatelná bez živého kontextu).
     * Nese jen s rozestavěnou stavbou, hotovým skladem a přebytkem bloků;
     * ochota pomoci a velikost přebytku ji zvedají. Drží se v pásmu
     * pomocných prací (pod přežitím, bojem i jídlem), aby zásobování
     * nepřebilo vlastní bezpečí bota.
     *
     * @param buildingBlocks stavební bloky v batohu
     * @param activeBuild    v sídle se právě staví
     * @param depot          sídlo má hotový sklad (kam ukládat)
     * @param helpfulness    rys ochoty pomoci [0,1]
     * @return váha cíle (0 = nezásobovat)
     */
    static double utilityFor(int buildingBlocks, boolean activeBuild, boolean depot,
                             double helpfulness) {
        if (!activeBuild || !depot || buildingBlocks <= SURPLUS_BLOCKS) {
            return 0;
        }
        return 4 + helpfulness * 8 + Math.min(buildingBlocks - SURPLUS_BLOCKS, 64) * 0.1;
    }

    @Override
    public void start(Bot bot) {
        BotContext ctx = ctx(bot);
        chest = depotChest(ctx, bot);
        deposit = null;
        phase = chest == null ? Phase.DONE : Phase.GO;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        switch (phase) {
            case GO -> tickGo(ctx);
            case OPEN -> tickOpen(ctx);
            case DEPOSIT -> tickDeposit(ctx, bot);
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
        phase = Phase.DEPOSIT;
    }

    private void tickDeposit(BotContext ctx, Bot bot) {
        if (--waitTicks > 0) {
            return;
        }
        if (deposit == null) {
            deposit = containers.depositBuildingBlocks(ctx, ctx.worldView().worldName(),
                    chest, KEEP_BLOCKS);
            return;
        }
        if (!deposit.isDone()) {
            return;
        }
        int moved = deposit.getNow(0);
        deposit = null;
        if (moved > 0) {
            // Připsat příspěvek do rozpisu (BOM) – ostatní sběrači pak vidí,
            // že materiálu ubývá k dostavbě.
            ctx.settlements().settlementIdOf(bot.id())
                    .ifPresent(id -> ctx.settlements().contribute(id, moved));
            if (ctx.rng().chance(0.5)) {
                ctx.chat().say("nosím materiál na stavbu, ať to máme rychleji hotové");
            }
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

    /**
     * Zásobovací truhla skladu, je-li rozumně blízko a opravdu ve světě
     * stojí (jinak {@code null}). Sklad může být přes půl světa – to by
     * sběrač netáhl; a když ho někdo zbořil, není kam ukládat.
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
            return null; // moc daleko – nemá cenu materiál táhnout přes svět
        }
        Material material = world.materialAt(depot);
        if (material != null && material != Material.CHEST
                && material != Material.TRAPPED_CHEST) {
            return null; // sklad zbořený / jiný svět – truhla tam není
        }
        return depot;
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
        return "nosím materiál na společnou stavbu";
    }
}
