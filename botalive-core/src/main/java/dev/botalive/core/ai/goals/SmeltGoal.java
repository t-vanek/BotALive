package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.inventory.FurnaceService;
import dev.botalive.core.station.FurnaceStation;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.WorldView;
import org.bukkit.Material;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Tavení v peci – řemeslo kováře (vanilla furnace mechanika).
 *
 * <p>Bot s rudami/syrovým jídlem a palivem najde pec (paměť
 * {@link MemoryKind#FURNACE}, jinak sken), dojde k ní, klikne (animace),
 * vloží vsázku ({@link FurnaceService}) a odejde za jinou činností. Pec mezitím
 * taví skutečnou vanilla rychlostí (~10 s/kus); až je dávka hotová, utility
 * cíle stoupne a bot se pro výsledky vrátí.</p>
 */
public final class SmeltGoal extends AbstractGoal {

    /** Vanilla doba tavení jednoho kusu (ms). */
    private static final long SMELT_TIME_PER_ITEM_MS = 10_000;

    private enum Phase { FIND, GO, OPEN, WORK, CLOSE, DONE }

    private final FurnaceStation furnaces;

    private Phase phase = Phase.FIND;
    private BlockPos furnace;
    private boolean collecting;
    private int waitTicks;
    private CompletableFuture<?> pending;
    private int cooldownTicks;

    /** Kde a kdy budou hotové výsledky (0 = nic se nepeče). */
    private BlockPos pendingFurnace;
    private long collectReadyAtMs;

    /**
     * @param furnaces sdílená služba pecí
     */
    public SmeltGoal(FurnaceStation furnaces) {
        super("smelt");
        this.furnaces = furnaces;
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (collectReady()) {
            return 16 + bot.personality().trait(Trait.PATIENCE) * 6;
        }
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        var snapshot = ctx.serverView().latest();
        if (snapshot == null
                || !snapshot.hasItem(FurnaceService::isSmeltable)
                || !snapshot.hasItem(FurnaceService::isFuel)) {
            return 0;
        }
        double patience = bot.personality().trait(Trait.PATIENCE);
        return 5 + patience * 8;
    }

    @Override
    public void start(Bot bot) {
        phase = Phase.FIND;
        pending = null;
        collecting = collectReady();
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        switch (phase) {
            case FIND -> {
                furnace = collecting ? pendingFurnace : findFurnace(ctx, bot);
                if (furnace == null) {
                    if (collecting) {
                        pendingFurnace = null; // pec zmizela z evidence
                        collectReadyAtMs = 0;
                    }
                    finish(ctx, 1800);
                    return;
                }
                phase = Phase.GO;
            }
            case GO -> {
                if (furnace.center().distanceSquared(ctx.position()) > 3.0 * 3.0) {
                    ctx.navigator().navigateTo(ctx.position(), furnace);
                    if (!ctx.navigator().navigating()) {
                        finish(ctx, 1800);
                    }
                    return;
                }
                ctx.navigator().stop();
                ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0),
                        furnace.center().add(0, 0.5, 0));
                waitTicks = ctx.rng().rangeInt(4, 10);
                phase = Phase.OPEN;
            }
            case OPEN -> {
                if (--waitTicks > 0) {
                    return;
                }
                ctx.actions().useItemOn(furnace, Direction.UP); // otevření (animace/zvuk)
                waitTicks = ctx.rng().rangeInt(15, 30);
                phase = Phase.WORK;
            }
            case WORK -> {
                if (--waitTicks > 0) {
                    return;
                }
                if (pending == null) {
                    pending = collecting
                            ? furnaces.collect(ctx, ctx.worldView().worldName(), furnace)
                            : furnaces.insert(ctx, ctx.worldView().worldName(), furnace);
                    return;
                }
                if (!pending.isDone()) {
                    return;
                }
                onWorkDone(ctx, bot);
                waitTicks = ctx.rng().rangeInt(5, 12);
                phase = Phase.CLOSE;
            }
            case CLOSE -> {
                if (--waitTicks <= 0) {
                    ctx.actions().closeContainer();
                    finish(ctx, ctx.rng().rangeInt(400, 1200));
                }
            }
            case DONE -> {
                // finished() ukončí
            }
        }
    }

    /** Vyhodnocení vložení/výběru + naplánování návratu pro výsledky. */
    private void onWorkDone(BotContext ctx, Bot bot) {
        if (collecting) {
            Object result = pending.getNow(null);
            pendingFurnace = null;
            collectReadyAtMs = 0;
            if (result instanceof Integer taken && taken > 0) {
                ctx.stats().addMined(); // hrubá metrika produktivity kováře
            }
        } else {
            Object result = pending.getNow(null);
            if (result instanceof FurnaceStation.InsertReport report && report.inserted() > 0) {
                pendingFurnace = furnace;
                collectReadyAtMs = System.currentTimeMillis()
                        + report.inserted() * SMELT_TIME_PER_ITEM_MS + 5_000;
                rememberFurnace(ctx, bot);
            }
        }
        pending = null;
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

    private boolean collectReady() {
        return pendingFurnace != null && collectReadyAtMs > 0
                && System.currentTimeMillis() >= collectReadyAtMs;
    }

    private void finish(BotContext ctx, int cooldown) {
        cooldownTicks = cooldown;
        phase = Phase.DONE;
    }

    private void rememberFurnace(BotContext ctx, Bot bot) {
        if (ctx.worldView() != null && furnace != null) {
            bot.memory().remember(MemoryKind.FURNACE, ctx.worldView().worldName(),
                    furnace.x(), furnace.y(), furnace.z(), null, Map.of(), 0.7);
        }
    }

    /** Pec z paměti, jinak sken okolí. */
    private BlockPos findFurnace(BotContext ctx, Bot bot) {
        WorldView world = ctx.worldView();
        if (world == null) {
            return null;
        }
        BlockPos center = ctx.position().toBlockPos();
        var remembered = bot.memory().recallNearest(MemoryKind.FURNACE, world.worldName(),
                center.x(), center.y(), center.z());
        if (remembered.isPresent()
                && remembered.get().distanceSquared(center.x(), center.y(), center.z()) < 64 * 64) {
            var r = remembered.get();
            Material material = world.materialAt(new BlockPos(r.x(), r.y(), r.z()));
            if (material == null || isFurnaceBlock(material)) {
                return new BlockPos(r.x(), r.y(), r.z());
            }
        }
        int radius = 12;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    Material material = world.materialAt(pos);
                    if (material != null && isFurnaceBlock(material)) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    private static boolean isFurnaceBlock(Material material) {
        return material == Material.FURNACE || material == Material.BLAST_FURNACE
                || material == Material.SMOKER;
    }
}
