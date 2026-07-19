package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.tasks.MineBlockTask;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.WorldView;
import org.bukkit.Material;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;

import java.util.Map;
import dev.botalive.core.pathfinding.PathGoal;

/**
 * Farmaření – sklizeň zralých plodin a přesazení.
 *
 * <p>Bot hledá v okolí zralé plodiny (kontrola {@link Ageable} přes block data
 * z chunk snapshotu), sklidí je (plodiny se lámou okamžitě), a pokud má
 * v hotbaru osivo, znovu je zasadí (UseItemOn na farmland – stejný paket jako
 * hráč s pravým klikem). Nalezené pole si pamatuje jako
 * {@link MemoryKind#FARM} a vrací se k němu.</p>
 */
public final class FarmGoal extends AbstractGoal {

    /** Plodina → osivo pro přesazení. */
    private static final Map<Material, Material> CROP_SEEDS = Map.of(
            Material.WHEAT, Material.WHEAT_SEEDS,
            Material.CARROTS, Material.CARROT,
            Material.POTATOES, Material.POTATO,
            Material.BEETROOTS, Material.BEETROOT_SEEDS
    );

    private enum Phase { FIND, GO, HARVEST, REPLANT, DONE }

    private Phase phase = Phase.FIND;
    private BlockPos crop;
    private Material cropType;
    private MineBlockTask harvestTask;
    private int replantTicks;
    private int cooldownTicks;
    private int harvested;

    /** Vytvoří cíl. */
    public FarmGoal() {
        super("farm");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        // Pole patří do overworldu (v Netheru plodiny nerostou).
        if (outsideOverworld(ctx)) {
            return 0;
        }
        // Chuť farmařit: hlad zvyšuje, trpělivost a ochota pomoci podporují.
        double patience = bot.personality().trait(Trait.PATIENCE);
        double hungerPressure = Math.max(0, 16 - ctx.clientState().food());
        // Známá farma poblíž → vyšší motivace (ví, kam jít).
        boolean knownFarm = ctx.worldView() != null && bot.memory()
                .recallNearest(MemoryKind.FARM, ctx.worldView().worldName(),
                        (int) ctx.position().x(), (int) ctx.position().y(), (int) ctx.position().z())
                .map(r -> r.distanceSquared((int) ctx.position().x(),
                        (int) ctx.position().y(), (int) ctx.position().z()) < 64 * 64)
                .orElse(false);
        return 4 + patience * 8 + hungerPressure * 1.2 + (knownFarm ? 6 : 0);
    }

    @Override
    public void start(Bot bot) {
        phase = Phase.FIND;
        crop = null;
        harvestTask = null;
        harvested = 0;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        switch (phase) {
            case FIND -> {
                crop = findMatureCrop(ctx);
                if (crop == null) {
                    cooldownTicks = 900; // v okolí nic zralého
                    phase = Phase.DONE;
                    return;
                }
                cropType = ctx.worldView().materialAt(crop);
                phase = Phase.GO;
            }
            case GO -> {
                double distSq = crop.center().distanceSquared(ctx.position());
                if (distSq > 3.5 * 3.5) {
                    ctx.navigator().navigateTo(ctx.position(), PathGoal.near(crop, 2));
                    if (!ctx.navigator().navigating()) {
                        phase = Phase.FIND; // nedosažitelné – hledat jinde
                    }
                    return;
                }
                ctx.navigator().stop();
                harvestTask = new MineBlockTask(crop);
                phase = Phase.HARVEST;
            }
            case HARVEST -> {
                if (harvestTask.tick(ctx)) {
                    harvestTask = null;
                    harvested++;
                    ctx.stats().addMined();
                    rememberFarm(ctx, bot);
                    // Přesazení, pokud má bot osivo v hotbaru.
                    Material seed = CROP_SEEDS.get(cropType);
                    var snapshot = ctx.serverView().latest();
                    if (seed != null && snapshot != null
                            && snapshot.findHotbarSlot(m -> m == seed) >= 0) {
                        ctx.actions().selectHotbar(snapshot.findHotbarSlot(m -> m == seed));
                        replantTicks = ctx.rng().rangeInt(4, 10);
                        phase = Phase.REPLANT;
                    } else {
                        phase = Phase.FIND; // další plodina
                    }
                }
            }
            case REPLANT -> {
                if (--replantTicks <= 0) {
                    // Osivo se sází pravým klikem na farmland pod plodinou.
                    ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0), crop.center());
                    ctx.actions().useItemOn(crop.down(), Direction.UP);
                    ctx.stats().addPlaced();
                    phase = Phase.FIND;
                }
            }
            case DONE -> {
                // finished() ukončí
            }
        }
    }

    @Override
    public void stop(Bot bot) {
        if (harvestTask != null) {
            harvestTask.cancel(ctx(bot));
            harvestTask = null;
        }
        super.stop(bot);
    }

    @Override
    public boolean finished(Bot bot) {
        if (harvested >= 12) {
            cooldownTicks = ctx(bot).rng().rangeInt(1200, 2400);
            return true;
        }
        return phase == Phase.DONE;
    }

    /** Uloží pole do paměti (jednou za sklizeň lokality). */
    private void rememberFarm(BotContext ctx, Bot bot) {
        if (ctx.worldView() != null && crop != null) {
            bot.memory().remember(MemoryKind.FARM, ctx.worldView().worldName(),
                    crop.x(), crop.y(), crop.z(), null,
                    Map.of("crop", cropType == null ? "?" : cropType.name()), 0.6);
        }
    }

    /** Sken okolí na zralou plodinu (Ageable s max věkem). */
    private BlockPos findMatureCrop(BotContext ctx) {
        WorldView world = ctx.worldView();
        if (world == null) {
            return null;
        }
        BlockPos center = ctx.position().toBlockPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        int radius = 12;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    Material material = world.materialAt(pos);
                    if (material == null || !CROP_SEEDS.containsKey(material)) {
                        continue;
                    }
                    BlockData data = world.blockDataAt(pos);
                    if (!(data instanceof Ageable ageable)
                            || ageable.getAge() < ageable.getMaximumAge()) {
                        continue;
                    }
                    double dist = pos.distanceSquared(center);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    @Override
    public String explain(dev.botalive.api.bot.Bot bot) {
        return "starám se o pole";
    }
}
