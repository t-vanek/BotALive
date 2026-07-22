package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.ai.BotNeeds;
import dev.botalive.core.bot.ServerSideView;
import dev.botalive.core.build.BarrierStyle;
import dev.botalive.core.build.Enclosure;
import dev.botalive.core.build.HouseBlueprint;
import dev.botalive.core.chat.PhraseCategory;
import dev.botalive.core.crafting.CraftingService;
import dev.botalive.core.settlement.SettlementService;
import dev.botalive.core.settlement.SettlementTier;
import dev.botalive.core.station.CraftingStation;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Cardinal;
import dev.botalive.core.world.WorldDimension;
import dev.botalive.core.world.WorldView;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;

/**
 * Hradby kolem sídla (vesnice/město) a jejich <b>oprava</b> – kamenný obvod po
 * vnějším obsazeném prstenci parcel, s branami (průchody) tam, kudy vyjíždějí
 * cesty.
 *
 * <p>Sourozenec {@link SettlementRoadsGoal}: hradby vlastní {@link
 * SettlementService} (jeden stavitel naráz – claim s TTL), stavbu vede sdílený
 * {@link BarrierWorker}. Staví se z <b>běžných stavebních bloků</b>; když chybí,
 * bot si je <b>dojde vytěžit</b> ({@link BarrierGather}).</p>
 *
 * <p><b>Oprava před soumrakem</b>: díra v hradbě = obrana pryč. {@link
 * Enclosure#assess} pozná pár chybějících sloupců; poškození zvedne prioritu a
 * <b>za soumraku/v noci</b> obejde denní bránu i 5min throttle, aby se hradba
 * spravila dřív, než bot udělá cokoli jiného ({@link BarrierRepair}).
 * Zapíná se {@code settlement.walls} (default vypnuto).</p>
 */
public final class SettlementWallGoal extends AbstractGoal {

    private enum Phase { CLAIM, PROVISION, WORK, DONE }

    /** Brány na všech čtyřech osách – tam, kudy vedou hlavní ulice z návsi. */
    private static final Set<Cardinal> GATES =
            Set.of(Cardinal.NORTH, Cardinal.SOUTH, Cardinal.EAST, Cardinal.WEST);
    /** Strop sloupců na jednu seanci (velká hradba se dostaví napříč seancemi). */
    private static final int MAX_COLUMNS = 24;
    /** Minimální odstup mezi seancemi stavby téže hradby (ms). */
    private static final long RECHECK_INTERVAL_MS = 5 * 60_000L;
    /** Okruh kolem návsi, ve kterém bot hradbu řeší (nechodí kvůli ní přes svět). */
    private static final int NEAR_HOME_RINGS = 8;
    /** Minimum stavebních bloků k zahájení seance (zbytek se dodělá později). */
    private static final int MIN_BLOCKS = 24;

    /** Kámen/hlína, jejichž vytěžení dá stavební blok (na hradbu). */
    private static final Predicate<Material> STONE = m ->
            m == Material.STONE || m == Material.COBBLESTONE || m == Material.DEEPSLATE
                    || m == Material.COBBLED_DEEPSLATE || m == Material.DIRT;

    private final CraftingStation crafting;
    private final RepairAssessor assessor = new RepairAssessor();

    private Phase phase = Phase.DONE;
    private long settlementId;
    private BlockPos center;
    private WallBounds bounds;
    private BarrierWorker worker;
    private BarrierGather gather;
    private int cooldownTicks;
    private UUID selfId;

    /**
     * @param crafting služba craftingu – branky do hradeb si bot dorobí z prken
     *                 (bonus; bez prken zůstanou otevřené oblouky)
     */
    public SettlementWallGoal(CraftingStation crafting) {
        super("settlement-walls");
        this.crafting = crafting;
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        var cfg = ctx.config().settlement();
        if (!cfg.enabled() || !cfg.walls()) {
            return 0;
        }
        WorldView world = ctx.worldView();
        if (world == null || ctx.dimension() != WorldDimension.OVERWORLD
                || ctx.settlements() == null) {
            return 0;
        }
        var info = ctx.settlements().settlementOf(bot.id());
        if (info.isEmpty()) {
            return 0;
        }
        SettlementService.SettlementInfo settlement = info.get();
        if (settlement.tier().ordinal() < SettlementTier.VESNICE.ordinal()
                || !settlement.world().equals(world.worldName())) {
            return 0;
        }
        WallBounds b = wallBounds(settlement.center(), plotOrigins(settlement), cfg.plotSpacing());
        if (b == null) {
            return 0; // není co obehnat (žádné parcely na prstenci)
        }
        boolean inProgress = worker != null || gather != null
                || phase == Phase.CLAIM || phase == Phase.PROVISION || phase == Phase.WORK;
        if (!inProgress) {
            // Levná brána nejdřív: kvůli hradbě se nechodí přes svět (a neskenuje zdálky).
            int reach = cfg.plotSpacing() * NEAR_HOME_RINGS;
            if (ctx.position().toBlockPos().distanceSquared(settlement.center())
                    > (double) reach * reach) {
                return 0;
            }
        }
        Enclosure.Assessment a = assessor.assess(ctx, b.min(), b.max(), settlement.center().y(), GATES);
        boolean damaged = BarrierRepair.isDamaged(a);
        long time = ctx.worldTime();
        boolean night = time >= 11500 && time <= 23000;
        if (!inProgress) {
            // Nová hradba jen ve dne; poškozenou (obrana pryč) i za soumraku/v noci.
            if (night && !damaged) {
                return 0;
            }
            if (a.total() > 0 && a.missing() == 0) {
                return 0; // hradba celá stojí
            }
            if (!damaged) {
                // Nová stavba: potřebuje bloky a drží 5min odstup (oprava obojí obejde).
                if (BotNeeds.assess(ctx.serverView().latest()).buildingBlocks() < MIN_BLOCKS) {
                    return 0;
                }
                if (!ctx.settlements().wallsDue(settlement.id(), RECHECK_INTERVAL_MS, bot.id())) {
                    return 0;
                }
            }
        }
        if (damaged) {
            return BarrierRepair.wallUrgency(a, night); // oprava – za soumraku nejnaléhavější
        }
        double helpfulness = bot.personality().trait(Trait.HELPFULNESS);
        return 5 + helpfulness * 5; // nová hradba – nízká priorita
    }

