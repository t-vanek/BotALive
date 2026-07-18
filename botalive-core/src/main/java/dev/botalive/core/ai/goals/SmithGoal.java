package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.station.SmithingStation;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.WorldView;
import org.bukkit.Material;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;

import java.util.concurrent.CompletableFuture;

/**
 * Povýšení diamantové výbavy na netherit u kovářského stolu – vrchol
 * crafting progrese.
 *
 * <p>Aktivuje se, jen když má bot všechno pohromadě: netheritový ingot
 * (4 úlomky + 4 zlato, viz {@code CraftPlanner}), kovářskou šablonu
 * (kořist z bastionových truhel) a diamantový kus k povýšení. Stůl si najde
 * (sken okolí), nebo vyrobený postaví vedle sebe jako jiné stanice.</p>
 */
public final class SmithGoal extends AbstractGoal {

    private enum Phase { FIND, GO, OPEN, WORK, CLOSE, DONE }

    private final SmithingStation smithing;

    private Phase phase = Phase.FIND;
    private BlockPos table;
    private StationPlacement placement;
    private int waitTicks;
    private int goTicks;
    private CompletableFuture<SmithingStation.UpgradeReport> pending;
    private int cooldownTicks;

    /**
     * @param smithing sdílená stanice kovářského stolu
     */
    public SmithGoal(SmithingStation smithing) {
        super("smith");
        this.smithing = smithing;
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        // Kove se doma – uprostřed výpravy v Netheru by vysoká utilita
        // přebila návrat domů (NetherGoal drží 30) a bot by stavěl kovářský
        // stůl vedle lávového jezera.
        if (outsideOverworld(ctx)) {
            return 0;
        }
        var snapshot = ctx.serverView().latest();
        if (snapshot == null
                || !snapshot.hasItem(m -> m == Material.NETHERITE_INGOT)
                || !snapshot.hasItem(m -> m == Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE)
                || !snapshot.hasItem(SmithGoal::upgradable)) {
            return 0;
        }
        // Vrchol progrese – když je všechno pohromadě, bot na to spěchá.
        return 20 + bot.personality().trait(Trait.GREED) * 8
                + bot.personality().trait(Trait.INTELLIGENCE) * 4;
    }

    @Override
    public void start(Bot bot) {
        phase = Phase.FIND;
        pending = null;
        placement = null;
        goTicks = 0;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        switch (phase) {
            case FIND -> {
                table = findTable(ctx);
                if (table != null) {
                    placement = null;
                    phase = Phase.GO;
                    return;
                }
                // Stůl nikde – bot si vyrobený postaví vedle sebe.
                if (placement == null) {
                    placement = new StationPlacement(Material.SMITHING_TABLE);
                }
                if (!placement.tick(ctx)) {
                    placement = null;
                    finish(1800);
                }
            }
            case GO -> {
                if (table.center().distanceSquared(ctx.position()) > 3.0 * 3.0) {
                    ctx.navigator().navigateTo(ctx.position(), table);
                    // navigating() je po navigateTo vždy true – nedojití
                    // hlídá časový rozpočet, ne stav navigátoru.
                    if (++goTicks > 600) {
                        finish(1800);
                    }
                    return;
                }
                ctx.navigator().stop();
                ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0),
                        table.center().add(0, 0.5, 0));
                waitTicks = ctx.rng().rangeInt(4, 10);
                phase = Phase.OPEN;
            }
            case OPEN -> {
                if (--waitTicks > 0) {
                    return;
                }
                ctx.actions().useItemOn(table, Direction.UP);
                waitTicks = ctx.rng().rangeInt(15, 30);
                phase = Phase.WORK;
            }
            case WORK -> {
                if (--waitTicks > 0) {
                    return;
                }
                if (pending == null) {
                    pending = smithing.upgrade(ctx, ctx.worldView().worldName(), table);
                    return;
                }
                if (!pending.isDone()) {
                    return;
                }
                var report = pending.getNow(SmithingStation.UpgradeReport.NONE);
                pending = null;
                if (report.succeeded() && ctx.rng().chance(0.8)) {
                    ctx.chat().say("NETHERIT! konečně " + report.result().name().toLowerCase());
                }
                waitTicks = ctx.rng().rangeInt(5, 12);
                phase = Phase.CLOSE;
            }
            case CLOSE -> {
                if (--waitTicks <= 0) {
                    ctx.actions().closeContainer();
                    finish(ctx.rng().rangeInt(200, 600));
                }
            }
            case DONE -> {
                // finished() ukončí
            }
        }
    }

    @Override
    public void stop(Bot bot) {
        ctx(bot).actions().closeContainer();
        super.stop(bot);
    }

    @Override
    public boolean finished(Bot bot) {
        return phase == Phase.DONE;
    }

    private void finish(int cooldown) {
        cooldownTicks = cooldown;
        phase = Phase.DONE;
    }

    /** @return {@code true} pro diamantový kus, který jde povýšit */
    private static boolean upgradable(Material material) {
        return SmithingStation.netheriteOf(material) != null;
    }

    /** Kovářský stůl v okolí (sken – stoly nejsou v paměti, stojí u domů). */
    private BlockPos findTable(BotContext ctx) {
        WorldView world = ctx.worldView();
        if (world == null) {
            return null;
        }
        BlockPos center = ctx.position().toBlockPos();
        int radius = 12;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (world.materialAt(pos) == Material.SMITHING_TABLE) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    @Override
    public String explain(Bot bot) {
        return "povyšuju výbavu na netherit u kovářského stolu";
    }
}
