package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.ai.BotNeeds;
import dev.botalive.core.build.plan.Blueprint;
import dev.botalive.core.build.plan.Blueprints;
import dev.botalive.core.build.plan.BuildPlan;
import dev.botalive.core.build.plan.BuildPlanner;
import dev.botalive.core.build.plan.BuildSession;
import dev.botalive.core.build.plan.SiteFinder;
import dev.botalive.core.build.plan.Workshops;
import dev.botalive.core.pathfinding.PathGoal;
import dev.botalive.core.chat.PhraseCategory;
import dev.botalive.core.crafting.CraftingService;
import dev.botalive.core.station.ChestStation;
import dev.botalive.core.station.CraftingStation;
import dev.botalive.core.settlement.PlotLayout;
import dev.botalive.core.settlement.SettlementService;
import dev.botalive.core.settlement.SettlementService.ProjectKind;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Cardinal;
import dev.botalive.core.world.WorldDimension;
import dev.botalive.core.world.WorldView;

import org.bukkit.Material;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;

/**
 * Společná stavba pro sídlo (studna, sýpka; růstová roadmapa fáze B).
 *
 * <p>Projekt vlastní {@link SettlementService} (nástěnka po vzoru trhu: první
 * stavitel bere, přerušení uvolňuje dalšímu, restart taky – fyzická stavba je
 * autorita). Vlastní stavbu vede sdílený {@link BuildSession}: druh projektu
 * jen vybere {@link Blueprint} ({@code WELL→studna}, {@code GRANARY→sýpka}) –
 * žádné per-druh větvení geometrie. Parcelu vybírá projekt, takže odpadá její
 * hledání; <b>výšku</b> staveniště si ale stavitel musí doladit sám ({@link
 * SiteFinder}) – katastr rozdává parcely s Y návsi a na svahu je to vedle
 * i o osm bloků. Pak zbývá postavit, osadit a nahlásit dokončení sídlu
 * (povýšení ohlásí stavitel v chatu).</p>
 */
public final class CommunalBuildGoal extends AbstractGoal {

    private enum Phase { CLAIM, CRAFT, PROVISION, DRAW, GOTO, SESSION, FINISH, DONE }

    /** Vnitřní kroky odběru materiálu ze skladu (fáze DRAW). */
    private enum DrawStep { GO, OPEN, TAKE, CLOSE }

    /** Za tímto poloměrem (v blocích) už není sklad ke staveništi „po ruce". */
    private static final int DEPOT_REACH = 128;

    /** Rezerva bloků nad čistou spotřebu stavby (zásypy podlahy). */
    private static final int BLOCK_RESERVE = 8;
    /** Vodorovná vzdálenost od staveniště, kde se doladí výška a začne stavět. */
    private static final int APPROACH_RADIUS = 3;
    /**
     * Minimum stavebních bloků k zahájení – zbytek dodá PROVISION (dotěží
     * v okolí) nebo další seance. Velká stavba (radnice, kostel) se tak
     * dostaví po částech: BLOCKED_MATERIAL uvolní claim, world-diff drží
     * pokrok a další stavitel naváže. U malých staveb je práh jejich plná
     * spotřeba, takže studna/sýpka/tržiště se chovají jako dřív.
     */
    private static final int MIN_BLOCKS = 24;
    /** Kámen/hlína, jejichž vytěžení dá stavební blok (dozásobení staveniště). */
    private static final java.util.function.Predicate<Material> STONE = m ->
            m == Material.STONE || m == Material.COBBLESTONE || m == Material.DEEPSLATE
                    || m == Material.COBBLED_DEEPSLATE || m == Material.DIRT;

    private final CraftingStation crafting;
    private final ChestStation containers;

