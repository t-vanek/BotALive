package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.ai.BotNeeds;
import dev.botalive.core.mining.DigPlanner;
import dev.botalive.core.tasks.MineBlockTask;
import dev.botalive.core.tasks.PlaceBlockTask;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.WorldView;
import org.bukkit.Material;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Těžba s účelem – bot ví, co potřebuje, a umí se k tomu prokopat.
 *
 * <p>Cíl těžby vybírají potřeby ({@link BotNeeds}): bez kamenných nástrojů
 * kope kámen, s kamenným krumpáčem shání železo (a uhlí na pochodně), se
 * železným jde po diamantech. Tier gating zaručuje, že bot nekope rudu
 * nástrojem, ze kterého by nic nepadlo.</p>
 *
 * <p>Kromě povrchové těžby (odkryté bloky) umí bot i <b>reálně kopat</b>:
 * k zasypané rudě si prorazí štolu, za hlubinnou rudou sestupuje schodištěm
 * ({@link DigPlanner} – nikdy kolmo dolů), po vytěžení rudy sleduje celou
 * žílu a ve štole si rozmisťuje pochodně. Před každým blokem kontroluje
 * okolí na lávu/vodu; při nebezpečí výkop vzdá. Hloubení lze vypnout
 * ({@code ai.terraforming: false}).</p>
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

    /** Cílová hloubka schodiště podle hledané rudy (nohy bota). */
    private static final Map<String, Integer> ORE_DEPTHS = Map.of(
            "IRON_ORE", 16, "COAL_ORE", 44, "DIAMOND_ORE", -54);

    /** Kolik bloků smí bot prokopat v jedné těžební seanci. */
    private static final int DIG_BUDGET = 48;

    private enum Mode { SURFACE, DIG_TO, STAIRCASE, TRAVEL }

    private BlockPos targetBlock;
    private Material targetMaterial;
    private MineBlockTask task;
    private PlaceBlockTask torchTask;
    private int cooldownTicks;
    private int noTargetTicks;

    private Mode mode = Mode.SURFACE;
    private String reason;
    private Material wishTarget;
    private final Deque<DigPlanner.Step> digSteps = new ArrayDeque<>();
    private final Deque<BlockPos> stepBlocks = new ArrayDeque<>();
    private BlockPos pendingWalk;
    private BlockPos pendingWalkAfterStep;
    private boolean digTaskInPlan;
    private int walkTicks;
    private int digBudget;
    private int torchCounter;
    private BlockPos travelTarget;
    private int travelTicks;

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
        // Těžbu v Netheru řídí výprava (NetherGoal) – vlastní cíle a rizika.
        if (outsideOverworld(ctx)) {
            return 0;
        }
        double greed = bot.personality().trait(Trait.GREED);
        double patience = bot.personality().trait(Trait.PATIENCE);
        double base = 5 + greed * 14 + patience * 4;
        // Potřeby zvyšují motivaci: bot ví, že mu něco chybí.
        BotNeeds needs = BotNeeds.assess(ctx.serverView().latest());
        if (!needs.miningWishlist().isEmpty()) {
            base += 8;
        }
        return base;
    }

    @Override
    public void start(Bot bot) {
        targetBlock = null;
        task = null;
        torchTask = null;
        noTargetTicks = 0;
        mode = Mode.SURFACE;
        reason = null;
        digSteps.clear();
        stepBlocks.clear();
        pendingWalk = null;
        pendingWalkAfterStep = null;
        digTaskInPlan = false;
        digBudget = DIG_BUDGET;
        torchCounter = 0;
        travelTarget = null;
        travelTicks = 0;
        maybeAnnounce(bot);
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);

        // Pokládání pochodně má přednost (krátké přerušení kopání).
        if (torchTask != null) {
            if (torchTask.tick(ctx)) {
                torchTask = null;
            }
            return;
        }

        // Probíhající kopání jednoho bloku.
        if (task != null) {
            if (task.tick(ctx)) {
                task = null;
                onBlockMined(ctx, bot);
            }
            return;
        }

        // Přesun na pozici dokončeného kroku výkopu.
        if (pendingWalk != null) {
            if (tickWalk(ctx)) {
                return;
            }
        }

        // Cesta ke vzpomínce na důl.
        if (travelTarget != null) {
            Vec3 pos = ctx.position();
            if (pos.toBlockPos().distanceSquared(travelTarget) <= 10 * 10) {
                travelTarget = null; // dorazil – další tick skenuje okolí dolu
                ctx.navigator().stop();
                mode = Mode.SURFACE;
            } else if (++travelTicks > 600) {
                travelTarget = null; // cesta se vleče – vzdát
                cooldownTicks = 400;
            } else {
                ctx.navigator().navigateTo(pos, travelTarget);
                if (!ctx.navigator().navigating()) {
                    travelTarget = null;
                    cooldownTicks = 400;
                }
                return;
            }
        }

        // Výkop podle plánu.
        if (targetBlock == null && (!stepBlocks.isEmpty() || !digSteps.isEmpty())) {
            tickDig(ctx, bot);
            return;
        }

        // Hledání nového cíle.
        if (targetBlock == null) {
            acquireTarget(ctx, bot);
            if (targetBlock == null && digSteps.isEmpty()) {
                noTargetTicks += 1;
                if (noTargetTicks > 3) {
                    cooldownTicks = 600; // v okolí nic není – chvíli to nezkoušet
                }
                return;
            }
            if (targetBlock == null) {
                return; // naplánován výkop – pokračuje se příští tick
            }
        }

        // Dojít k bloku a začít kopat.
        Vec3 pos = ctx.position();
        double distSq = targetBlock.center().add(0, 0.5, 0).distanceSquared(pos.add(0, 1.62, 0));
        if (distSq > 4.5 * 4.5) {
            ctx.navigator().navigateTo(pos, targetBlock);
            if (!ctx.navigator().navigating()) {
                targetBlock = null; // nedosažitelné pěšky
            }
            return;
        }
        ctx.navigator().stop();
        ctx.inventory().equipBestTool(ctx.serverView().latest(), targetMaterial);
        task = new MineBlockTask(targetBlock);
    }

    @Override
    public void stop(Bot bot) {
        BotContext ctx = ctx(bot);
        if (task != null) {
            task.cancel(ctx);
            task = null;
        }
        if (torchTask != null) {
            torchTask.cancel(ctx);
            torchTask = null;
        }
        digSteps.clear();
        stepBlocks.clear();
        pendingWalk = null;
        super.stop(bot);
    }

    @Override
    public boolean finished(Bot bot) {
        return cooldownTicks > 0;
    }

    @Override
    public String explain(Bot bot) {
        if (targetMaterial != null && (task != null || targetBlock != null)) {
            String label = BotNeeds.blockLabel(targetMaterial);
            return reason != null
                    ? "těžím " + label + " – " + reason
                    : "těžím " + label;
        }
        if (mode == Mode.STAIRCASE && (!digSteps.isEmpty() || !stepBlocks.isEmpty())) {
            return "razím schodiště do hloubky, hledám "
                    + (wishTarget != null ? BotNeeds.blockLabel(wishTarget) : "rudu");
        }
        if (mode == Mode.DIG_TO && (!digSteps.isEmpty() || !stepBlocks.isEmpty())) {
            return "prokopávám se k " + (wishTarget != null
                    ? BotNeeds.blockLabel(wishTarget) : "rudě")
                    + (reason != null ? " – " + reason : "");
        }
        if (mode == Mode.TRAVEL && travelTarget != null) {
            return "vracím se do svého dolu";
        }
        return "rozhlížím se, co by se dalo vytěžit";
    }

    // ==================================================================
    // Výběr cíle
    // ==================================================================

    /** Najde další cíl: wishlist → hodnotné rudy → dřevo → výkop/schodiště. */
    private void acquireTarget(BotContext ctx, Bot bot) {
        WorldView world = ctx.worldView();
        if (world == null) {
            return;
        }
        BotNeeds needs = BotNeeds.assess(ctx.serverView().latest());
        List<Material> wishlist = needs.miningWishlist();
        boolean preferLogs = dev.botalive.core.role.RoleProfiles.prefersLogs(bot.role())
                || (!needs.hasWood() && needs.pickaxeTier() == 0);

        // 1) Odkrytý blok z wishlistu (jen co bot skutečně vytěží).
        BlockPos found = scanExposed(ctx, m -> wishlist.contains(m) && needs.canHarvest(m));
        if (found != null) {
            setTarget(ctx, found, needs);
            return;
        }
        // 2) Dřevo, když ho bot potřebuje/preferuje.
        if (preferLogs) {
            found = scanExposed(ctx, LOGS::contains);
            if (found != null) {
                setTarget(ctx, found, needs);
                return;
            }
        }
        // 3) Jakákoli hodnotná ruda, kterou umí vytěžit.
        found = scanExposed(ctx, m -> ORE_VALUES.containsKey(m) && needs.canHarvest(m));
        if (found == null && !preferLogs) {
            found = scanExposed(ctx, LOGS::contains);
        }
        if (found != null) {
            setTarget(ctx, found, needs);
            return;
        }

        // 4) Reálné kopání: prokopat se k zasypané rudě z wishlistu.
        if (!ctx.config().ai().terraforming() || needs.pickaxeTier() < 1 || wishlist.isEmpty()) {
            return;
        }
        BlockPos buried = scanBuried(ctx, m -> wishlist.contains(m) && needs.canHarvest(m));
        if (buried != null) {
            List<DigPlanner.Step> plan = DigPlanner.plan(
                    ctx.position().toBlockPos(), buried.down(), 24);
            if (!plan.isEmpty()) {
                digSteps.addAll(plan);
                mode = Mode.DIG_TO;
                wishTarget = world.materialAt(buried);
                reason = wishTarget != null ? needs.miningReason(wishTarget) : null;
                return;
            }
        }
        // 5) Vzpomínka na důl: vrátit se tam, kde už bot rudu našel.
        if (travelTarget == null && ctx.rng().chance(0.5)) {
            var mine = bot.memory().recallNearest(MemoryKind.MINE,
                    world.worldName(), (int) ctx.position().x(),
                    (int) ctx.position().y(), (int) ctx.position().z());
            if (mine.isPresent()) {
                BlockPos pos = new BlockPos(mine.get().x(), mine.get().y(), mine.get().z());
                double distSq = pos.distanceSquared(ctx.position().toBlockPos());
                if (distSq > 14 * 14 && distSq < 80 * 80) {
                    travelTarget = pos;
                    mode = Mode.TRAVEL;
                    reason = "vracím se do svého dolu";
                    return;
                }
            }
        }
        // 6) Sestup schodištěm do hloubky hledané rudy (kopáčský instinkt).
        Material deepWish = wishlist.getFirst();
        Integer depth = ORE_DEPTHS.get(BotNeeds.oreFamily(deepWish));
        BlockPos feet = ctx.position().toBlockPos();
        if (depth != null && feet.y() > depth + 4 && ctx.rng().chance(0.5)) {
            digSteps.addAll(DigPlanner.staircase(feet, Math.max(depth, feet.y() - 24),
                    ctx.rng().rangeInt(0, 4), 24));
            mode = Mode.STAIRCASE;
            wishTarget = deepWish;
            reason = needs.miningReason(deepWish);
        }
    }

    private void setTarget(BotContext ctx, BlockPos pos, BotNeeds needs) {
        targetBlock = pos;
        targetMaterial = ctx.worldView().materialAt(pos);
        reason = targetMaterial != null ? needs.miningReason(targetMaterial) : null;
        mode = Mode.SURFACE;
    }

    // ==================================================================
    // Výkop
    // ==================================================================

    /** Vykonává plán výkopu: čistí bloky kroků, přesouvá se, klade pochodně. */
    private void tickDig(BotContext ctx, Bot bot) {
        WorldView world = ctx.worldView();
        if (world == null) {
            abortDig();
            return;
        }
        if (stepBlocks.isEmpty()) {
            DigPlanner.Step step = digSteps.poll();
            if (step == null) {
                // Plán hotov: rozhlédnout se po odkryté rudě (další tick).
                mode = Mode.SURFACE;
                return;
            }
            // Sonda podlahy: pod budoucími chodidly musí být do 3 bloků pevno.
            // Bez ní si bot prokopne strop velké kaverny a padá desítky bloků
            // (dva mrtví horníci v deepslate hloubkách během provozního testu).
            if (!DigPlanner.hasFloorBelow(world, step.feet(), 3)) {
                abortDig();
                cooldownTicks = 400;
                return;
            }
            stepBlocks.addAll(step.toBreak());
            pendingWalkAfterStep = step.feet();
        }
        BlockPos block = stepBlocks.peek();
        if (block == null) {
            return;
        }
        // Už průchozí (vzduch, jeskyně) – nic nekopat.
        if (world.traitsAt(block).passable()) {
            stepBlocks.poll();
            if (stepBlocks.isEmpty()) {
                finishStep(ctx, bot);
            }
            return;
        }
        // Bezpečnost: láva/voda v sousedství bloku → výkop vzdát.
        if (DigPlanner.unsafeToBreak(world, block)) {
            abortDig();
            cooldownTicks = 600;
            if (ctx.rng().chance(0.4)) {
                ctx.chat().say("uf, malem jsem se prokopal do lavy, radsi toho necham");
            }
            return;
        }
        if (digBudget-- <= 0) {
            abortDig();
            cooldownTicks = 400; // dost na jednu seanci
            return;
        }
        Material material = world.materialAt(block);
        ctx.inventory().equipBestTool(ctx.serverView().latest(),
                material != null ? material : Material.STONE);
        targetMaterial = material;
        // Blok zůstává ve frontě, dokud world view nepotvrdí vzduch (větev
        // "už průchozí" výše). Nepovedený výkop se tak zopakuje, místo aby se
        // tiše přeskočil a bot vkročil do zdi – přesně tak se dva horníci
        // během provozního testu udusili ve stěně.
        task = new MineBlockTask(block);
        digTaskInPlan = true;
    }

    /** Po vyčištění kroku: přesun na jeho pozici + případná pochodeň. */
    private void finishStep(BotContext ctx, Bot bot) {
        pendingWalk = pendingWalkAfterStep;
        walkTicks = 0;
        pendingWalkAfterStep = null;
        if (++torchCounter % 6 == 0) {
            BotNeeds needs = BotNeeds.assess(ctx.serverView().latest());
            if (needs.hasTorches()
                    && ctx.inventory().equipItem(ctx.serverView().latest(), Material.TORCH)) {
                torchTask = new PlaceBlockTask(ctx.position().toBlockPos());
            }
        }
    }

    /** @return {@code true} dokud přesun probíhá */
    private boolean tickWalk(BotContext ctx) {
        Vec3 pos = ctx.position();
        if (pos.toBlockPos().distanceSquared(pendingWalk) <= 2) {
            pendingWalk = null;
            ctx.navigator().stop();
            return false;
        }
        if (++walkTicks > 100) {
            pendingWalk = null; // zaseknutí – vzdát přesun, plán pokračuje
            abortDig();
            return false;
        }
        ctx.navigator().navigateTo(pos, pendingWalk);
        return true;
    }

    private void abortDig() {
        digSteps.clear();
        stepBlocks.clear();
        pendingWalkAfterStep = null;
        mode = Mode.SURFACE;
    }

    // ==================================================================
    // Po vytěžení
    // ==================================================================

    /** Statistiky/ekonomika/paměť + sledování žíly nebo pokračování výkopu. */
    private void onBlockMined(BotContext ctx, Bot bot) {
        ctx.stats().addMined();
        boolean valuable = targetMaterial != null && ORE_VALUES.containsKey(targetMaterial);
        if (valuable) {
            Double value = ORE_VALUES.get(targetMaterial);
            if (ctx.config().economy().enabled()) {
                bot.wallet().deposit(value, "těžba "
                        + targetMaterial.name().toLowerCase(java.util.Locale.ROOT));
            }
            if (ctx.worldView() != null && targetBlock != null) {
                bot.memory().remember(MemoryKind.MINE, ctx.worldView().worldName(),
                        targetBlock.x(), targetBlock.y(), targetBlock.z(), null,
                        Map.of("ore", targetMaterial.name()), 0.5);
            }
        }

        // Sledování žíly: sousední blok stejné rodiny → těžit hned dál.
        if (valuable && targetBlock != null && followVein(ctx)) {
            return;
        }

        BlockPos mined = targetBlock;
        targetBlock = null;

        // Výkop pokračuje (blok byl součástí plánu).
        if (digTaskInPlan) {
            digTaskInPlan = false;
            if (stepBlocks.isEmpty()) {
                finishStep(ctx, bot);
            }
            return;
        }

        // Povrchová těžba: trpěliví boti pokračují, ostatní si dají pauzu.
        double patience = bot.personality().trait(Trait.PATIENCE);
        if (mined != null && !ctx.rng().chance(0.3 + patience * 0.6)) {
            cooldownTicks = ctx.rng().rangeInt(200, 800);
        }
    }

    /** Zkusí najít pokračování žíly v okolí 3×3×3 vytěženého bloku. */
    private boolean followVein(BotContext ctx) {
        WorldView world = ctx.worldView();
        if (world == null || targetMaterial == null) {
            return false;
        }
        String family = BotNeeds.oreFamily(targetMaterial);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dy == 0 && dz == 0) {
                        continue;
                    }
                    BlockPos next = targetBlock.offset(dx, dy, dz);
                    Material material = world.materialAt(next);
                    if (material != null && ORE_VALUES.containsKey(material)
                            && BotNeeds.oreFamily(material).equals(family)
                            && !DigPlanner.unsafeToBreak(world, next)) {
                        targetBlock = next;
                        targetMaterial = material;
                        return true;
                    }
                }
            }
        }
        return false;
    }

    // ==================================================================
    // Skeny
    // ==================================================================

    /** Nejbližší odkrytý blok vyhovující filtru (r=10, ±4 na výšku). */
    private BlockPos scanExposed(BotContext ctx, java.util.function.Predicate<Material> filter) {
        return scan(ctx, filter, true, 10);
    }

    /** Nejbližší zasypaný blok vyhovující filtru (r=8) – cíl pro výkop. */
    private BlockPos scanBuried(BotContext ctx, java.util.function.Predicate<Material> filter) {
        return scan(ctx, filter, false, 8);
    }

    private BlockPos scan(BotContext ctx, java.util.function.Predicate<Material> filter,
                          boolean requireExposed, int radius) {
        WorldView world = ctx.worldView();
        if (world == null) {
            return null;
        }
        BlockPos center = ctx.position().toBlockPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    Material material = world.materialAt(pos);
                    if (material == null || !filter.test(material)) {
                        continue;
                    }
                    if (requireExposed && !DigPlanner.isExposed(world, pos)) {
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

    /** Občas nahlas oznámit záměr (podle společenskosti). */
    private void maybeAnnounce(Bot bot) {
        BotContext ctx = ctx(bot);
        double sociability = bot.personality().trait(Trait.SOCIABILITY);
        if (!ctx.rng().chance(0.12 * sociability)) {
            return;
        }
        BotNeeds needs = BotNeeds.assess(ctx.serverView().latest());
        List<Material> wishlist = needs.miningWishlist();
        if (!wishlist.isEmpty()) {
            ctx.chat().say("jdu kopat, " + needs.miningReason(wishlist.getFirst()));
        }
    }
}
