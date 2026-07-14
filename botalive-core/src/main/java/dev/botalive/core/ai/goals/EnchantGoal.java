package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.inventory.EnchantService;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.WorldView;
import org.bukkit.Material;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Očarovávání výbavy u enchantovacího stolu.
 *
 * <p>Bot s XP levely (získává je těžbou a bojem), lapisem a neočarovanou
 * výbavou najde enchantovací stůl (paměť {@link MemoryKind#ENCHANTING_TABLE},
 * jinak sken), dojde k němu, klikne, chvíli „studuje glyfy" a očaruje
 * ({@link EnchantService}). Stůl si zapamatuje.</p>
 */
public final class EnchantGoal extends AbstractGoal {

    private enum Phase { FIND, GO, OPEN, ENCHANT, CLOSE, DONE }

    private final EnchantService enchanting;

    private Phase phase = Phase.FIND;
    private BlockPos table;
    private int waitTicks;
    private CompletableFuture<EnchantService.EnchantReport> pending;
    private int cooldownTicks;

    /**
     * @param enchanting sdílená enchant služba
     */
    public EnchantGoal(EnchantService enchanting) {
        super("enchant");
        this.enchanting = enchanting;
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        var snapshot = ctx.serverView().latest();
        if (snapshot == null || snapshot.expLevel() < 1
                || !snapshot.hasItem(m -> m == Material.LAPIS_LAZULI)
                || !snapshot.hasItem(EnchantService::isEnchantable)) {
            return 0;
        }
        double intelligence = bot.personality().trait(Trait.INTELLIGENCE);
        return 4 + intelligence * 10 + Math.min(10, snapshot.expLevel() * 0.5);
    }

    @Override
    public void start(Bot bot) {
        phase = Phase.FIND;
        pending = null;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        switch (phase) {
            case FIND -> {
                table = findTable(ctx, bot);
                if (table == null) {
                    finish(ctx, 2400);
                    return;
                }
                phase = Phase.GO;
            }
            case GO -> {
                if (table.center().distanceSquared(ctx.position()) > 3.0 * 3.0) {
                    ctx.navigator().navigateTo(ctx.position(), table);
                    if (!ctx.navigator().navigating()) {
                        finish(ctx, 2400);
                    }
                    return;
                }
                ctx.navigator().stop();
                ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0),
                        table.center().add(0, 0.7, 0));
                waitTicks = ctx.rng().rangeInt(4, 10);
                phase = Phase.OPEN;
            }
            case OPEN -> {
                if (--waitTicks > 0) {
                    return;
                }
                ctx.actions().useItemOn(table, Direction.UP); // otevření stolu
                waitTicks = ctx.rng().rangeInt(30, 70);        // "studuje glyfy"
                phase = Phase.ENCHANT;
            }
            case ENCHANT -> {
                if (--waitTicks > 0) {
                    return;
                }
                if (pending == null) {
                    pending = enchanting.enchantBest(bot.id());
                    return;
                }
                if (!pending.isDone()) {
                    return;
                }
                EnchantService.EnchantReport report =
                        pending.getNow(EnchantService.EnchantReport.EMPTY);
                pending = null;
                if (report.enchanted() != null) {
                    rememberTable(ctx, bot);
                    if (ctx.rng().chance(0.3)) {
                        ctx.chat().say("očaroval jsem " + report.enchanted());
                    }
                }
                waitTicks = ctx.rng().rangeInt(5, 12);
                phase = Phase.CLOSE;
            }
            case CLOSE -> {
                if (--waitTicks <= 0) {
                    ctx.actions().closeContainer();
                    finish(ctx, ctx.rng().rangeInt(2400, 6000));
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

    private void finish(BotContext ctx, int cooldown) {
        cooldownTicks = cooldown;
        phase = Phase.DONE;
    }

    private void rememberTable(BotContext ctx, Bot bot) {
        if (ctx.worldView() != null && table != null) {
            bot.memory().remember(MemoryKind.ENCHANTING_TABLE, ctx.worldView().worldName(),
                    table.x(), table.y(), table.z(), null, Map.of(), 0.8);
        }
    }

    /** Stůl z paměti, jinak sken okolí. */
    private BlockPos findTable(BotContext ctx, Bot bot) {
        WorldView world = ctx.worldView();
        if (world == null) {
            return null;
        }
        BlockPos center = ctx.position().toBlockPos();
        var remembered = bot.memory().recallNearest(MemoryKind.ENCHANTING_TABLE,
                world.worldName(), center.x(), center.y(), center.z());
        if (remembered.isPresent()
                && remembered.get().distanceSquared(center.x(), center.y(), center.z()) < 64 * 64) {
            var r = remembered.get();
            Material material = world.materialAt(new BlockPos(r.x(), r.y(), r.z()));
            if (material == null || material == Material.ENCHANTING_TABLE) {
                return new BlockPos(r.x(), r.y(), r.z());
            }
        }
        int radius = 12;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (world.materialAt(pos) == Material.ENCHANTING_TABLE) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }
}
