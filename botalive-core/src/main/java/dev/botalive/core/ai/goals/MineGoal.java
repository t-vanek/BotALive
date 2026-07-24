package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.ai.BotNeeds;
import dev.botalive.core.build.plan.BuildMaterials;
import dev.botalive.core.build.plan.BuildTier;
import dev.botalive.core.build.plan.HouseDesigner;
import dev.botalive.core.inventory.InventoryHelper;
import dev.botalive.core.inventory.Materials;
import dev.botalive.core.mining.DigPlanner;
import dev.botalive.core.settlement.SettlementService;
import dev.botalive.core.settlement.SettlementTier;
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
import dev.botalive.core.pathfinding.PathGoal;

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

    /** Cílová hloubka schodiště podle hledané rudy (nohy bota). */
    private static final Map<String, Integer> ORE_DEPTHS = Map.of(
            "IRON_ORE", 16, "COAL_ORE", 44, "DIAMOND_ORE", -54);

    /** Kolik bloků smí bot prokopat v jedné těžební seanci. */
    private static final int DIG_BUDGET = 48;

    private enum Mode { SURFACE, DIG_TO, STAIRCASE, TRAVEL }

    private BlockPos targetBlock;
    /** Kandidáti posledního skenu – o pořadí rozhodne dosažitelnost (anyNear). */
    private List<BlockPos> targetCandidates = List.of();
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
        // Plný batoh: vytěžený drop by neměl kam padnout a ztratil by se.
        // Rozdělané (blok/žíla/výkop) se dokope – jen se nezačíná nová seance –
        // ať zaskočí StashGoal a batoh se vybankuje do truhly.
        if (!miningInProgress()) {
            var snapshot = ctx.serverView().latest();
            if (snapshot != null && InventoryHelper.freeSlots(snapshot) <= 1) {
                return 0;
            }
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
        targetCandidates = List.of();
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
                ctx.navigator().navigateTo(pos, PathGoal.near(travelTarget, 8));
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

        // Dojít k rudě a začít kopat. Cílem je okruh KTERÉHOKOLI kandidáta
        // ze skenu – nejbližší vzdušnou čarou může být za lávou a o pořadí
        // rozhoduje skutečná dosažitelnost (A* dojde k nejlevnějšímu).
        Vec3 pos = ctx.position();
        BlockPos inReach = candidateInReach(pos);
        if (inReach == null) {
            ctx.navigator().navigateTo(pos, targetCandidates.size() > 1
                    ? PathGoal.anyNear(targetCandidates, 2)
                    : PathGoal.near(targetBlock, 2));
            if (!ctx.navigator().navigating()) {
                targetBlock = null; // žádný kandidát není dosažitelný pěšky
                targetCandidates = List.of();
            }
            return;
        }
        if (!inReach.equals(targetBlock)) {
            targetBlock = inReach; // došlo se k jinému kandidátovi než nejbližšímu
            targetMaterial = ctx.worldView().materialAt(inReach);
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
    public void pause(Bot bot) {
        // Přerušení reflexem (creeper, hlad): zrušit jen rozběhnutý úder a
        // navigaci, ale ZACHOVAT plán výkopu (schodiště/tunel, cílový blok,
        // rozpočet, cesta do dolu). Bez toho by start() při návratu vše smazal
        // a bot by razil od povrchu znova. K němu se vrací resume().
        BotContext ctx = ctx(bot);
        if (task != null) {
            task.cancel(ctx);
            task = null;
        }
        if (torchTask != null) {
            torchTask.cancel(ctx);
            torchTask = null;
        }
        ctx.navigator().stop();
    }

    @Override
    public void resume(Bot bot) {
        // Návazný start: rozdělaný výkop (digSteps/stepBlocks/targetBlock/mode/
        // digBudget/travelTarget) přežil pause – jen se nechá běžet dál, tick()
        // naváže na další krok. Nic se neresetuje ani neohlašuje (žádný spam).
    }

    @Override
    public boolean finished(Bot bot) {
        return cooldownTicks > 0;
    }

    /** Probíhá právě těžba (blok, žíla nebo výkop)? Rozdělané se má dokopat. */
    private boolean miningInProgress() {
        return task != null || targetBlock != null
                || !digSteps.isEmpty() || !stepBlocks.isEmpty();
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

    /**
     * Cílový stavební stupeň domu bota – z prosperity sídla a osobnosti
     * ({@link HouseDesigner#tierFor}). Řídí, jestli má smysl shánět sklo:
     * solidní+ dům zasklívá okna, srub (osada / samotář) ne.
     */
    private static BuildTier buildTarget(BotContext ctx, Bot bot) {
        SettlementTier tier = SettlementTier.OSADA;
        SettlementService settlements = ctx.settlements();
        if (settlements != null) {
            tier = settlements.settlementOf(bot.id())
                    .map(SettlementService.SettlementInfo::tier).orElse(SettlementTier.OSADA);
        }
        return HouseDesigner.tierFor(tier, bot.personality().trait(Trait.LAZINESS));
    }

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

        // 1) Odkryté bloky z wishlistu (jen co bot skutečně vytěží).
        List<BlockPos> found = scanExposedAll(ctx, m -> wishlist.contains(m) && needs.canHarvest(m));
        if (!found.isEmpty()) {
            setTarget(ctx, found, needs);
            return;
        }
        // 1b) Stavební suroviny (písek na sklo, hlína na cihly) – nižší priorita
        // než rudy: bere se, jen když poblíž není žádaná ruda, ať těžba nástrojů
        // má přednost. Cílový tier řídí, co má smysl shánět.
        var snapshot = ctx.serverView().latest();
        List<Material> build = BuildMaterials.gatherWishlist(buildTarget(ctx, bot),
                snapshot != null && snapshot.hasItem(Materials::isGlass),
                snapshot != null && snapshot.hasItem(Materials::isSand),
                snapshot != null && snapshot.hasItem(m -> m == Material.CLAY
                        || m == Material.CLAY_BALL),
                snapshot != null && snapshot.hasItem(m -> m == Material.BRICKS));
        if (!build.isEmpty()) {
            found = scanExposedAll(ctx, m -> build.contains(m) && needs.canHarvest(m));
            if (!found.isEmpty()) {
                setTarget(ctx, found, needs);
                return;
            }
        }
        // 2) Dřevo, když ho bot potřebuje/preferuje.
        if (preferLogs) {
            found = scanExposedAll(ctx, Materials::isLog);
            if (!found.isEmpty()) {
                setTarget(ctx, found, needs);
                return;
            }
        }
        // 3) Jakákoli hodnotná ruda, kterou umí vytěžit.
        found = scanExposedAll(ctx, m -> Materials.isValuableOre(m) && needs.canHarvest(m));
        if (found.isEmpty() && !preferLogs) {
            found = scanExposedAll(ctx, Materials::isLog);
        }
        if (!found.isEmpty()) {
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
            if (!plan.isEmpty() && planClear(ctx, plan)) {
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
        // Ústí dolu patří MIMO sídlo: díra vykopaná tam, kde bot zrovna stojí,
        // vede uprostřed návsi rovnou pod domy. Uvnitř ochranného okruhu se
        // proto schodiště nezakládá – bot dřív odejde za humna.
        Material deepWish = wishlist.getFirst();
        Integer depth = ORE_DEPTHS.get(BotNeeds.oreFamily(deepWish));
        BlockPos feet = ctx.position().toBlockPos();
        if (depth != null && feet.y() > depth + 4 && ctx.rng().chance(0.5)
                && mineEntranceAllowed(ctx, feet)) {
            List<DigPlanner.Step> stairs = DigPlanner.staircase(feet,
                    Math.max(depth, feet.y() - 24), ctx.rng().rangeInt(0, 4), 24);
            if (planClear(ctx, stairs)) {
                digSteps.addAll(stairs);
                mode = Mode.STAIRCASE;
                wishTarget = deepWish;
                reason = needs.miningReason(deepWish);
            }
        }
    }

    /**
     * Smí bot tenhle blok vytěžit? Zástavba sídla (dům, společná stavba) a
     * podloží pod ní je chráněná – jinak si boti poddolují vlastní vesnici.
     *
     * @param ctx kontext bota
     * @param pos zkoumaný blok
     * @return {@code true} když těžba tohoto bloku nic nepodkopává
     */
    private static boolean mineable(BotContext ctx, BlockPos pos) {
        WorldView world = ctx.worldView();
        if (world == null || ctx.settlements() == null) {
            return true;
        }
        return !ctx.settlements().isStructureProtected(world.worldName(), pos);
    }

    /** Neprochází žádný krok výkopu chráněnou zástavbou? */
    private static boolean planClear(BotContext ctx, List<DigPlanner.Step> plan) {
        for (DigPlanner.Step step : plan) {
            for (BlockPos block : step.toBreak()) {
                if (!mineable(ctx, block)) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Je tady dost daleko od sídel na to, aby se dal založit důl?
     * Vzdálenost bere {@code settlement.mine-distance}; 0 = kdekoliv.
     *
     * @param ctx  kontext bota
     * @param feet pozice zamýšleného ústí
     * @return {@code true} když se tu smí zahájit sestup
     */
    private static boolean mineEntranceAllowed(BotContext ctx, BlockPos feet) {
        WorldView world = ctx.worldView();
        int minDistance = ctx.config().settlement().mineDistance();
        if (world == null || ctx.settlements() == null || minDistance <= 0) {
            return true;
        }
        var nearest = ctx.settlements().distanceToNearestSettlement(world.worldName(), feet);
        return nearest.isEmpty() || nearest.getAsInt() >= minDistance;
    }

    private void setTarget(BotContext ctx, BlockPos pos, BotNeeds needs) {
        targetBlock = pos;
        targetCandidates = List.of(pos);
        targetMaterial = ctx.worldView().materialAt(pos);
        reason = targetMaterial != null ? needs.miningReason(targetMaterial) : null;
        mode = Mode.SURFACE;
    }

    /** Cíl s kandidáty: nejbližší je výchozí, o pořadí rozhodne dosažitelnost. */
    private void setTarget(BotContext ctx, List<BlockPos> candidates, BotNeeds needs) {
        setTarget(ctx, candidates.getFirst(), needs);
        targetCandidates = List.copyOf(candidates);
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
        // Bezpečnost: nad blokem gravitační sloupec (písek/štěrk) → nekopat
        // zespodu, sesypal by se dolů a horníka zasypal (katalogové pravidlo
        // „nekopat zespodu", které o gravitačních blocích drží MaterialGuide).
        if (DigPlanner.wouldCollapse(world, block)) {
            abortDig();
            cooldownTicks = 600;
            if (ctx.rng().chance(0.4)) {
                ctx.chat().say("nad hlavou pisek, to bych se zasypal, radsi jinudy");
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
        boolean valuable = targetMaterial != null && Materials.isValuableOre(targetMaterial);
        if (valuable) {
            Double value = Materials.oreValue(targetMaterial);
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
        targetCandidates = List.of();

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
                    if (material != null && Materials.isValuableOre(material)
                            && BotNeeds.oreFamily(material).equals(family)
                            && !DigPlanner.unsafeToBreak(world, next)) {
                        targetBlock = next;
                        targetCandidates = List.of(next);
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

    /** Strop kandidátů z jednoho skenu (víc nemá smysl – sken se opakuje). */
    private static final int CANDIDATE_LIMIT = 6;

    /** Až {@link #CANDIDATE_LIMIT} nejbližších odkrytých bloků dle filtru. */
    private List<BlockPos> scanExposedAll(BotContext ctx,
                                          java.util.function.Predicate<Material> filter) {
        WorldView world = ctx.worldView();
        if (world == null) {
            return List.of();
        }
        BlockPos center = ctx.position().toBlockPos();
        java.util.ArrayList<BlockPos> found = new java.util.ArrayList<>();
        for (int dx = -10; dx <= 10; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -10; dz <= 10; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    Material material = world.materialAt(pos);
                    if (material == null || !filter.test(material)) {
                        continue;
                    }
                    if (!DigPlanner.isExposed(world, pos) || !mineable(ctx, pos)) {
                        continue;
                    }
                    found.add(pos);
                }
            }
        }
        found.sort(java.util.Comparator.comparingDouble(p -> p.distanceSquared(center)));
        return found.size() > CANDIDATE_LIMIT
                ? List.copyOf(found.subList(0, CANDIDATE_LIMIT)) : List.copyOf(found);
    }

    /** Nejbližší kandidát na dokopnutí (oko ≤ 4,5 bloku od středu bloku). */
    private BlockPos candidateInReach(Vec3 pos) {
        Vec3 eye = pos.add(0, 1.62, 0);
        BlockPos best = null;
        double bestDist = 4.5 * 4.5;
        for (BlockPos candidate : targetCandidates) {
            double dist = candidate.center().add(0, 0.5, 0).distanceSquared(eye);
            if (dist <= bestDist) {
                bestDist = dist;
                best = candidate;
            }
        }
        return best;
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
                    if (!mineable(ctx, pos)) {
                        continue; // zástavba sídla – nepodhrabávat
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