    @Override
    public void start(Bot bot) {
        phase = Phase.CLAIM;
        worker = null;
        gather = null;
        bounds = null;
        selfId = bot.id();
        settlementId = 0;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        switch (phase) {
            case CLAIM -> tickClaim(ctx, bot);
            case PROVISION -> tickProvision(ctx);
            case WORK -> tickWork(ctx, bot);
            case DONE -> {
            }
        }
    }

    private void tickClaim(BotContext ctx, Bot bot) {
        var info = ctx.settlements().settlementOf(bot.id());
        if (info.isEmpty()
                || info.get().tier().ordinal() < SettlementTier.VESNICE.ordinal()) {
            phase = Phase.DONE;
            return;
        }
        SettlementService.SettlementInfo settlement = info.get();
        settlementId = settlement.id();
        center = settlement.center();
        if (!ctx.settlements().claimWalls(settlementId, bot.id())) {
            cooldownTicks = 1200; // staví někdo jiný – ať to dodělá on
            phase = Phase.DONE;
            return;
        }
        bounds = wallBounds(center, plotOrigins(settlement), ctx.config().settlement().plotSpacing());
        if (bounds == null) {
            finish(ctx, false); // není co obehnat – odstup a konec
            return;
        }
        if (ctx.rng().chance(0.6)) {
            ctx.chat().sayFrom(PhraseCategory.SETTLEMENT_WALLS_START, settlement.name());
        }
        phase = Phase.PROVISION;
    }

    /** Zajistí stavební bloky; když chybí, dojde vytěžit kámen/hlínu v okolí. */
    private void tickProvision(BotContext ctx) {
        int blocks = BotNeeds.assess(ctx.serverView().latest()).buildingBlocks();
        if (blocks >= MIN_BLOCKS) {
            gather = null;
            phase = Phase.WORK;
            return;
        }
        if (gather == null) {
            gather = new BarrierGather(STONE);
        }
        if (gather.tick(ctx) == BarrierGather.State.EXHAUSTED) {
            gather = null;
            if (blocks >= 1) {
                phase = Phase.WORK; // postaví aspoň s tím, co má (zbytek příště)
            } else {
                giveUp(ctx, 2400); // v okolí není kámen ani hlína
            }
        }
    }

    private void tickWork(BotContext ctx, Bot bot) {
        if (worker == null) {
            List<Enclosure.Placement> cells = planWall(ctx.worldView(),
                    ctx.config().settlement().wallHeight());
            if (cells.isEmpty()) {
                finish(ctx, false); // hradba už drží (nebo není kde stavět)
                return;
            }
            Material gate = provisionGates(ctx, bot, cells);
            worker = new BarrierWorker(cells, center, null, gate); // sloupky z běžných bloků
        }
        if (worker.tick(ctx)) {
            ctx.gainExperience(dev.botalive.core.personality.PersonalityEvolution
                    .BotExperience.HOUSE_BUILT);
            finish(ctx, true);
        }
    }