    private Phase phase = Phase.DONE;
    private SettlementService.ProjectInfo project;
    private BuildPlan plan;
    private BuildSession session;
    private BarrierGather gather;
    private java.util.concurrent.CompletableFuture<Boolean> stationCraft;
    private int cooldownTicks;
    private boolean claimed;
    private java.util.UUID selfId;
    /** Odběr ze skladu se zkusí jednou za pokus o stavbu (pak se dolování). */
    private boolean drewFromDepot;
    private BlockPos depotChest;
    private DrawStep drawStep;
    private int drawWait;
    private java.util.concurrent.CompletableFuture<Integer> draw;

    /** Klíč posledního ohlášeného projektu – hláška jen pro nový, ne re-claim. */
    private long lastAnnouncedProject = -1;

    /**
     * @param crafting   služba craftingu – stavitel dílny si stanici (řezák,
     *                   šípařská deska…) vyrobí na míru, když ji nemá
     * @param containers stanice truhel – odběr materiálu ze společného skladu
     *                   ({@link MaterialDepot}) při dozásobení velkých staveb
     */
    public CommunalBuildGoal(CraftingStation crafting, ChestStation containers) {
        super("communal-build");
        this.crafting = crafting;
        this.containers = containers;
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        if (ctx.worldView() == null || ctx.dimension() != WorldDimension.OVERWORLD) {
            return 0;
        }
        if (ctx.settlements() == null) {
            return 0;
        }
        var needed = ctx.settlements().neededProject(bot.id());
        if (needed.isEmpty()) {
            return 0;
        }
        // Vstupní brány platí jen pro ZAHÁJENÍ – rozdělaná stavba smí doběhnout
        // i po setmění a s ubývajícím materiálem (jinak zůstane torzo).
        boolean inProgress = session != null || claimed;
        if (!inProgress) {
            // Staví se za světla, jako domy.
            long time = ctx.worldTime();
            if (time >= 11500 && time <= 23000) {
                return 0;
            }
            // Stačí ČÁST bloků k zahájení – velkou stavbu dozásobí PROVISION
            // a dostaví se po částech; u malých staveb je práh jejich plná
            // spotřeba, takže se chování studny/sýpky/tržiště nemění.
            BotNeeds needs = BotNeeds.assess(ctx.serverView().latest());
            if (needs.buildingBlocks()
                    < startThreshold(blueprintFor(needed.get().kind()).blocksNeeded())) {
                return 0;
            }
            if (!hasRequiredItems(ctx, needed.get().kind())) {
                // Sýpka/tržiště bez truhel je kůlna; dílna bez stanice prázdná
                // kůlna – zahájí to jen člen, který nutné vybavení má.
                return 0;
            }
        }
        double helpfulness = bot.personality().trait(Trait.HELPFULNESS);
        // Závazek: kdo si stavbu zamluvil, drží se jí – bez bonusu si stavitelé
        // rozdělanou studnu přebírali po pár vteřinách (utility kolotoč).
        return 9 + helpfulness * 9 + (claimed ? 10 : 0);
    }

    @Override
    public void start(Bot bot) {
        phase = Phase.CLAIM;
        project = null;
        plan = null;
        session = null;
        gather = null;
        stationCraft = null;
        claimed = false;
        selfId = bot.id();
        drewFromDepot = false;
        depotChest = null;
        draw = null;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        switch (phase) {
            case CLAIM -> tickClaim(ctx, bot);
            case CRAFT -> tickCraft(ctx, bot);
            case PROVISION -> tickProvision(ctx);
            case DRAW -> tickDraw(ctx);
            case GOTO -> tickGoto(ctx);
            case SESSION -> tickSession(ctx, bot);
            case FINISH -> finishProject(ctx, bot);
            case DONE -> {
            }
        }
    }

    private Blueprint blueprintFor(ProjectKind kind) {
        if (kind.isWorkshop()) {
            return Workshops.blueprint(kind.workshopRole().orElseThrow());
        }
        return switch (kind) {
            case WELL -> Blueprints.well();
            case GRANARY -> Blueprints.granary();
            case MARKET_STALL -> Blueprints.marketStall();
            case WAREHOUSE -> Blueprints.granary(); // sklad = zásobárna s dvojtruhlou
            case TOWN_HALL -> Blueprints.townHall();
            case CHURCH -> Blueprints.church();
            default -> throw new IllegalStateException("není blueprint pro " + kind);
        };
    }

