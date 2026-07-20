package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.memory.MemoryRecord;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.ai.BotNeeds;
import dev.botalive.core.build.HouseBlueprint;
import dev.botalive.core.build.VillageDecor;
import dev.botalive.core.build.plan.Blueprints;
import dev.botalive.core.build.plan.BuildPlan;
import dev.botalive.core.build.plan.BuildPlanner;
import dev.botalive.core.build.plan.BuildSession;
import dev.botalive.core.build.plan.HouseDesigner;
import dev.botalive.core.build.plan.HouseGenerator;
import dev.botalive.core.util.Cardinal;
import dev.botalive.core.chat.PhraseCategory;
import dev.botalive.core.settlement.SettlementService;
import dev.botalive.core.settlement.SettlementTier;
import dev.botalive.core.settlement.SocialView;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.BlockTraits;
import dev.botalive.core.world.WorldView;

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
    /** Bonus zakladatele za vodu do 24 bloků (jednotky ceny staveniště). */
    private static final int WATER_BONUS = 3;
    /** Bonus zakladatele za stromy do 32 bloků. */
    private static final int WOOD_BONUS = 2;

    private enum Phase { FIND_SITE, RELOCATE, GOTO_SITE, SESSION, DECORATE, DONE }

    private Phase phase = Phase.FIND_SITE;
    private BlockPos origin;
    private Cardinal facing = Cardinal.NORTH;
    /** Vlastní stavbu (terén, pokládka, vybavení) vede sdílený engine. */
    private BuildSession session;
    /** Návrh generovaného domu pro tuto seanci ({@code null} = legacy 4×4). */
    private HouseDesigner.HouseDesign design;
    private DecorWorker decor;
    private int cooldownTicks;
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
        // Domy se stavějí v overworldu.
        if (outsideOverworld(ctx)) {
            return 0;
        }
        // Už dům má → nestavět další.
        if (houseBuilt || hasHouse(bot, ctx)) {
            return 0;
        }
        // Vstupní brány (denní doba, materiál) platí jen pro ZAHÁJENÍ stavby.
        // Dřív se vyhodnocovaly i pro běžící cíl: jakmile během stavby ubyly
        // bloky pod plný požadavek nebo padla noc, utility spadla na 0, mozek
        // cíl opustil a start() smazal postup – ve světě zůstávala torza.
        boolean inProgress = session != null || claimedIndex != null;
        if (!inProgress) {
            // Dům se staví za světla.
            long time = ctx.worldTime();
            if (time >= 11500 && time <= 23000) {
                return 0;
            }
            // Bez dostatku bloků nemá cenu začínat (generovaný dům jich chce víc).
            BotNeeds needs = BotNeeds.assess(ctx.serverView().latest());
            if (needs.buildingBlocks() < blocksNeededFor(ctx, bot)) {
                return 0;
            }
        }
        double intelligence = bot.personality().trait(Trait.INTELLIGENCE);
        double caution = bot.personality().trait(Trait.CAUTION);
        return 8 + intelligence * 8 + caution * 6;
    }

    @Override
    public void start(Bot bot) {
        phase = Phase.FIND_SITE;
        origin = null;
        facing = Cardinal.NORTH;
        session = null;
        design = null;
        decor = null;
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
            case SESSION -> tickSession(ctx, bot);
            case DECORATE -> tickDecor(ctx, bot);
            case DONE -> {
            }
        }
    }

    @Override
    public void stop(Bot bot) {
        if (session != null) {
            session.cancel(ctx(bot));
        }
        if (decor != null) {
            decor.cancel(ctx(bot));
            decor = null;
        }
        super.stop(bot);
    }

    @Override
    public boolean finished(Bot bot) {
        return phase == Phase.DONE;
    }

    @Override
    public boolean blocksRelocation() {
        return true; // uprostřed stavby se nestěhuje – parcela by se uvolnila pod rukama
    }

    @Override
    public String explain(Bot bot) {
        String where = settlementName == null ? "" : " v " + settlementName;
        return switch (phase) {
            case FIND_SITE -> "hledám místo na dům" + where;
            case RELOCATE -> "stěhuju se dál, tady stavět nechci";
            case GOTO_SITE -> "jdu na staveniště" + where;
            case SESSION -> "stavím si dům" + where
                    + (session != null ? " (zbývá " + session.remaining() + " bloků)" : "");
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
            dev.botalive.core.settlement.SettlementAnnouncer.sayJoined(ctx.chat(),
                    plan.settlementName());
            // Univerzál převezme řemeslo, které v sídle chybí (fáze C).
            if (bot.role() == dev.botalive.api.role.BotRole.NONE) {
                settlements.missingCoreRole(settlementId).ifPresent(needed -> {
                    bot.role(needed);
                    ctx.chat().sayFrom(dev.botalive.core.chat.PhraseCategory
                            .SETTLEMENT_ROLE_TAKEN, needed.displayName());
                });
            }
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
        BlockPos best = localScan(ctx, founding);
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
        target(best, Cardinal.NORTH, null);
    }

    /** Přijme cíl stavby; úpravy terénu se plánují až po příchodu na místo. */
    private void target(BlockPos site, Cardinal siteFacing, Integer plotIndex) {
        origin = site;
        facing = siteFacing;
        claimedIndex = plotIndex;
        phase = Phase.GOTO_SITE;
    }

    /**
     * Lokální sken rovného místa (nenačtené okraje se přeskočí). Zakladatel
     * vesnice navíc hodnotí bohatost okolí – voda a stromy na dosah dělají
     * z místa vesnici, ne tábor v poušti.
     */
    private BlockPos localScan(BotContext ctx, boolean founding) {
        WorldView world = ctx.worldView();
        BlockPos feet = ctx.position().toBlockPos();
        boolean terraformingAllowed = ctx.config().ai().terraforming();

        BlockPos best = null;
        int bestScore = Integer.MAX_VALUE;
        for (int dx = -8; dx <= 8; dx += 2) {
            for (int dz = -8; dz <= 8; dz += 2) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos candidate = feet.offset(dx, dy, dz);
                    int cost = siteCost(world, candidate, Set.of(), terraformingAllowed);
                    if (cost < 0) {
                        continue;
                    }
                    // Bonus za okolí jen při zakládání: u parcel rozhoduje
                    // vesnice, samotáři je jedno, kde tábor stojí.
                    int score = founding ? cost - environmentBonus(world, candidate) : cost;
                    if (score < bestScore) {
                        bestScore = score;
                        best = candidate;
                    }
                }
            }
        }
        return best;
    }

    /**
     * Bohatost okolí kandidáta: voda do 24 a stromy do 32 bloků. Levné sondy
     * po osmi paprscích nad chunk snapshoty – nenačtené okraje prostě bonus
     * nedají (nezakládá se naslepo daleko od těla).
     */
    private static int environmentBonus(WorldView world, BlockPos site) {
        boolean water = false;
        boolean wood = false;
        for (int dir = 0; dir < 8 && !(water && wood); dir++) {
            double angle = dir * Math.PI / 4;
            double ux = Math.cos(angle);
            double uz = Math.sin(angle);
            for (int r = 8; r <= 32 && !(water && wood); r += 8) {
                BlockPos base = site.offset((int) Math.round(ux * r), 0,
                        (int) Math.round(uz * r));
                // Svislý sloupek vyrovnává zvlnění terénu (svahy, břehy).
                for (int dy = -3; dy <= 4; dy++) {
                    BlockPos probe = base.offset(0, dy, 0);
                    if (!water && r <= 24) {
                        var traits = world.traitsAt(probe);
                        if (traits.liquid() && !traits.hazard()) {
                            water = true;
                        }
                    }
                    if (!wood) {
                        var material = world.materialAt(probe);
                        if (material != null && material.name().endsWith("_LOG")) {
                            wood = true;
                        }
                    }
                }
            }
        }
        return (water ? WATER_BONUS : 0) + (wood ? WOOD_BONUS : 0);
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
            Cardinal direction = Cardinal.values()[ctx.rng().rangeInt(0, 3)];
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
    private static Set<BlockPos> structureOf(BlockPos origin, Cardinal facing) {
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
                phase = Phase.SESSION;
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
        // Terén, pokládku i vybavení vede sdílený engine; výběr staveniště
        // (výška, katastr, resume) zůstal na cíli. Complex režim staví
        // generovaný dům z palety, jinak legacy domek 4×4.
        var buildCfg = ctx.config().build();
        if (buildCfg.complex()) {
            design = HouseDesigner.design(bot, ctx.serverView().latest(), buildCfg,
                    settlementTier(ctx, bot));
            BuildPlan buildPlan = BuildPlan.of(design.blueprint(), origin, facing);
            session = new BuildSession(
                    BuildPlanner.schedule(buildPlan, ctx.worldView()), design.palette());
        } else {
            design = null;
            BuildPlan buildPlan = BuildPlan.of(Blueprints.house(), origin, facing);
            session = new BuildSession(BuildPlanner.schedule(buildPlan, ctx.worldView()));
        }
        return true;
    }

    /** Stupeň sídla bota pro volbu velikosti domu (osada / bez sídla = OSADA). */
    private SettlementTier settlementTier(BotContext ctx, Bot bot) {
        SettlementService settlements = ctx.settlements();
        if (settlements == null) {
            return SettlementTier.OSADA;
        }
        return settlements.settlementOf(bot.id())
                .map(SettlementService.SettlementInfo::tier).orElse(SettlementTier.OSADA);
    }

    /** Kolik bloků chce dům, na který se právě chystá (legacy vs generovaný). */
    private int blocksNeededFor(BotContext ctx, Bot bot) {
        var buildCfg = ctx.config().build();
        if (!buildCfg.complex()) {
            return HouseBlueprint.blocksNeeded();
        }
        int width = HouseDesigner.widthFor(settlementTier(ctx, bot),
                bot.personality().trait(Trait.LAZINESS), buildCfg.width());
        return new HouseGenerator(width, buildCfg.wallHeight()).blocksNeeded();
    }

    /**
     * Vede vlastní stavbu přes {@link BuildSession}. Došlý materiál nechá dům
     * rozestavěný (dostaví se jindy, resume world-diffem); nedostupné
     * staveniště se vzdá; hotová stavba pokračuje do zvelebení okolí.
     */
    private void tickSession(BotContext ctx, Bot bot) {
        switch (session.tick(ctx)) {
            case RUNNING -> {
            }
            case DONE -> {
                planDecor(ctx, bot);
                phase = Phase.DECORATE;
            }
            case BLOCKED_MATERIAL -> {
                cooldownTicks = 2400;
                phase = Phase.DONE;
                if (!announcedOutOfBlocks && ctx.rng().chance(0.5)) {
                    announcedOutOfBlocks = true;
                    ctx.chat().say("dosly mi bloky, dum dostavim pozdejc");
                }
            }
            case UNREACHABLE -> {
                cooldownTicks = 1200;
                phase = Phase.DONE;
            }
        }
    }

    /**
     * Naplánuje zvelebení okolí domu ve vesnici (cestička k návsi, pochodně).
     * Mimo vesnici (samotáři) se nezvelebuje; plán i vykonavatel jsou sdílené
     * s údržbou ({@link VillageDecor}, {@link DecorWorker}).
     */
    private void planDecor(BotContext ctx, Bot bot) {
        var cfg = ctx.config().settlement();
        SettlementService settlements = villages(ctx);
        boolean inVillage = settlements != null && (claimedIndex != null || pendingFound);
        if (!inVillage || (!cfg.lighting() && !cfg.paths())) {
            return;
        }
        BlockPos center = claimedIndex != null
                ? settlements.settlementOf(bot.id())
                        .map(SettlementService.SettlementInfo::center).orElse(null)
                : null;
        decor = new DecorWorker(VillageDecor.plan(ctx.worldView(), origin, facing,
                center, cfg.plotSpacing(), cfg.lighting(), cfg.paths()));
    }

    /** Zvelebuje okolí; po dokončení se dům uzavírá. */
    private void tickDecor(BotContext ctx, Bot bot) {
        if (decor == null || decor.tick(ctx)) {
            finishHouse(ctx, bot);
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
                dev.botalive.core.settlement.SettlementAnnouncer.sayFounded(ctx.chat(),
                        settlementName);
            }
            // Když založení nevyšlo (vesnice mezitím vyrostla vedle),
            // dům stojí a bot prostě bydlí po svém.
        }
        BlockPos stand = design != null
                ? design.blueprint().standPoint(origin, facing)
                : HouseBlueprint.standPoint(origin, facing);
        if (ctx.worldView() != null) {
            Map<String, String> data = new HashMap<>();
            data.put("type", "house");
            // Origin a orientace pro pozdější údržbu (MaintainHomeGoal).
            data.put("ox", String.valueOf(origin.x()));
            data.put("oy", String.valueOf(origin.y()));
            data.put("oz", String.valueOf(origin.z()));
            data.put("facing", facing.name());
            // Generovaný dům: uložit parametry, aby ho údržba (MaintainHomeGoal)
            // zrekonstruovala a opravovala proti správnému plánu, ne proti 4×4.
            if (design != null) {
                data.put("design", design.key());
                data.put("bw", String.valueOf(design.width()));
                data.put("bh", String.valueOf(design.wallHeight()));
                data.put("bwood", design.wood().name());
                data.put("bseed", String.valueOf(design.seed()));
            }
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
        // Dostavěný dům je substance sídla – může ho povýšit (osada→vesnice).
        if (settlements != null) {
            settlements.houseFinished(bot.id()).ifPresent(tier ->
                    dev.botalive.core.settlement.SettlementAnnouncer.sayTierUp(ctx.chat(),
                            tier, settlements.settlementOf(bot.id())
                                    .map(SettlementService.SettlementInfo::name)
                                    .orElse(settlementName)));
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