    /**
     * Materiál branky (dřevo okolí, jinak dub) a best-effort dorobení branek
     * z prken pro <b>ještě nepostavené</b> průchody. Branka je bonus: bez prken
     * nebo ponku se nevyrobí a průchod zůstane otevřeným obloukem.
     *
     * @return materiál branky pro {@link BarrierWorker}
     */
    private Material provisionGates(BotContext ctx, Bot bot, List<Enclosure.Placement> cells) {
        ServerSideView.Snapshot snapshot = ctx.serverView().latest();
        Material planks = CraftingService.dominantPlanks(snapshot);
        BarrierStyle.Materials mats = BarrierStyle.FENCE.materials(planks); // planks==null → dub
        Material gate = mats.gate();
        if (planks == null || snapshot == null
                || !snapshot.hasItem(m -> m == Material.CRAFTING_TABLE)) {
            return gate; // bez prken/ponku branku nevyrobí – zůstanou otevřené oblouky
        }
        int openGates = 0;
        for (Enclosure.Placement cell : cells) {
            if (cell.kind() == Enclosure.Cell.GATE
                    && ctx.worldView().traitsAt(cell.pos()).noCollision()) {
                openGates++; // průchod, kam branka teprve přijde (ne už osazená)
            }
        }
        int toCraft = openGates - ctx.inventory().countItem(snapshot, gate);
        if (toCraft > 0 && CraftingService.canCraftFencing(snapshot, planks, 0, toCraft)) {
            crafting.craftFencing(bot.id(), planks, mats.post(), gate, 0, toCraft);
        }
        return gate;
    }

    /**
     * Naplánuje hradbu po obvodu {@link #bounds} do výšky {@code height}. Brány
     * dostanou <b>průchod</b> ({@link Enclosure#gateway}); ostatní plný sloupec.
     */
    private List<Enclosure.Placement> planWall(WorldView world, int height) {
        List<Enclosure.Placement> cells = new ArrayList<>();
        for (Enclosure.Post p : Enclosure.plan(world, bounds.min(), bounds.max(), center.y(),
                GATES, MAX_COLUMNS)) {
            cells.addAll(p.gate() ? Enclosure.gateway(p, height) : Enclosure.column(p, height));
        }
        return cells;
    }

    /** Origins obsazených parcel sídla (pro obvod hradby). */
    private static List<BlockPos> plotOrigins(SettlementService.SettlementInfo settlement) {
        List<BlockPos> origins = new ArrayList<>();
        for (SettlementService.MemberInfo member : settlement.members()) {
            if (member.plotOrigin() != null) {
                origins.add(member.plotOrigin());
            }
        }
        return origins;
    }

    /** Seance doběhla: nastaví sídlu odstup, uvolní hradby a ukončí cíl. */
    private void finish(BotContext ctx, boolean announce) {
        if (settlementId != 0) {
            ctx.settlements().wallsBuilt(settlementId);
        }
        if (announce && ctx.rng().chance(0.4)) {
            ctx.settlements().settlementOf(selfId).ifPresent(s ->
                    ctx.chat().sayFrom(PhraseCategory.SETTLEMENT_WALLS_DONE, s.name()));
        }
        worker = null;
        gather = null;
        cooldownTicks = 6000;
        phase = Phase.DONE;
    }

    private void giveUp(BotContext ctx, int cooldown) {
        // Nedokončeno – uvolni hradby dalšímu (bez throttlu přes wallsBuilt).
        if (settlementId != 0) {
            ctx.settlements().releaseWalls(settlementId, selfId);
        }
        worker = null;
        gather = null;
        cooldownTicks = cooldown;
        phase = Phase.DONE;
    }

    /** Obdélník hradby a strana branky (čistá geometrie, testovatelné). */
    record WallBounds(BlockPos min, BlockPos max) {
    }

    /**
     * Obvod hradby z obsazených parcel: čtverec kolem návsi po <b>vnějším
     * obsazeném prstenci</b> plus rezerva {@link HouseBlueprint#SIZE}.
     *
     * @param center      náves
     * @param plotOrigins originy obsazených parcel
     * @param spacing     rozestup parcel (bloky)
     * @return obdélník hradby, nebo {@code null} když není co obehnat
     */
    static WallBounds wallBounds(BlockPos center, List<BlockPos> plotOrigins, int spacing) {
        if (center == null || spacing <= 0) {
            return null;
        }
        int half = HouseBlueprint.SIZE / 2;
        int maxRing = 0;
        for (BlockPos origin : plotOrigins) {
            if (origin == null) {
                continue;
            }
            int dx = Math.round((float) (origin.x() + half - center.x()) / spacing);
            int dz = Math.round((float) (origin.z() + half - center.z()) / spacing);
            maxRing = Math.max(maxRing, Math.max(Math.abs(dx), Math.abs(dz)));
        }
        if (maxRing == 0) {
            return null; // jen náves (nebo prázdno) – hradbu kolem jednoho domu nestavíme
        }
        int ext = maxRing * spacing + HouseBlueprint.SIZE;
        return new WallBounds(
                new BlockPos(center.x() - ext, center.y(), center.z() - ext),
                new BlockPos(center.x() + ext, center.y(), center.z() + ext));
    }

    @Override
    public void stop(Bot bot) {
        BotContext ctx = ctx(bot);
        if (worker != null) {
            worker.cancel(ctx);
            worker = null;
        }
        if (gather != null) {
            gather.cancel(ctx);
            gather = null;
        }
        if (settlementId != 0) {
            ctx.settlements().releaseWalls(settlementId, selfId);
        }
        ctx.navigator().stop();
    }

    @Override
    public boolean finished(Bot bot) {
        return phase == Phase.DONE;
    }

    @Override
    public String explain(Bot bot) {
        return "stavím hradby kolem sídla";
    }
}
