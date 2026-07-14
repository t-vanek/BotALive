package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.inventory.InventoryHelper;
import dev.botalive.core.tasks.MineBlockTask;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.WorldView;
import org.bukkit.Material;

import java.util.Map;
import java.util.Set;

/**
 * Těžba – bot hledá odkryté rudy (případně dřevo) v okolí a těží je.
 *
 * <p>Postup: sken okolí přes {@link WorldView} → navigace k bloku → výběr
 * nejlepšího nástroje → {@link MineBlockTask} (kopání pakety s reálnou dobou
 * těžby). Vytěžené místo si bot pamatuje jako {@link MemoryKind#MINE} a za
 * cennou rudu dostává odměnu do peněženky.</p>
 */
public final class MineGoal extends AbstractGoal {

    /** Cílové bloky těžby a jejich hodnota pro ekonomiku. */
    private static final Map<Material, Double> ORE_VALUES = Map.ofEntries(
            Map.entry(Material.COAL_ORE, 2.0), Map.entry(Material.DEEPSLATE_COAL_ORE, 2.0),
            Map.entry(Material.COPPER_ORE, 2.5), Map.entry(Material.DEEPSLATE_COPPER_ORE, 2.5),
            Map.entry(Material.IRON_ORE, 5.0), Map.entry(Material.DEEPSLATE_IRON_ORE, 5.0),
            Map.entry(Material.GOLD_ORE, 8.0), Map.entry(Material.DEEPSLATE_GOLD_ORE, 8.0),
            Map.entry(Material.REDSTONE_ORE, 4.0), Map.entry(Material.DEEPSLATE_REDSTONE_ORE, 4.0),
            Map.entry(Material.LAPIS_ORE, 6.0), Map.entry(Material.DEEPSLATE_LAPIS_ORE, 6.0),
            Map.entry(Material.DIAMOND_ORE, 25.0), Map.entry(Material.DEEPSLATE_DIAMOND_ORE, 25.0),
            Map.entry(Material.EMERALD_ORE, 20.0), Map.entry(Material.DEEPSLATE_EMERALD_ORE, 20.0)
    );

    /** Dřevo – náhradní cíl těžby, když nejsou rudy. */
    private static final Set<Material> LOGS = Set.of(
            Material.OAK_LOG, Material.SPRUCE_LOG, Material.BIRCH_LOG, Material.JUNGLE_LOG,
            Material.ACACIA_LOG, Material.DARK_OAK_LOG, Material.MANGROVE_LOG,
            Material.CHERRY_LOG, Material.PALE_OAK_LOG
    );

    private BlockPos targetBlock;
    private Material targetMaterial;
    private MineBlockTask task;
    private int cooldownTicks;
    private int noTargetTicks;

    /** Vytvoří cíl. */
    public MineGoal() {
        super("mine");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        double greed = bot.personality().trait(Trait.GREED);
        double patience = bot.personality().trait(Trait.PATIENCE);
        // Bez skenu (drahý) – utility vyjadřuje chuť těžit; sken až v ticku.
        return 5 + greed * 14 + patience * 4;
    }

    @Override
    public void start(Bot bot) {
        targetBlock = null;
        task = null;
        noTargetTicks = 0;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);

        // Fáze 3: probíhající kopání.
        if (task != null) {
            if (task.tick(ctx)) {
                onMined(ctx, bot);
                task = null;
                targetBlock = null;
            }
            return;
        }

        // Fáze 1: najít cíl.
        if (targetBlock == null) {
            targetBlock = scanForTarget(ctx);
            if (targetBlock == null) {
                noTargetTicks += 1;
                if (noTargetTicks > 3) {
                    cooldownTicks = 600; // v okolí nic není – chvíli to nezkoušet
                }
                return;
            }
            targetMaterial = ctx.worldView().materialAt(targetBlock);
        }

        // Fáze 2: dojít k bloku a začít kopat.
        Vec3 pos = ctx.position();
        double distSq = targetBlock.center().add(0, 0.5, 0).distanceSquared(pos.add(0, 1.62, 0));
        if (distSq > 4.5 * 4.5) {
            ctx.navigator().navigateTo(pos, targetBlock);
            if (!ctx.navigator().navigating()) {
                targetBlock = null; // nedosažitelné
            }
            return;
        }
        ctx.navigator().stop();
        ctx.inventory().equipBestTool(ctx.serverView().latest(), targetMaterial);
        task = new MineBlockTask(targetBlock);
    }

    @Override
    public void stop(Bot bot) {
        if (task != null) {
            task.cancel(ctx(bot));
            task = null;
        }
        super.stop(bot);
    }

    @Override
    public boolean finished(Bot bot) {
        return cooldownTicks > 0;
    }

    /** Po vytěžení: paměť, statistiky, ekonomika, krátká pauza. */
    private void onMined(BotContext ctx, Bot bot) {
        ctx.stats().addMined();
        Double value = targetMaterial == null ? null : ORE_VALUES.get(targetMaterial);
        if (value != null && ctx.config().economy().enabled()) {
            bot.wallet().deposit(value, "těžba " + targetMaterial.name().toLowerCase());
        }
        if (ctx.worldView() != null && targetBlock != null) {
            bot.memory().remember(MemoryKind.MINE, ctx.worldView().worldName(),
                    targetBlock.x(), targetBlock.y(), targetBlock.z(), null,
                    targetMaterial == null ? Map.of() : Map.of("ore", targetMaterial.name()), 0.5);
        }
        // Trpěliví boti těží dál, ostatní si dají pauzu.
        double patience = bot.personality().trait(Trait.PATIENCE);
        if (!ctx.rng().chance(0.3 + patience * 0.6)) {
            cooldownTicks = ctx.rng().rangeInt(200, 800);
        }
    }

    /** Sken okolí: nejbližší odkrytá ruda, jinak strom. */
    private BlockPos scanForTarget(BotContext ctx) {
        WorldView world = ctx.worldView();
        if (world == null) {
            return null;
        }
        BlockPos center = ctx.position().toBlockPos();
        BlockPos bestOre = null;
        BlockPos bestLog = null;
        double bestOreDist = Double.MAX_VALUE;
        double bestLogDist = Double.MAX_VALUE;

        int radius = 10;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    Material material = world.materialAt(pos);
                    if (material == null) {
                        continue;
                    }
                    boolean isOre = ORE_VALUES.containsKey(material);
                    boolean isLog = LOGS.contains(material);
                    if (!isOre && !isLog) {
                        continue;
                    }
                    if (!isExposed(world, pos)) {
                        continue; // zavřené ve skále – bot nekope tunely naslepo
                    }
                    double dist = pos.distanceSquared(center);
                    if (isOre && dist < bestOreDist) {
                        bestOreDist = dist;
                        bestOre = pos;
                    } else if (isLog && dist < bestLogDist) {
                        bestLogDist = dist;
                        bestLog = pos;
                    }
                }
            }
        }
        return bestOre != null ? bestOre : bestLog;
    }

    /** Blok je odkrytý, když sousedí s průchozím prostorem. */
    private boolean isExposed(WorldView world, BlockPos pos) {
        return world.traitsAt(pos.up()).passable()
                || world.traitsAt(pos.down()).passable()
                || world.traitsAt(pos.offset(1, 0, 0)).passable()
                || world.traitsAt(pos.offset(-1, 0, 0)).passable()
                || world.traitsAt(pos.offset(0, 0, 1)).passable()
                || world.traitsAt(pos.offset(0, 0, -1)).passable();
    }
}