    /**
     * Vybavení, které musí mít stavitel v inventáři, aby stavbu <b>zahájil</b>
     * (jinak by vznikla prázdná kůlna): sýpka dvojtruhla, tržiště jedna,
     * dílna svou hlavní stanici. Vedlejší stanice je bonus (osadí se, když ji
     * stavitel má) – proto tu není.
     */
    private static java.util.List<Material> requiredItems(ProjectKind kind) {
        if (kind.isWorkshop()) {
            return java.util.List.of(
                    Workshops.spec(kind.workshopRole().orElseThrow()).station());
        }
        return switch (kind) {
            case GRANARY, WAREHOUSE -> java.util.List.of(Material.CHEST, Material.CHEST);
            case MARKET_STALL -> java.util.List.of(Material.CHEST);
            default -> java.util.List.of();
        };
    }

    /** Má stavitel v batohu vše nutné k zahájení dané stavby? */
    private static boolean hasRequiredItems(BotContext ctx, ProjectKind kind) {
        var snapshot = ctx.serverView().latest();
        // Dílna: buď má hlavní stanici, nebo ji umí vyrobit na míru (suroviny
        // v batohu) – i dílny mimo běžnou progresi (řezák, loom…) tak vzniknou.
        if (kind.isWorkshop()) {
            Material station = Workshops.spec(kind.workshopRole().orElseThrow()).station();
            return ctx.inventory().countItem(snapshot, station) >= 1
                    || CraftingService.canCraftStation(snapshot, station);
        }
        for (Material material : requiredItems(kind).stream().distinct().toList()) {
            long need = requiredItems(kind).stream().filter(m -> m == material).count();
            if (ctx.inventory().countItem(snapshot, material) < need) {
                return false;
            }
        }
        return true;
    }

    private static PhraseCategory startPhrase(ProjectKind kind) {
        if (kind.isWorkshop()) {
            return PhraseCategory.SETTLEMENT_WORKSHOP_START;
        }
        return switch (kind) {
            case WELL -> PhraseCategory.SETTLEMENT_WELL_START;
            case GRANARY -> PhraseCategory.SETTLEMENT_GRANARY_START;
            case MARKET_STALL -> PhraseCategory.SETTLEMENT_MARKET_START;
            case WAREHOUSE -> PhraseCategory.SETTLEMENT_WAREHOUSE_START;
            case TOWN_HALL -> PhraseCategory.SETTLEMENT_TOWNHALL_START;
            case CHURCH -> PhraseCategory.SETTLEMENT_CHURCH_START;
            default -> throw new IllegalStateException("není hláška pro " + kind);
        };
    }

    private static PhraseCategory donePhrase(ProjectKind kind) {
        if (kind.isWorkshop()) {
            return PhraseCategory.SETTLEMENT_WORKSHOP_DONE;
        }
        return switch (kind) {
            case WELL -> PhraseCategory.SETTLEMENT_WELL_DONE;
            case GRANARY -> PhraseCategory.SETTLEMENT_GRANARY_DONE;
            case MARKET_STALL -> PhraseCategory.SETTLEMENT_MARKET_DONE;
            case WAREHOUSE -> PhraseCategory.SETTLEMENT_WAREHOUSE_DONE;
            case TOWN_HALL -> PhraseCategory.SETTLEMENT_TOWNHALL_DONE;
            case CHURCH -> PhraseCategory.SETTLEMENT_CHURCH_DONE;
            default -> throw new IllegalStateException("není hláška pro " + kind);
        };
    }

