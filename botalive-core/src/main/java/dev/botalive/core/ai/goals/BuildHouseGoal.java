package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.memory.MemoryRecord;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.ai.BotNeeds;
import dev.botalive.core.build.HouseBlueprint;
import dev.botalive.core.build.HouseFacing;
import dev.botalive.core.chat.PhraseCategory;
import dev.botalive.core.settlement.SettlementService;
import dev.botalive.core.settlement.SocialView;
import dev.botalive.core.tasks.BotTask;
import dev.botalive.core.tasks.MineBlockTask;
import dev.botalive.core.tasks.PlaceBlockTask;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.BlockTraits;
import dev.botalive.core.world.WorldView;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Stavba skutečného domku – bot si plánuje, upravuje terén a staví.
 *
 * <p>Kde se staví, řeší vesnice ({@link SettlementService}): člen staví na
 * přidělené parcele dveřmi k návsi, společenský bot se přidá k vesnici
 * kamarádů, samotář si najde rovné místo po svém, jen ne cizí vesnici pod
 * okny. Odštěpenec po roztržce nejdřív odejde dál. Nová vesnice vzniká až
 * s dokončeným domem zakladatele – žádné fantomové vesnice bez jediné stavby.</p>
 *
 * <p>Terén vzdálené parcely nejde posoudit z dálky (nenačtené chunky hlásí
 * {@link BlockTraits#UNKNOWN}) – bot proto parcelu nejdřív zabere, dojde na
 * ni a teprve s načteným okolím rozhodne: stavět / posunout výšku / vrátit
 * parcelu a zkusit vedlejší. Vlastní rozestavěné zdi se při návratu nikdy
 * nepočítají jako překážka a nebourají se.</p>
 *
 * <p>Postup stavby: připravit staveniště (vykopat překážky, zaplnit díry
 * v podlaze – jen s {@code ai.terraforming}), postavit zdi s dveřním otvorem
 * a střechu z jednoho místa uvnitř, vyjít dveřmi. Hotový dům se ukládá jako
 * {@link MemoryKind#HOME} typu {@code house}.</p>
 */
public final class BuildHouseGoal extends AbstractGoal {

    /** Kolik sloupců půdorysu smí chybět/přebývat, aby se to ještě srovnalo. */
    private static final int MAX_FILLS = 4;
    private static final int MAX_DIGS = 8;
    /** Kolik návrhů parcel zkusit za jednu stavební seanci. */
    private static final int PLOT_ATTEMPTS = 12;
    /** Cena staveniště: nepoužitelné (tekutina, moc úprav). */
    private static final int COST_INVALID = -1;
    /** Cena staveniště: nenačtené chunky – nelze posoudit, nikdy netombstonovat. */
    private static final int COST_UNKNOWN = -2;
    /** Po kolika marných seancích hledání parcely si člen staví po svém. */
    private static final int PLOT_SESSIONS_BEFORE_SOLO = 3;
    /** Pořadí zkoušených výškových posunů parcely (resume musí zkusit 0 první). */
    private static final int[] DY_ORDER = {0, -1, 1, -2, 2};

    private enum Phase { FIND_SITE, RELOCATE, GOTO_SITE, TERRAFORM, BUILD, FURNISH,
        DECORATE, DONE }

    /** Krok vybavování domu: co vzít do ruky a kam to položit. */
    private record FurnishStep(java.util.function.Predicate<org.bukkit.Material> item,
                               dev.botalive.core.util.BlockPos target) {
    }

    /** Krok zvelebování okolí: {@code path} = udusaná cestička, jinak pochodeň. */
    private record DecorStep(boolean path, BlockPos target) {
    }

    private Phase phase = Phase.FIND_SITE;
    private BlockPos origin;
    private HouseFacing facing = HouseFacing.NORTH;
    private final Deque<BotTask> terraform = new ArrayDeque<>();
    private final Deque<BlockPos> placements = new ArrayDeque<>();
    private final Deque<FurnishStep> furnish = new ArrayDeque<>();
    private final Deque<DecorStep> decor = new ArrayDeque<>();
    private int decorWaitTicks;
    private BotTask current;
    private int cooldownTicks;
    private int placedCount;
    /** Pojistka proti opakované stavbě, kdyby paměť HOME zmergovala metadata. */
    private boolean houseBuilt;
    /** „Došly bloky" hlásit jen jednou za seanci. */
    private boolean announcedOutOfBlocks;
    /** Plán bydlení na tuto seanci (vesnice / vlastní / samotář). */
    private SettlementService.HomePlan plan;
    /** Sociální snímek seance (join, zakládání) – staví se nejvýš jednou. */
    private SocialView sessionView;
    /** Jméno vesnice, kde se staví (pro intent vrstvu). */
    private String settlementName;
    /** Index zabrané parcely ({@code null} = staví se mimo parcelu). */
    private Integer claimedIndex;
    /** Po dostavění založit vesnici (dům zakladatele = náves). */
    private boolean pendingFound;
    /** Cíl odchodu dál od cizí vesnice (odštěpenec, samotář). */
    private BlockPos relocateTarget;
    private boolean relocated;
    /** Marné seance hledání parcely v řadě (přes seance) → solo fallback. */
    private int plotFailedSessions;

    /** Vytvoří cíl. */
    public BuildHouseGoal() {
        super("house");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        // Po přestěhování (roztržka, stěhování za kamarády) se staví znovu.
        SettlementService settlements = ctx.settlements();
        if (settlements != null && settlements.consumeRebuild(bot.id())) {
            houseBuilt = false;
            cooldownTicks = 0;
            plotFailedSessions = 0;
        }
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        // Dům se staví za světla.
        long time = ctx.worldTime();
        if (time >= 11500 && time <= 23000) {
            return 0;
        }
        // Už dům má → nestavět další.
        if (houseBuilt || hasHouse(bot, ctx)) {
            return 0;
        }
        // Bez dostatku bloků nemá cenu začínat.
        BotNeeds needs = BotNeeds.assess(ctx.serverView().latest());
        if (needs.buildingBlocks() < HouseBlueprint.blocksNeeded()) {
            return 0;
        }
        double intelligence = bot.personality().trait(Trait.INTELLIGENCE);
        double caution = bot.personality().trait(Trait.CAUTION);
        return 8 + intelligence * 8 + caution * 6;
    }

    @Override
    public void start(Bot bot) {
        phase = Phase.FIND_SITE;
        origin = null;
        facing = HouseFacing.NORTH;
        terraform.clear();
        placements.clear();
        furnish.clear();
        decor.clear();
        decorWaitTicks = 0;
        current = null;
        placedCount = 0;
        announcedOutOfBlocks = false;
        plan = null;
        sessionView = null;
        settlementName = null;
        claimedIndex = null;
        pendingFound = false;
        relocateTarget = null;
        relocated = false;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        WorldView world = ctx.worldView();
        if (world == null) {
            cooldownTicks = 600;
            phase = Phase.DONE;
            return;
        }
        switch (phase) {
            case FIND_SITE -> findSite(ctx, bot);
            case RELOCATE -> relocate(ctx);
            case GOTO_SITE -> gotoSite(ctx, bot);
            case TERRAFORM -> tickTasks(ctx, terraform, Phase.BUILD);
            case BUILD -> tickBuild(ctx, bot);
            case FURNISH -> tickFurnish(ctx, bot);
            case DECORATE -> tickDecor(ctx, bot);
            case DONE -> {
            }
        }
    }

    @Override
    public void stop(Bot bot) {
        if (current != null) {
            current.cancel(ctx(bot));
            current = null;
        }
        terraform.clear();
        placements.clear();
        super.stop(bot);
    }

    @Override
    public boolean finished(Bot bot) {
        return phase == Phase.DONE;
    }

    @Override
    public String explain(Bot bot) {
        String where = settlementName == null ? "" : " v " + settlementName;
        return switch (phase) {
            case FIND_SITE -> "hledám místo na dům" + where;
            case RELOCATE -> "stěhuju se dál, tady stavět nechci";
            case GOTO_SITE -> "jdu na staveniště" + where;
            case TERRAFORM -> "srovnávám staveniště pro dům" + where;
            case BUILD -> "stavím si dům" + where + " (zbývá " + (placements.size()
                    + (current != null ? 1 : 0)) + " bloků)";
            case FURNISH -> "zabydluju se – dveře, světlo, postel";
            case DECORATE -> "zvelebuju okolí – cestička a pochodně";
            case DONE -> null;
        };
    }

    // ==================================================================

    /** Zkratka: jsou vesnice zapnuté a služba dostupná? */
    private SettlementService villages(BotContext ctx) {
        SettlementService settlements = ctx.settlements();
        return settlements != null && ctx.config().settlement().enabled()
                ? settlements : null;
    }

    /**
     * Najde staveniště podle plánu bydlení: parcela vesnice, nebo rovné
     * místo v okolí (zakladatel, samotář).
     */
    private void findSite(BotContext ctx, Bot bot) {
        SettlementService settlements = villages(ctx);
        if (plan == null) {
            if (settlements != null) {
                sessionView = ctx.settlementView();
                plan = settlements.planHome(sessionView);
                // Vesnice opakovaně nemá použitelnou parcelu → stavět po svém
                // (členství zůstává, bot bydlí „na samotě u lesa").
                if ((plan.kind() == SettlementService.HomePlan.Kind.MEMBER
                        || plan.kind() == SettlementService.HomePlan.Kind.JOIN)
                        && plotFailedSessions >= PLOT_SESSIONS_BEFORE_SOLO) {
                    plotFailedSessions = 0;
                    plan = SettlementService.HomePlan.solo();
                }
            } else {
                plan = SettlementService.HomePlan.solo();
            }
        }
        switch (plan.kind()) {
            case MEMBER, JOIN -> findPlotSite(ctx, bot, settlements);
            case FOUND_AWAY -> {
                if (!relocated) {
                    startRelocation(ctx, plan.awayFrom());
                } else {
                    findOpenSite(ctx, bot, settlements, true);
                }
            }
            case FOUND -> findOpenSite(ctx, bot, settlements, true);
            case SOLO -> findOpenSite(ctx, bot, settlements, false);
        }
    }

    /** Členská větev: vstup do vesnice a zabrání parcely (validace až na místě). */
    private void findPlotSite(BotContext ctx, Bot bot, SettlementService settlements) {
        if (settlements == null) {
            plan = SettlementService.HomePlan.solo();
            return;
        }
        long settlementId = plan.settlementId();
        if (plan.kind() == SettlementService.HomePlan.Kind.JOIN) {
            if (!settlements.join(settlementId, sessionView)) {
                // Vesnice mezitím zanikla/zaplnila se – postavit se po svém.
                plan = SettlementService.HomePlan.found();
                return;
            }
            ctx.chat().sayFrom(PhraseCategory.SETTLEMENT_JOINED, plan.settlementName());
            plan = new SettlementService.HomePlan(SettlementService.HomePlan.Kind.MEMBER,
                    settlementId, plan.settlementName(), null);
        }
        settlementName = plan.settlementName();
        WorldView world = ctx.worldView();
        boolean terraforming = ctx.config().ai().terraforming();

        // Už přidělenou parcelu zkusit první – přesně tam, kde stavba začala.
        var claimed = settlements.claimedPlot(bot.id());
        if (claimed.isPresent()) {
            var slot = claimed.get();
            int cost = siteCost(world, slot.origin(), structureOf(slot.origin(),
                    slot.facing()), terraforming);
            if (cost != COST_INVALID) {
                // Použitelná, nebo z dálky neposouditelná → dojít a rozhodnout tam.
                target(slot.origin(), slot.facing(), slot.index());
                return;
            }
            settlements.markPlotUnusable(settlementId, slot.index());
            settlements.releasePlot(bot.id());
        }
        // Návrhy od návsi ven; známě špatné rovnou zavrhnout, jinak zabrat
        // a posoudit až na místě (vzdálené chunky nejsou načtené).
        for (SettlementService.PlotSlot slot
                : settlements.suggestPlots(settlementId, PLOT_ATTEMPTS)) {
            int cost = bestCostAround(world, slot.origin(), terraforming);
            if (cost == COST_INVALID) {
                settlements.markPlotUnusable(settlementId, slot.index());
                continue;
            }
            if (settlements.claimPlot(settlementId, bot.id(), slot)) {
                target(slot.origin(), slot.facing(), slot.index());
                return;
            }
        }
        // Vesnice teď nemá volnou parcelu – po pár marných seancích solo fallback.
        plotFailedSessions++;
        cooldownTicks = 1200;
        phase = Phase.DONE;
    }

    /** Zakladatel/samotář: rovné místo v okolí + respekt ke katastru cizích. */
    private void findOpenSite(BotContext ctx, Bot bot, SettlementService settlements,
                              boolean founding) {
        WorldView world = ctx.worldView();
        BlockPos best = localScan(ctx);
        if (best == null) {
            cooldownTicks = 1200; // tady se stavět nedá – zkusit jinde/jindy
            phase = Phase.DONE;
            return;
        }
        if (settlements != null) {
            int personalSpace = ctx.config().settlement().plotSpacing() * 3;
            var foreign = settlements.nearestForeignCenter(bot.id(),
                    world.worldName(), best, personalSpace);
            if (foreign.isPresent()) {
                if (!relocated) {
                    startRelocation(ctx, foreign.get());
                } else {
                    // Ani po přesunu dost daleko – cizím na návsi se nestaví.
                    cooldownTicks = 1200;
                    phase = Phase.DONE;
                }
                return;
            }
        }
        // Vesnice vznikne až s hotovým domem (finishHouse) – žádný fantom.
        pendingFound = founding && settlements != null;
        target(best, HouseFacing.NORTH, null);
    }

    /** Přijme cíl stavby; úpravy terénu se plánují až po příchodu na místo. */
    private void target(BlockPos site, HouseFacing siteFacing, Integer plotIndex) {
        origin = site;
        facing = siteFacing;
        claimedIndex = plotIndex;
        phase = Phase.GOTO_SITE;
    }

    /** Lokální sken rovného místa (nenačtené okraje se přeskočí). */
    private BlockPos localScan(BotContext ctx) {
        WorldView world = ctx.worldView();
        BlockPos feet = ctx.position().toBlockPos();
        boolean terraformingAllowed = ctx.config().ai().terraforming();

        BlockPos best = null;
        int bestCost = Integer.MAX_VALUE;
        for (int dx = -8; dx <= 8; dx += 2) {
            for (int dz = -8; dz <= 8; dz += 2) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos candidate = feet.offset(dx, dy, dz);
                    int cost = siteCost(world, candidate, Set.of(), terraformingAllowed);
                    if (cost >= 0 && cost < bestCost) {
                        bestCost = cost;
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Nejlepší cena parcely přes výškové posuny ±2. Nenačtený chunk vrací
     * {@link #COST_UNKNOWN} (posoudí se až na místě), jinak nejlepší
     * dosažená cena nebo {@link #COST_INVALID}.
     */
    private int bestCostAround(WorldView world, BlockPos suggested, boolean terraforming) {
        int best = COST_INVALID;
        for (int dy : DY_ORDER) {
            int cost = siteCost(world, suggested.offset(0, dy, 0), Set.of(), terraforming);
            if (cost == COST_UNKNOWN) {
                return COST_UNKNOWN;
            }
            if (cost >= 0 && (best < 0 || cost < best)) {
                best = cost;
            }
        }
        return best;
    }

    /** Zahájí odchod dál od cizí vesnice (odštěpenec, samotář v katastru). */
    private void startRelocation(BotContext ctx, BlockPos awayFrom) {
        Vec3 pos = ctx.position();
        double dx = pos.x() - awayFrom.x();
        double dz = pos.z() - awayFrom.z();
        double len = Math.hypot(dx, dz);
        if (len < 1) {
            // Stojí přímo na návsi – náhodná světová strana.
            HouseFacing direction = HouseFacing.values()[ctx.rng().rangeInt(0, 3)];
            dx = direction.dx();
            dz = direction.dz();
            len = 1;
        }
        int minDistance = ctx.config().settlement().minVillageDistance();
        double travel = Math.max(32, minDistance - len
                + ctx.config().settlement().plotSpacing() * 2.0);
        relocateTarget = new BlockPos(
                (int) Math.round(pos.x() + dx / len * travel),
                (int) Math.round(pos.y()),
                (int) Math.round(pos.z() + dz / len * travel));
        phase = Phase.RELOCATE;
    }

    /**
     * Jde k cíli odchodu; kam až dojde, tam se hledá staveniště. Příchod se
     * měří jen vodorovně – cílové Y je odhad z místa startu, skutečný terén
     * může být o desítky bloků jinde.
     */
    private void relocate(BotContext ctx) {
        BlockPos feet = ctx.position().toBlockPos();
        double dx = feet.x() - relocateTarget.x();
        double dz = feet.z() - relocateTarget.z();
        if (dx * dx + dz * dz <= 36) {
            ctx.navigator().stop();
            relocated = true;
            phase = Phase.FIND_SITE;
            return;
        }
        ctx.navigator().navigateTo(ctx.position(), relocateTarget);
        if (!ctx.navigator().navigating()) {
            // Dál to nejde – hledá se tam, kam bot došel (katastr se ověří znovu).
            relocated = true;
            phase = Phase.FIND_SITE;
        }
    }

    /** Bloky domu samotného (zdi + střecha) – při kontrolách se přeskakují. */
    private static Set<BlockPos> structureOf(BlockPos origin, HouseFacing facing) {
        return new HashSet<>(HouseBlueprint.placements(origin, facing));
    }

    /**
     * Cena úprav staveniště: počet výkopů + zásypů. Pozice ze {@code skip}
     * (vlastní zdi a střecha při návratu na parcelu) se nepočítají.
     *
     * @return cena, {@link #COST_INVALID} (tekutina / moc úprav / úpravy
     *         zakázané), nebo {@link #COST_UNKNOWN} (nenačtený chunk)
     */
    private int siteCost(WorldView world, BlockPos candidate, Set<BlockPos> skip,
                         boolean terraformingAllowed) {
        int fills = 0;
        int digs = 0;
        for (int x = 0; x < HouseBlueprint.SIZE; x++) {
            for (int z = 0; z < HouseBlueprint.SIZE; z++) {
                BlockPos ground = candidate.offset(x, -1, z);
                BlockTraits traits = world.traitsAt(ground);
                if (traits == BlockTraits.UNKNOWN) {
                    return COST_UNKNOWN;
                }
                if (traits.liquid()) {
                    return COST_INVALID;
                }
                if (!traits.solid()) {
                    fills++;
                    if (fills > MAX_FILLS || !terraformingAllowed) {
                        return COST_INVALID;
                    }
                }
                for (int y = 0; y <= HouseBlueprint.WALL_HEIGHT; y++) {
                    BlockPos space = candidate.offset(x, y, z);
                    if (skip.contains(space)) {
                        continue;
                    }
                    BlockTraits spaceTraits = world.traitsAt(space);
                    if (spaceTraits == BlockTraits.UNKNOWN) {
                        return COST_UNKNOWN;
                    }
                    if (spaceTraits.liquid()) {
                        return COST_INVALID;
                    }
                    if (spaceTraits.solid()) {
                        digs++;
                        if (digs > MAX_DIGS || !terraformingAllowed) {
                            return COST_INVALID;
                        }
                    }
                }
            }
        }
        return fills * 2 + digs; // zásyp stojí i materiál
    }

    /** Dojít doprostřed staveniště; na místě finální kontrola a plán úprav. */
    private void gotoSite(BotContext ctx, Bot bot) {
        BlockPos stand = HouseBlueprint.standPoint(origin, facing);
        Vec3 pos = ctx.position();
        if (pos.toBlockPos().distanceSquared(stand) <= 2) {
            ctx.navigator().stop();
            if (prepareSite(ctx, bot)) {
                phase = terraform.isEmpty() ? Phase.BUILD : Phase.TERRAFORM;
            }
            return;
        }
        ctx.navigator().navigateTo(pos, stand);
        if (!ctx.navigator().navigating()) {
            cooldownTicks = 1200; // staveniště nedostupné
            phase = Phase.DONE;
        }
    }

    /**
     * Finální posouzení staveniště s načteným okolím: doladit výšku
     * (rozestavěná parcela se zkouší přesně, bez posunu), naplánovat
     * terén a stavbu; nepoužitelnou parcelu vrátit a hledat dál.
     *
     * @return {@code true} když se může stavět
     */
    private boolean prepareSite(BotContext ctx, Bot bot) {
        WorldView world = ctx.worldView();
        boolean terraforming = ctx.config().ai().terraforming();
        BlockPos usable = null;
        for (int dy : DY_ORDER) {
            BlockPos candidate = origin.offset(0, dy, 0);
            int cost = siteCost(world, candidate, structureOf(candidate, facing),
                    terraforming);
            if (cost >= 0) {
                usable = candidate;
                break;
            }
        }
        if (usable == null) {
            SettlementService settlements = villages(ctx);
            if (claimedIndex != null && settlements != null) {
                // Parcela se na místě ukázala nepoužitelná – vrátit a zkusit
                // hned další (bot už u vesnice stojí, chunky jsou načtené).
                settlements.markPlotUnusable(plan.settlementId(), claimedIndex);
                settlements.releasePlot(bot.id());
                claimedIndex = null;
                phase = Phase.FIND_SITE;
            } else {
                cooldownTicks = 1200;
                phase = Phase.DONE;
            }
            return false;
        }
        if (!usable.equals(origin)) {
            origin = usable;
            SettlementService settlements = villages(ctx);
            if (claimedIndex != null && settlements != null) {
                // Výška se doladila podle terénu – parcela v evidenci musí
                // odpovídat skutečnému originu (návrat ke stavbě po restartu).
                settlements.claimPlot(plan.settlementId(), bot.id(),
                        new SettlementService.PlotSlot(claimedIndex, origin, facing));
            }
        }
        if (claimedIndex != null) {
            plotFailedSessions = 0;
        }
        planTerraform(ctx);
        return true;
    }

    /**
     * Naplánuje výkopy překážek a zásypy děr v podlaze. Pozice patřící domu
     * samotnému se nekopou: přírodní blok ve zdi se stane součástí zdi a
     * hlavně se nesmí bourat vlastní rozestavěná stavba při návratu na parcelu.
     */
    private void planTerraform(BotContext ctx) {
        WorldView world = ctx.worldView();
        Set<BlockPos> structure = structureOf(origin, facing);
        for (BlockPos space : HouseBlueprint.clearVolume(origin)) {
            if (structure.contains(space)) {
                continue;
            }
            if (world.traitsAt(space).solid()) {
                terraform.add(new MineBlockTask(space));
            }
        }
        for (BlockPos ground : HouseBlueprint.groundColumns(origin)) {
            if (!world.traitsAt(ground).solid()) {
                terraform.add(new PlaceBlockTask(ground));
            }
        }
        placements.addAll(HouseBlueprint.placements(origin, facing));
    }

    /** Odbaví frontu tasků (terraform) a přepne do další fáze. */
    private void tickTasks(BotContext ctx, Deque<BotTask> queue, Phase next) {
        if (current == null) {
            current = queue.poll();
            if (current == null) {
                phase = next;
                return;
            }
            if (current instanceof PlaceBlockTask) {
                ctx.inventory().equipBuildingBlock(ctx.serverView().latest());
            }
        }
        if (current.tick(ctx)) {
            current = null;
        }
    }

    /** Staví domek blok po bloku z místa uvnitř. */
    private void tickBuild(BotContext ctx, Bot bot) {
        if (current == null) {
            BlockPos next = placements.poll();
            if (next == null) {
                planFurnish(ctx);
                phase = Phase.FURNISH;
                return;
            }
            // Průchozí pozici přeskočit jen pokud tam už blok je.
            if (ctx.worldView() != null && ctx.worldView().traitsAt(next).solid()) {
                return;
            }
            if (!ctx.inventory().equipBuildingBlock(ctx.serverView().latest())) {
                // Došly bloky – dům zůstal rozestavěný, zkusit dostavět jindy.
                cooldownTicks = 2400;
                phase = Phase.DONE;
                if (!announcedOutOfBlocks && ctx.rng().chance(0.5)) {
                    announcedOutOfBlocks = true;
                    ctx.chat().say("dosly mi bloky, dum dostavim pozdejc");
                }
                return;
            }
            current = new PlaceBlockTask(next);
        }
        if (current.tick(ctx)) {
            current = null;
            placedCount++;
            ctx.stats().addPlaced();
        }
    }

    /** Naplánuje vybavení: dveře do otvoru, pochodeň a postel dovnitř. */
    private void planFurnish(BotContext ctx) {
        var snapshot = ctx.serverView().latest();
        if (snapshot == null) {
            return;
        }
        if (snapshot.hasItem(m -> m.name().endsWith("_DOOR"))) {
            furnish.add(new FurnishStep(m -> m.name().endsWith("_DOOR"),
                    HouseBlueprint.doorBottom(origin, facing)));
        }
        if (snapshot.hasItem(m -> m == org.bukkit.Material.TORCH)) {
            furnish.add(new FurnishStep(m -> m == org.bukkit.Material.TORCH,
                    HouseBlueprint.torchSpot(origin, facing)));
        }
        if (snapshot.hasItem(m -> m.name().endsWith("_BED"))) {
            furnish.add(new FurnishStep(m -> m.name().endsWith("_BED"),
                    HouseBlueprint.bedSpot(origin, facing)));
        }
    }

    /** Osadí dveře/pochodeň/postel; co nejde vybavit, přeskočí. */
    private void tickFurnish(BotContext ctx, Bot bot) {
        if (current == null) {
            FurnishStep step = furnish.poll();
            if (step == null) {
                planDecor(ctx, bot);
                phase = Phase.DECORATE;
                return;
            }
            if (!ctx.inventory().equipMatching(ctx.serverView().latest(), step.item())) {
                return; // item mezitím zmizel – další krok příští tick
            }
            current = new PlaceBlockTask(step.target());
        }
        if (current.tick(ctx)) {
            current = null;
        }
    }

    /**
     * Naplánuje zvelebení okolí domu ve vesnici: udusanou cestičku od dveří
     * směrem k návsi (lopatou, jako hráč) a pár pochodní podél ní – vesnice
     * v noci nespawnuje moby mezi domy a začne i vypadat jako vesnice.
     * Mimo vesnici (samotáři) se nezvelebuje.
     */
    private void planDecor(BotContext ctx, Bot bot) {
        var cfg = ctx.config().settlement();
        SettlementService settlements = villages(ctx);
        boolean inVillage = settlements != null && (claimedIndex != null || pendingFound);
        if (!inVillage || (!cfg.lighting() && !cfg.paths())) {
            return;
        }
        var snapshot = ctx.serverView().latest();
        boolean torches = cfg.lighting() && snapshot != null
                && snapshot.hasItem(m -> m == org.bukkit.Material.TORCH);
        boolean shovel = cfg.paths() && snapshot != null
                && snapshot.hasItem(m -> m.name().endsWith("_SHOVEL"));
        if (!torches && !shovel) {
            return;
        }
        WorldView world = ctx.worldView();
        BlockPos door = HouseBlueprint.doorBottom(origin, facing);
        // Délka linie: člen až k návsi, zakladatel pár kroků od vlastních dveří.
        int length = 6;
        if (claimedIndex != null) {
            var own = settlements.settlementOf(bot.id());
            if (own.isPresent()) {
                var center = own.get().center();
                int distance = (int) Math.round(Math.hypot(
                        center.x() - door.x(), center.z() - door.z()));
                length = Math.max(3, Math.min(distance - 2, cfg.plotSpacing() + 4));
            }
        }
        // Kolmý směr pro pochodně vedle cestičky (ne v chůzi).
        int perpX = facing.dz();
        int perpZ = -facing.dx();
        for (int d = 1; d <= length; d++) {
            int x = door.x() + facing.dx() * d;
            int z = door.z() + facing.dz() * d;
            BlockPos ground = groundAt(world, x, origin.y(), z);
            if (ground == null) {
                continue;
            }
            if (shovel && world.materialAt(ground) == org.bukkit.Material.GRASS_BLOCK) {
                decor.add(new DecorStep(true, ground));
            }
            if (torches && d % 4 == 2) {
                BlockPos torchGround = groundAt(world, x + perpX, origin.y(), z + perpZ);
                if (torchGround != null) {
                    decor.add(new DecorStep(false, torchGround.up()));
                }
            }
        }
    }

    /** První pevný blok s volnem nad sebou v okolí výšky staveniště. */
    private static BlockPos groundAt(WorldView world, int x, int yHint, int z) {
        for (int dy = 2; dy >= -3; dy--) {
            BlockPos pos = new BlockPos(x, yHint + dy, z);
            if (world.traitsAt(pos).solid() && !world.traitsAt(pos.up()).solid()) {
                return pos;
            }
        }
        return null;
    }

    /** Zvelebuje okolí: dojde ke kroku, udusá cestičku / zapíchne pochodeň. */
    private void tickDecor(BotContext ctx, Bot bot) {
        if (decorWaitTicks > 0) {
            decorWaitTicks--;
            return;
        }
        if (current != null) {
            if (current.tick(ctx)) {
                current = null;
            }
            return;
        }
        DecorStep step = decor.peek();
        if (step == null) {
            finishHouse(ctx, bot);
            return;
        }
        // Kroky jsou podél linie – dojít na dosah ruky.
        double distSq = ctx.position().toBlockPos().distanceSquared(step.target());
        if (distSq > 12) {
            ctx.navigator().navigateTo(ctx.position(), step.target());
            if (!ctx.navigator().navigating()) {
                decor.poll(); // nedostupný krok přeskočit
            }
            return;
        }
        ctx.navigator().stop();
        decor.poll();
        var snapshot = ctx.serverView().latest();
        if (step.path()) {
            if (!ctx.inventory().equipMatching(snapshot,
                    m -> m.name().endsWith("_SHOVEL"))) {
                decor.removeIf(DecorStep::path); // bez lopaty už jen pochodně
                return;
            }
            ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0),
                    step.target().center().add(0, 1, 0));
            ctx.actions().useItemOn(step.target(),
                    org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction.UP);
            decorWaitTicks = ctx.rng().rangeInt(8, 14);
        } else {
            if (!ctx.inventory().equipMatching(snapshot,
                    m -> m == org.bukkit.Material.TORCH)) {
                decor.removeIf(s -> !s.path()); // bez pochodní už jen cestička
                return;
            }
            current = new PlaceBlockTask(step.target());
        }
    }

    /** Hotovo: případné založení vesnice, paměť, oznámení, konec cíle. */
    private void finishHouse(BotContext ctx, Bot bot) {
        SettlementService settlements = villages(ctx);
        // Zakladatel: vesnice vzniká až teď, s hotovým domem jako návsí.
        if (pendingFound && settlements != null) {
            BlockPos center = origin.offset(HouseBlueprint.SIZE / 2, 0,
                    HouseBlueprint.SIZE / 2);
            var founded = settlements.foundSettlement(ctx.settlementView(), center,
                    origin, facing, bot.personality().seed());
            if (founded.isPresent()) {
                settlementName = founded.get().name();
                ctx.chat().sayFrom(PhraseCategory.SETTLEMENT_FOUNDED, settlementName);
            }
            // Když založení nevyšlo (vesnice mezitím vyrostla vedle),
            // dům stojí a bot prostě bydlí po svém.
        }
        BlockPos stand = HouseBlueprint.standPoint(origin, facing);
        if (ctx.worldView() != null) {
            Map<String, String> data = new HashMap<>();
            data.put("type", "house");
            // Origin a orientace pro pozdější údržbu (MaintainHomeGoal).
            data.put("ox", String.valueOf(origin.x()));
            data.put("oy", String.valueOf(origin.y()));
            data.put("oz", String.valueOf(origin.z()));
            data.put("facing", facing.name());
            if (settlements != null) {
                settlements.settlementIdOf(bot.id()).ifPresent(
                        id -> data.put("settlement", String.valueOf(id)));
            }
            bot.memory().remember(MemoryKind.HOME, ctx.worldView().worldName(),
                    stand.x(), stand.y(), stand.z(), null, Map.copyOf(data), 0.9);
        }
        if (ctx.rng().chance(0.7)) {
            ctx.chat().say(settlementName == null
                    ? "hotovo! mam vlastni dum :)"
                    : "hotovo! mam dum v " + settlementName + " :)");
        }
        ctx.gainExperience(dev.botalive.core.personality.PersonalityEvolution
                .BotExperience.HOUSE_BUILT);
        houseBuilt = true;
        cooldownTicks = 6000;
        phase = Phase.DONE;
    }

    /** Bot už někde dům má? */
    private boolean hasHouse(Bot bot, BotContext ctx) {
        List<MemoryRecord> homes = bot.memory().recall(MemoryKind.HOME);
        for (MemoryRecord home : homes) {
            if ("house".equals(home.data().get("type"))) {
                return true;
            }
        }
        return false;
    }
}