    /**
     * Argument pro hlášku o projektu: u dílny její český název (aby chat řekl
     * „stavíme kovárnu"), u infrastruktury jméno sídla.
     */
    private static String phraseArg(ProjectKind kind, String settlementName) {
        if (kind.isWorkshop()) {
            return Workshops.spec(kind.workshopRole().orElseThrow()).csName();
        }
        return settlementName;
    }

    private void tickClaim(BotContext ctx, Bot bot) {
        var needed = ctx.settlements().neededProject(bot.id());
        if (needed.isEmpty()) {
            phase = Phase.DONE;
            return;
        }
        project = needed.get();
        if (!ctx.settlements().claimProject(project.settlementId(), project.kind(), bot.id())) {
            cooldownTicks = 600; // předběhl mě soused – ať staví on
            phase = Phase.DONE;
            return;
        }
        claimed = true;
        plan = BuildPlan.of(blueprintFor(project.kind()), project.origin(), project.facing());
        String name = settlementName(ctx, bot);
        long announceKey = project.settlementId() * 31 + project.kind().ordinal();
        if (name != null && announceKey != lastAnnouncedProject && ctx.rng().chance(0.6)) {
            // Hlásí se jen NOVÝ projekt – opakované zamluvení téhož (návrat
            // ke stavbě, marné pokusy) by chat zaplavilo.
            lastAnnouncedProject = announceKey;
            ctx.chat().sayFrom(startPhrase(project.kind()), phraseArg(project.kind(), name));
        }
        // Dílna, jejíž stanici bot nemá, ale umí ji vyrobit na míru → nejdřív craft.
        phase = needsStationCraft(ctx) ? Phase.CRAFT : Phase.PROVISION;
    }

    /** Chybí staviteli stanice dílny, ale má na ni suroviny (vyrobí na míru)? */
    private boolean needsStationCraft(BotContext ctx) {
        if (project == null || !project.kind().isWorkshop()) {
            return false;
        }
        Material station = Workshops.spec(project.kind().workshopRole().orElseThrow()).station();
        var snapshot = ctx.serverView().latest();
        return ctx.inventory().countItem(snapshot, station) < 1
                && CraftingService.canCraftStation(snapshot, station);
    }

    /** Výroba stanice dílny na míru před cestou na staveniště. */
    private void tickCraft(BotContext ctx, Bot bot) {
        if (stationCraft == null) {
            Material station = Workshops.spec(project.kind().workshopRole().orElseThrow()).station();
            stationCraft = crafting.craftStation(bot.id(), station);
            return;
        }
        if (!stationCraft.isDone()) {
            return;
        }
        boolean crafted = stationCraft.getNow(false);
        stationCraft = null;
        if (crafted) {
            phase = Phase.PROVISION; // stanici má – dozásobí bloky a jde stavět
        } else {
            giveUp(ctx, 2400); // suroviny mezitím ubyly – projekt uvolní dalšímu
        }
    }

    /**
     * Dozásobí stavební bloky na projekt – co chybí, dojde vytěžit v okolí
     * (kámen/hlína, vzor hradeb {@link BarrierGather}). Velkou stavbu shání
     * dokud lokální zdroje stačí; když dojdou, postaví se aspoň s tím, co má
     * (zbytek dostaví další seance přes world-diff). Bez zdroje a bez minima
     * bloků projekt uvolní dalšímu.
     */
    private void tickProvision(BotContext ctx) {
        int have = BotNeeds.assess(ctx.serverView().latest()).buildingBlocks();
        int want = blueprintFor(project.kind()).blocksNeeded() + BLOCK_RESERVE;
        if (provisionStep(have, want, false) == ProvisionStep.BUILD) {
            gather = null;
            phase = Phase.GOTO; // materiálu dost – rovnou stavět
            return;
        }
        // Chybí materiál: nejdřív zkusit společný sklad (levnější než dolování),
        // jednou za pokus o stavbu. Sklad má jen město po dostavbě WAREHOUSE,
        // takže studna/sýpka/tržiště i sám sklad se chovají jako dřív (depot
        // null → rovnou dolování). Velkou stavbu (radnice, kostel) tím ale
        // zásobí sběrači místo samotného stavitele – dělba práce V2c.
        if (!drewFromDepot) {
            BlockPos depot = reachableDepot(ctx);
            if (depot != null) {
                depotChest = depot;
                drawStep = DrawStep.GO;
                draw = null;
                phase = Phase.DRAW;
                return;
            }
            drewFromDepot = true; // není sklad – rovnou dolovat, nezkoušet znovu
        }
        if (gather == null) {
            gather = new BarrierGather(STONE);
        }
        if (gather.tick(ctx) != BarrierGather.State.EXHAUSTED) {
            return; // shání dál
        }
        gather = null;
        if (provisionStep(have, want, true) == ProvisionStep.BUILD) {
            phase = Phase.GOTO; // postaví aspoň s tím, co má (zbytek příště)
        } else {
            giveUp(ctx, 2400); // v okolí není kámen ani hlína
        }
    }

    /** Rozhodnutí fáze PROVISION. */
    enum ProvisionStep { GATHER, BUILD, GIVE_UP }

    /**
     * Co dělat v PROVISION (čistá funkce – testovatelné bez živého kontextu).
     *
     * @param have             stavební bloky v batohu
     * @param want             plná spotřeba stavby + rezerva
     * @param sourcesExhausted v okolí už není co těžit
     * @return {@code BUILD} stavět, {@code GATHER} shánět dál, {@code GIVE_UP} vzdát
     */
    static ProvisionStep provisionStep(int have, int want, boolean sourcesExhausted) {
        if (have >= want) {
            return ProvisionStep.BUILD;
        }
        if (!sourcesExhausted) {
            return ProvisionStep.GATHER;
        }
        return have >= Math.min(MIN_BLOCKS, want)
                ? ProvisionStep.BUILD    // aspoň minimum → postav část, zbytek příště
                : ProvisionStep.GIVE_UP; // ani minimum a okolí prázdné
    }

    /** Práh bloků k zahájení projektu (malá stavba = plná spotřeba). */
    static int startThreshold(int blocksNeeded) {
        return Math.min(MIN_BLOCKS, blocksNeeded + BLOCK_RESERVE);
    }

    /**
     * Odběr stavebních bloků ze společného skladu ({@link MaterialDepot}) –
     * stavitel si dojde k zásobovací truhle a naplní batoh tím, co tam
     * sběrači nanosili. Po odběru (ať úspěšném, či ne) se vrací do PROVISION,
     * kde se přepočítá, zda už má dost, nebo zbytek dotěží dolováním. Sklad
     * se zkusí jen jednou za pokus o stavbu (příznak {@code drewFromDepot}),
     * aby se stavitel nezacyklil u prázdné truhly.
     */
    private void tickDraw(BotContext ctx) {
        WorldView world = ctx.worldView();
        if (world == null || depotChest == null) {
            drewFromDepot = true;
            phase = Phase.PROVISION;
            return;
        }
        switch (drawStep) {
            case GO -> {
                if (depotChest.center().distanceSquared(ctx.position()) > 3.0 * 3.0) {
                    ctx.navigator().navigateTo(ctx.position(), PathGoal.near(depotChest, 2));
                    if (!ctx.navigator().navigating()) {
                        drewFromDepot = true; // sklad nedosažitelný – dotěžím sám
                        phase = Phase.PROVISION;
                    }
                    return;
                }
                ctx.navigator().stop();
                ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0),
                        depotChest.center().add(0, 0.5, 0));
                drawWait = ctx.rng().rangeInt(4, 10);
                drawStep = DrawStep.OPEN;
            }
            case OPEN -> {
                if (--drawWait > 0) {
                    return;
                }
                ctx.actions().useItemOn(depotChest, Direction.UP);
                drawWait = ctx.rng().rangeInt(12, 28);
                drawStep = DrawStep.TAKE;
            }
            case TAKE -> {
                if (--drawWait > 0) {
                    return;
                }
                if (draw == null) {
                    int have = BotNeeds.assess(ctx.serverView().latest()).buildingBlocks();
                    int want = blueprintFor(project.kind()).blocksNeeded() + BLOCK_RESERVE;
                    draw = containers.withdrawBuildingBlocks(ctx, world.worldName(),
                            depotChest, Math.max(0, want - have));
                    return;
                }
                if (!draw.isDone()) {
                    return;
                }
                int moved = draw.getNow(0);
                draw = null;
                if (moved > 0 && ctx.rng().chance(0.5)) {
                    ctx.chat().say("beru materiál ze společného skladu na stavbu");
                }
                drawWait = ctx.rng().rangeInt(5, 12);
                drawStep = DrawStep.CLOSE;
            }
            case CLOSE -> {
                if (--drawWait > 0) {
                    return;
                }
                ctx.actions().closeContainer();
                drewFromDepot = true;
                phase = Phase.PROVISION; // přepočítat: možná teď stačí, jinak dolovat
            }
        }
    }

    /**
     * Zásobovací truhla skladu, je-li ke staveništi rozumně blízko a ve světě
     * opravdu stojí; jinak {@code null} (žádný sklad, jiný svět, moc daleko,
     * nebo bez stanice truhel). Sklad existuje až po dostavbě WAREHOUSE, takže
     * u dřívějších staveb i u samotného skladu se vrací {@code null}.
     */
    private BlockPos reachableDepot(BotContext ctx) {
        WorldView world = ctx.worldView();
        if (world == null || containers == null || ctx.settlements() == null) {
            return null;
        }
        BlockPos depot = MaterialDepot.chest(ctx.settlements(), selfId).orElse(null);
        if (depot == null) {
            return null;
        }
        if (depot.distanceSquared(ctx.position().toBlockPos()) > DEPOT_REACH * DEPOT_REACH) {
            return null; // sklad přes půl světa – dotěžím radši u staveniště
        }
        Material material = world.materialAt(depot);
        if (material != null && material != Material.CHEST
                && material != Material.TRAPPED_CHEST) {
            return null; // sklad zbořený / jiný svět – truhla tam není
        }
        return depot;
    }

    /** Smí seance začít s {@code have} bloky pro stavbu o {@code cells} buňkách? */
    static boolean canStartSession(int have, int cells) {
        return have >= Math.min(MIN_BLOCKS, cells);
    }

    private void tickGoto(BotContext ctx) {
        WorldView world = ctx.worldView();
        if (world == null) {
            giveUp(ctx, 1200);
            return;
        }
        BlockPos stand = plan.stand();
        BlockPos feet = ctx.position().toBlockPos();
        int dx = feet.x() - stand.x();
        int dz = feet.z() - stand.z();
        // Příchod se měří jen VODOROVNĚ. Projekt dostal výšku od návsi
        // (PlotLayout.plotOrigin bere center.y() doslova), takže než se
        // staveniště srovná podle terénu, leží stanoviště na svahu klidně
        // několik bloků ve vzduchu – svislá složka by příchod nepustila nikdy.
        if (dx * dx + dz * dz <= APPROACH_RADIUS * APPROACH_RADIUS) {
            ctx.navigator().stop();
            if (!adjustSite(ctx, world)) {
                return; // staveniště nepoužitelné – projekt přesunut na jinou parcelu
            }
            session = new BuildSession(BuildPlanner.schedule(plan, world));
            // Stačí část bloků – zbytek se dostaví po částech (BLOCKED_MATERIAL
            // uvolní claim, world-diff drží pokrok, další seance naváže).
            if (!canStartSession(BotNeeds.assess(ctx.serverView().latest()).buildingBlocks(),
                    plan.cells().size())) {
                giveUp(ctx, 2400);
                return;
            }
            phase = Phase.SESSION;
            return;
        }
        // Cíl chůze drží výšku bota, ne odhad z katastru: kdyby mířil na
        // stanoviště ve vzduchu, cesta by neexistovala a bot by se stavby
        // vzdal dřív, než by vůbec uviděl terén parcely. Jak sestupuje po
        // svahu, cíl klesá s ním – k parcele tak dojde po zemi.
        ctx.navigator().navigateTo(ctx.position(),
                PathGoal.near(new BlockPos(stand.x(), feet.y(), stand.z()), APPROACH_RADIUS));
        if (!ctx.navigator().navigating()) {
            // Staveniště nedostupné (typicky v backoffu nedosažitelnosti po
            // dálkovém selhání) – cooldown musí být delší než backoff, jinak
            // se claim/hláška točí naprázdno každou minutu.
            giveUp(ctx, 2400);
        }
    }

    /**
     * Srovnání staveniště podle terénu – teprve tady jsou chunky parcely
     * načtené. Katastr rozdává parcely s Y návsi, takže na svahu leží projekt
     * klidně osm bloků nad zemí; bez korekce se plán rozvine do vzduchu,
     * pokládka nemá oporu a stavba nikdy nedoběhne (studna i kovárna
     * v Itssharkovicích).
     *
     * <p>Doladěná výška se vrací do evidence sídla: po restartu se ke stavbě
     * navazuje z ní a cíle si z originu dopočítávají truhlu sýpky i skladu.
     * Nepoužitelné staveniště projekt přestěhuje na jinou parcelu – jinak by
     * na té špatné sídlo uvázlo napořád (obdoba {@code markPlotUnusable}
     * u domů).</p>
     *
     * @return {@code true} když se může stavět
     */
    private boolean adjustSite(BotContext ctx, WorldView world) {
        Blueprint blueprint = blueprintFor(project.kind());
        var site = ctx.config().build().site();
        // Vycentrovat půdorys na parcelu: plotOrigin počítá roh z rozměru domu
        // (4×4), takže studna/tržiště/kostel s jiným rozměrem sedí mimo střed.
        // Idempotentní snap na mřížku – rozestavěná stavba se trefí zpět.
        BlockPos suggested = centerOnPlot(ctx, blueprint, project.origin(), project.facing());
        // Kolik se smí staveniště posunout v parcele: rezerva k sousedovi
        // (rozestup − půdorys) zpola, nejvýš config build.site.search-radius.
        int radius = Math.max(1, Math.min(site.searchRadius(),
                (ctx.config().settlement().plotSpacing()
                        - SiteFinder.footprintSpan(blueprint, project.facing())) / 2));
        SiteFinder.Budget budget = new SiteFinder.Budget(site.surfaceScan(),
                site.maxFills(), site.maxDigs(), site.fillDivisor(), site.digDivisor());
        BlockPos usable = SiteFinder.search(world, blueprint, suggested, project.facing(),
                ctx.config().ai().terraforming(), radius, budget).orElse(null);
        if (usable == null) {
            ctx.settlements().relocateProject(project.settlementId(), project.kind());
            giveUp(ctx, 2400);
            return false;
        }
        if (!usable.equals(project.origin())) {
            ctx.settlements().updateProjectOrigin(project.settlementId(),
                    project.kind(), usable);
            project = new SettlementService.ProjectInfo(project.settlementId(),
                    project.kind(), usable, project.facing(), project.done());
            plan = BuildPlan.of(blueprint, usable, project.facing());
        }
        return true;
    }

    /**
     * Vycentruje roh staveniště na střed parcely podle skutečného půdorysu
     * (idempotentně – rozestavěná stavba se trefí zpět do svého originu). Bez
     * sídla (nemá mřížku) vrací roh beze změny.
     */
    private BlockPos centerOnPlot(BotContext ctx, Blueprint blueprint, BlockPos origin,
                                  Cardinal facing) {
        var info = ctx.settlements().settlementOf(selfId);
        if (info.isEmpty()) {
            return origin;
        }
        int[] dims = SiteFinder.footprintDims(blueprint, facing);
        return PlotLayout.centerFootprint(origin, dims[0], dims[1],
                info.get().center(), ctx.config().settlement().plotSpacing());
    }

    private void tickSession(BotContext ctx, Bot bot) {
        switch (session.tick(ctx)) {
            case RUNNING -> {
            }
            case DONE -> finishProject(ctx, bot);
            // INCOMPLETE = bloky se nepodařilo položit. Projekt se NESMÍ
            // odepsat jako hotový – uvolní se dalšímu staviteli a sídlo si
            // stavbu, která nestojí, nepřipíše.
            case BLOCKED_MATERIAL, UNREACHABLE, INCOMPLETE -> giveUp(ctx, 2400);
        }
    }

    private void finishProject(BotContext ctx, Bot bot) {
        var tier = ctx.settlements().projectFinished(project.settlementId(), project.kind());
        claimed = false;
        String name = settlementName(ctx, bot);
        if (name != null) {
            ctx.chat().sayFrom(donePhrase(project.kind()), phraseArg(project.kind(), name));
        }
        tier.ifPresent(t -> dev.botalive.core.settlement.SettlementAnnouncer
                .sayTierUp(ctx.chat(), t, name));
        ctx.gainExperience(dev.botalive.core.personality.PersonalityEvolution
                .BotExperience.HOUSE_BUILT);
        cooldownTicks = 6000;
        phase = Phase.DONE;
    }

    private void giveUp(BotContext ctx, int cooldown) {
        if (gather != null) {
            gather.cancel(ctx);
            gather = null;
        }
        if (claimed && project != null) {
            ctx.settlements().releaseProject(project.settlementId(), project.kind(), selfId);
            claimed = false;
        }
        cooldownTicks = cooldown;
        phase = Phase.DONE;
    }

    private String settlementName(BotContext ctx, Bot bot) {
        return ctx.settlements().settlementOf(bot.id())
                .map(SettlementService.SettlementInfo::name).orElse(null);
    }

    @Override
    public void stop(Bot bot) {
        // Zamluvení se při přepnutí cíle DRŽÍ (závazek + bonus utility) –
        // stavitel se ke stavbě vrátí; kdyby nadobro zmizel, zamluvení ve
        // službě expiruje a projekt si vezme soused. Nová session naváže
        // tam, kde stavba skončila (položené bloky se přeskakují).
        if (session != null) {
            session.cancel(ctx(bot));
        }
        if (gather != null) {
            gather.cancel(ctx(bot));
            gather = null;
        }
        // Kdyby přepnutí přišlo uprostřed odběru ze skladu, zavřít okno truhly.
        if (phase == Phase.DRAW) {
            ctx(bot).actions().closeContainer();
            draw = null;
        }
        ctx(bot).navigator().stop();
    }

    @Override
    public boolean blocksRelocation() {
        return true;
    }

    @Override
    public boolean finished(Bot bot) {
        return phase == Phase.DONE;
    }

    @Override
    public String explain(Bot bot) {
        if (phase == Phase.PROVISION) {
            return "sháním materiál na stavbu pro sídlo";
        }
        if (project == null) {
            return "stavím studnu pro sídlo";
        }
        if (project.kind().isWorkshop()) {
            return "stavím dílnu pro sídlo – "
                    + Workshops.spec(project.kind().workshopRole().orElseThrow()).csName();
        }
        return switch (project.kind()) {
            case WELL -> "stavím studnu pro sídlo";
            case GRANARY -> "stavím sýpku pro sídlo";
            case MARKET_STALL -> "stavím tržiště pro sídlo";
            case WAREHOUSE -> "stavím sklad pro sídlo";
            case TOWN_HALL -> "stavím radnici pro sídlo";
            case CHURCH -> "stavím kostel pro sídlo";
            default -> "stavím pro sídlo";
        };
    }
}
