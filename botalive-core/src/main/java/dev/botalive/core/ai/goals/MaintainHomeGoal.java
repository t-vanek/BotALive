package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.memory.MemoryRecord;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.build.plan.Blueprint;
import dev.botalive.core.build.plan.Blueprints;
import dev.botalive.core.build.plan.FurnishCell;
import dev.botalive.core.build.plan.HomeUpgrade;
import dev.botalive.core.build.plan.HouseDesigner;
import dev.botalive.core.bot.ServerSideView;
import dev.botalive.core.build.plan.BuildTier;
import dev.botalive.core.build.plan.Palette;
import dev.botalive.core.build.plan.PaletteRole;
import dev.botalive.core.build.plan.PlacementCell;
import dev.botalive.core.build.plan.StructureGrowth;
import dev.botalive.core.build.plan.StructureSize;
import dev.botalive.core.build.plan.StructureSizer;
import dev.botalive.core.build.plan.SubstitutionPolicy;
import dev.botalive.core.util.Cardinal;
import dev.botalive.core.chat.PhraseCategory;
import dev.botalive.core.settlement.SettlementService;
import dev.botalive.core.settlement.SettlementTier;
import dev.botalive.core.tasks.BotTask;
import dev.botalive.core.tasks.MineBlockTask;
import dev.botalive.core.tasks.PlaceBlockTask;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.BlockTraits;
import dev.botalive.core.world.WorldView;

import org.bukkit.Material;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Údržba vlastního domu – creeper díry, vytlučené zdi, chybějící dveře.
 *
 * <p>Bot čas od času (a jen když je poblíž domova) projde svůj dům proti
 * jeho plánu ({@link Blueprint}): doplní chybějící bloky zdí a střechy
 * správným materiálem (paleta podle role), vykope, co se připletlo do
 * dveřního otvoru, a osadí nové dveře, má-li je u sebe. Uzavírá smyčku
 * postav → bydli → <b>opravuj</b>; bez ní by po prvním creeperu vesnice
 * nevratně chátraly.</p>
 *
 * <p>Plán domu: legacy domek 4×4, nebo generovaný dům rekonstruovaný
 * z HOME dat (šířka, výška, dřevo, seed). Orientace: člen vesnice ji zná
 * z parcely, novější sólo domy z HOME dat ({@code ox/oy/oz/facing});
 * starým domům bez metadat se origin zrekonstruuje z bodu uložení
 * (stand point, sever).</p>
 */
public final class MaintainHomeGoal extends AbstractGoal {

    /** Kolik bloků nejvýš opravit za jednu seanci (zbytek příště). */
    private static final int MAX_REPAIRS = 16;
    /**
     * Kolik bloků nejvýš povýšit za jednu seanci (střídmě – dům dozrává pomalu,
     * ne přes noc). Míň než oprav: údržba je prioritní, zvelebení bonus.
     */
    private static final int MAX_UPGRADES = 6;
    /**
     * Kolik starých vnitřních bloků nejvýš odklidit za seanci při růstu (aditivní
     * napřed – demolice běží až po dostavbě pláště, tak střídmě jako upgrade).
     */
    private static final int MAX_DEMOLITIONS = 8;
    /** Pauza mezi kontrolami, když bylo všechno v pořádku. */
    private static final int CALM_COOLDOWN = 12000;
    /** Pauza po opravě (a mezi neúspěchy). */
    private static final int BUSY_COOLDOWN = 4000;

    private enum Phase { GOTO, REPAIR, FURNISH, DECOR, DONE }

    /** Krok vybavení: co vzít do ruky a kam to položit. */
    private record FurnishStep(java.util.function.Predicate<org.bukkit.Material> item,
                               BlockPos target) {
    }

    private Phase phase = Phase.GOTO;
    private BlockPos origin;
    private Cardinal facing = Cardinal.NORTH;
    /** Plán a materiály domu (legacy 4×4, nebo rekonstruovaný generovaný). */
    private Blueprint blueprint;
    private Palette palette = Palette.GENERIC;
    /** Ucpávky ve vchodu k vykopání. */
    private final Deque<BlockPos> clears = new ArrayDeque<>();
    /** Chybějící bloky stavby k doplnění (nesou roli pro správný materiál). */
    private final Deque<PlacementCell> refills = new ArrayDeque<>();
    /**
     * Bloky k povýšení na cílový tier: stojí, ale z nižšího materiálu – vytěží
     * se a nahradí cílovým (po celých rolích, s materiálem v ruce před těžbou).
     */
    private final Deque<PlacementCell> upgrades = new ArrayDeque<>();
    /** Míří dům na vyšší tier, než na jakém stojí? (prosperita/osobnost stouply) */
    private boolean upgrading;
    /** Efektivní stavební stupeň domu této seance (max z uloženého a cílového). */
    private BuildTier tier = BuildTier.SOLID;
    /**
     * Roste dům strukturálně (prosperita zvětšila cílovou velikost nad uloženou)?
     * Když ano, {@code blueprint} je už větší cíl na přepočítaném center-fixed
     * originu; nejdřív se dostaví plášť (refills), pak se odklidí starý vnitřek.
     */
    private boolean growing;
    /** Starý (menší) blueprint a jeho origin – k odklizení vnitřku po dostavbě. */
    private Blueprint growOldGeometry;
    private BlockPos growOldOrigin;
    /** Cílová velikost růstu k zapsání po dokončení (monotónně). */
    private int growTargetW;
    private int growTargetH;
    /** Staré strukturální bloky uvnitř nového pláště k odklizení (až plášť stojí). */
    private final Deque<BlockPos> demolitions = new ArrayDeque<>();
    private final Deque<FurnishStep> furnish = new ArrayDeque<>();
    private DecorWorker decor;
    private BotTask current;
    private int cooldownTicks;
    private int repairedCount;

    /** Vytvoří cíl. */
    public MaintainHomeGoal() {
        super("maintain");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        // Dům stojí v overworldu – z Netheru se neopravuje.
        if (outsideOverworld(ctx)) {
            return 0;
        }
        // Opravuje se za světla (stejné okno jako stavba).
        long time = ctx.worldTime();
        if (time >= 11500 && time <= 23000) {
            return 0;
        }
        MemoryRecord home = houseRecord(bot);
        if (home == null || ctx.worldView() == null
                || !ctx.worldView().worldName().equals(home.world())) {
            return 0;
        }
        // Kontroluje se jen poblíž domova (ráno po probuzení je bot doma).
        BlockPos homePos = new BlockPos(home.x(), home.y(), home.z());
        if (ctx.position().toBlockPos().distanceSquared(homePos) > 64 * 64) {
            return 0;
        }
        double caution = bot.personality().trait(Trait.CAUTION);
        double intelligence = bot.personality().trait(Trait.INTELLIGENCE);
        return 5 + caution * 5 + intelligence * 3;
    }

    @Override
    public void start(Bot bot) {
        phase = Phase.GOTO;
        clears.clear();
        refills.clear();
        upgrades.clear();
        upgrading = false;
        demolitions.clear();
        growing = false;
        growOldGeometry = null;
        furnish.clear();
        decor = null;
        current = null;
        repairedCount = 0;
        resolveOriginAndFacing(bot);
        resolveDesign(bot);
    }

    /**
     * Zjistí plán a materiály domu: generovaný z HOME dat (šířka, výška, dřevo,
     * seed), jinak legacy domek 4×4. Poškozená metadata generovaného domu
     * raději neopravovat ({@code origin=null}) než ho poškodit legacy plánem.
     */
    private void resolveDesign(Bot bot) {
        blueprint = Blueprints.house();
        palette = Palette.GENERIC;
        MemoryRecord home = houseRecord(bot);
        if (home == null || !home.data().containsKey("design")) {
            return;
        }
        try {
            // Bez uloženého stupně = SOLID (starý dům se opraví jako solidní).
            String tierStr = home.data().get("btier");
            BuildTier storedTier = tierStr == null
                    ? BuildTier.SOLID
                    : BuildTier.fromOrdinal(Integer.parseInt(tierStr));
            // Cílový tier z aktuální prosperity a osobnosti; když stoupl nad
            // uložený, dům se povyšuje (jinak čistá oprava na uloženém stupni).
            // max() nikdy nesnižuje – dům povýšený za rozkvětu se nebourá, když
            // sídlo pak splaskne.
            BuildTier target = targetTier(bot);
            upgrading = target.ordinal() > storedTier.ordinal();
            tier = upgrading ? target : storedTier;
            int storedW = Integer.parseInt(home.data().get("bw"));
            int storedH = Integer.parseInt(home.data().get("bh"));
            Material wood = Material.valueOf(home.data().get("bwood"));
            long seed = Long.parseLong(home.data().get("bseed"));
            var design = new HouseDesigner.HouseDesign(storedW, storedH, wood, seed, tier);
            blueprint = design.blueprint();
            palette = design.palette();
            maybeGrow(bot, storedW, storedH, wood, seed);
        } catch (RuntimeException e) {
            origin = null; // neúplný design – neopravovat (bezpečné)
        }
    }

    /**
     * Rozhodne o STRUKTURÁLNÍM růstu domu: když je zapnutý ({@code build.grow})
     * a prosperita zvětšila cílovou velikost nad uloženou, přepne na režim růstu
     * – {@code blueprint} se nahradí <b>větším</b> cílem na přepočítaném
     * <b>center-fixed</b> originu (střed parcely, a tím i stanoviště a klíč HOME
     * paměti, zůstává na místě) a zapamatuje se starý blueprint k pozdějšímu
     * odklizení vnitřku. Roste jen na <b>vhodném rovném terénu</b> (nová podlaha
     * je celá pevná → plášť má oporu); jinak růst počká. Monotónní: nikdy
     * nezmenší (cíl se porovnává s uloženou velikostí).
     */
    private void maybeGrow(Bot bot, int storedW, int storedH, Material wood, long seed) {
        BotContext ctx = ctx(bot);
        var cfg = ctx.config().build();
        if (!cfg.grow() || origin == null || ctx.worldView() == null) {
            return;
        }
        StructureSize targetSize = StructureSizer.house(settlementTierOf(bot),
                bot.personality().trait(Trait.LAZINESS), cfg.width(), cfg.wallHeight(),
                cfg.maxWallHeight());
        if (targetSize.width() <= storedW && targetSize.wallHeight() <= storedH) {
            return; // nic většího – čistá oprava/upgrade na uložené velikosti
        }
        // Nový roh drží střed na místě (dům je čtvercový): střed = starý roh +
        // půl staré šířky; nový roh = střed − půl nové šířky.
        BlockPos oldOrigin = origin;
        int centerX = oldOrigin.x() + storedW / 2;
        int centerZ = oldOrigin.z() + storedW / 2;
        int newHalf = targetSize.width() / 2;
        BlockPos newOrigin = new BlockPos(centerX - newHalf, oldOrigin.y(), centerZ - newHalf);
        var newDesign = new HouseDesigner.HouseDesign(targetSize.width(),
                targetSize.wallHeight(), wood, seed, tier);
        Blueprint newBp = newDesign.blueprint();
        // Viabilita: nová podlaha (sloupce pod celým novým půdorysem) musí být
        // celá pevná, aby nový plášť (obvod na y=0) měl oporu. Jinak růst počká
        // (opt-in luxus – radši nechat dům malý než postavit torzo na svahu).
        WorldView world = ctx.worldView();
        for (BlockPos ground : newBp.groundColumns(newOrigin, facing)) {
            BlockTraits t = world.traitsAt(ground);
            if (t == BlockTraits.UNKNOWN || !t.solid()) {
                return;
            }
        }
        growing = true;
        upgrading = false; // při růstu se materiál nepovyšuje – nejdřív velikost
        growOldGeometry = blueprint; // stará (uložená) geometrie na starém originu
        growOldOrigin = oldOrigin;
        origin = newOrigin;
        blueprint = newBp;
        palette = newDesign.palette();
        growTargetW = targetSize.width();
        growTargetH = targetSize.wallHeight();
    }

    /** Stupeň sídla bota (osada / bez sídla = OSADA) pro volbu velikosti. */
    private SettlementTier settlementTierOf(Bot bot) {
        SettlementService settlements = ctx(bot).settlements();
        if (settlements == null) {
            return SettlementTier.OSADA;
        }
        return settlements.settlementOf(bot.id())
                .map(SettlementService.SettlementInfo::tier).orElse(SettlementTier.OSADA);
    }

    /**
     * Cílový stavební stupeň domu z prosperity sídla a osobnosti – stejné
     * pravidlo jako při stavbě ({@link HouseDesigner#tierFor}). Bez sídla /
     * bez služby = osada (srub). Hlavní hnací síla je prosperita, osobnost
     * moduluje; přežití hlídá utilita cíle (upgrade běží jen v klidném ranním
     * okně u domova, nikdy nepřebije jídlo/obranu/spánek).
     */
    private BuildTier targetTier(Bot bot) {
        return HouseDesigner.tierFor(settlementTierOf(bot),
                bot.personality().trait(Trait.LAZINESS));
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        if (origin == null || ctx.worldView() == null) {
            cooldownTicks = CALM_COOLDOWN;
            phase = Phase.DONE;
            return;
        }
        switch (phase) {
            case GOTO -> gotoHome(ctx, bot);
            case REPAIR -> tickRepair(ctx);
            case FURNISH -> tickFurnish(ctx);
            case DECOR -> tickDecor(ctx);
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
        if (decor != null) {
            decor.cancel(ctx(bot));
            decor = null;
        }
        clears.clear();
        refills.clear();
        upgrades.clear();
        demolitions.clear();
        furnish.clear();
        super.stop(bot);
    }

    @Override
    public boolean finished(Bot bot) {
        return phase == Phase.DONE;
    }

    @Override
    public String explain(Bot bot) {
        return switch (phase) {
            case GOTO -> "jdu zkontrolovat dům";
            case REPAIR -> growing
                    ? "přistavuju barák do větších rozměrů (zbývá " + (refills.size()
                            + demolitions.size() + (current != null ? 1 : 0)) + " bloků)"
                    : upgrading && clears.isEmpty() && refills.isEmpty()
                    ? "vylepšuju barák (zbývá " + (upgrades.size()
                            + (current != null ? 1 : 0)) + " bloků)"
                    : "opravuju dům (zbývá " + (clears.size() + refills.size()
                            + upgrades.size() + (current != null ? 1 : 0)) + " bloků)";
            case FURNISH -> "doplňuju vybavení – dveře, postel, světlo";
            case DECOR -> "obnovuju cestičku a pochodně před domem";
            case DONE -> null;
        };
    }

    // ==================================================================

    /** Zjistí origin a orientaci domu: parcela → HOME data → rekonstrukce. */
    private void resolveOriginAndFacing(Bot bot) {
        BotContext ctx = ctx(bot);
        origin = null;
        facing = Cardinal.NORTH;
        SettlementService settlements = ctx.settlements();
        if (settlements != null) {
            var plot = settlements.claimedPlot(bot.id());
            if (plot.isPresent() && plot.get().origin() != null) {
                origin = plot.get().origin();
                facing = plot.get().facing();
                return;
            }
        }
        MemoryRecord home = houseRecord(bot);
        if (home == null) {
            return;
        }
        String ox = home.data().get("ox");
        if (ox != null) {
            try {
                origin = new BlockPos(Integer.parseInt(ox),
                        Integer.parseInt(home.data().get("oy")),
                        Integer.parseInt(home.data().get("oz")));
                String storedFacing = home.data().get("facing");
                if (storedFacing != null) {
                    facing = Cardinal.valueOf(storedFacing);
                }
                return;
            } catch (RuntimeException e) {
                origin = null; // poškozená metadata – spadnout na rekonstrukci
            }
        }
        // Starý dům bez metadat: HOME je stand point (střed), stavělo se na sever.
        // Origin = střed − půl šířky: legacy domek 4×4 dá −2, širší generovaný
        // dům (má-li uloženou šířku) se zrekonstruuje kolem svého skutečného středu.
        int half = 2;
        String bw = home.data().get("bw");
        if (bw != null) {
            try {
                half = Integer.parseInt(bw) / 2;
            } catch (NumberFormatException ignored) {
                half = 2; // poškozená šířka – spadnout na legacy 4×4
            }
        }
        origin = new BlockPos(home.x(), home.y(), home.z()).offset(-half, 0, -half);
    }

    /** Dojít k domu a sepsat, co chybí. */
    private void gotoHome(BotContext ctx, Bot bot) {
        BlockPos stand = blueprint.standPoint(origin, facing);
        if (ctx.position().toBlockPos().distanceSquared(stand) > 9) {
            ctx.navigator().navigateTo(ctx.position(), stand);
            if (!ctx.navigator().navigating()) {
                cooldownTicks = BUSY_COOLDOWN;
                phase = Phase.DONE;
            }
            return;
        }
        ctx.navigator().stop();
        planRepairs(ctx, bot);
    }

    /**
     * Diff domu proti plánu: díry ve zdech, ucpaný vchod, chybějící vybavení
     * (dveře, postel, vnitřní pochodeň) a u členů vesnice zhaslé pochodně
     * podél cestičky k návsi.
     */
    private void planRepairs(BotContext ctx, Bot bot) {
        WorldView world = ctx.worldView();
        var snapshot = ctx.serverView().latest();

        // Chybějící bloky stavby proti plánu (díra = nepevná pozice); materiál
        // se doplní podle role (AcceptancePolicy rozhodne, co už je v pořádku).
        int missing = 0;
        for (PlacementCell cell : blueprint.cells(origin, facing)) {
            BlockTraits traits = world.traitsAt(cell.pos());
            if (traits == BlockTraits.UNKNOWN) {
                cooldownTicks = BUSY_COOLDOWN; // okolí nedotažené – příště
                phase = Phase.DONE;
                return;
            }
            if (!traits.solid() && missing < MAX_REPAIRS) {
                // Otvor s politikou LEAVE_EMPTY (okno) nezazdívat: doplnit jen
                // když máme cílový materiál (sklo), jinak zůstane otvorem.
                if (leaveEmpty(cell, snapshot)) {
                    continue;
                }
                refills.add(cell);
                missing++;
            }
        }
        // Vchod: co se tam připletlo (pevný blok, ne dveře), vykopat.
        blueprint.doorCell(origin, facing).ifPresent(doorBottom -> {
            for (BlockPos pos : List.of(doorBottom, doorBottom.up())) {
                if (world.traitsAt(pos).solid()) {
                    clears.add(pos);
                }
            }
        });
        // Vybavení: chybějící dveře (ztracený vchod), postel (spawn) i pochodeň
        // (mobové uvnitř) – co bot má u sebe, doplní.
        for (FurnishCell step : blueprint.furnishing(origin, facing)) {
            var predicate = Blueprints.itemFor(step.kind());
            var material = world.materialAt(step.pos());
            boolean satisfied = material != null && predicate.test(material);
            if (!satisfied && !world.traitsAt(step.pos()).solid()
                    && snapshot != null && snapshot.hasItem(predicate)) {
                furnish.add(new FurnishStep(predicate, step.pos()));
            }
        }
        // Strukturální růst: až nový plášť stojí (nezbývají díry ve zdech ani
        // ucpaný vchod), odklidit staré strukturální bloky, které se staly
        // vnitřními (aditivní napřed, demolice vnitřku naposled – dům je celou
        // dobu zakrytý). Když je odklizeno, zapsat dosaženou velikost (monotónně).
        if (growing && refills.isEmpty() && clears.isEmpty()) {
            var gplan = StructureGrowth.plan(growOldGeometry, growOldOrigin, blueprint,
                    origin, facing, p -> world.traitsAt(p).solid(), 1, MAX_DEMOLITIONS);
            demolitions.addAll(gplan.demolitions());
            if (gplan.done()) {
                persistGrownSize(bot);
                growing = false;
            }
        }
        // Povýšení na vyšší tier (jen když dům míří výš): nahradit bloky nižšího
        // materiálu cílovými, po celých rolích. Až po opravách – údržba je
        // prioritní, zvelebení bonus.
        if (upgrading) {
            planUpgrades(bot, world, snapshot);
        }
        // Cestička a veřejné osvětlení (jen členové vesnice, plán je idempotentní).
        planDecor(ctx, bot);

        if (clears.isEmpty() && refills.isEmpty() && upgrades.isEmpty() && demolitions.isEmpty()
                && furnish.isEmpty() && (decor == null || !decor.hasWork())) {
            cooldownTicks = CALM_COOLDOWN; // všechno drží pohromadě
            phase = Phase.DONE;
            return;
        }
        if (!refills.isEmpty() && ctx.rng().chance(0.5)) {
            ctx.chat().sayFrom(PhraseCategory.HOME_REPAIR, null);
        } else if (refills.isEmpty() && clears.isEmpty() && !upgrades.isEmpty()
                && ctx.rng().chance(0.3)) {
            ctx.chat().say("vylepsuju si barak, at je hezci");
        }
        phase = Phase.REPAIR;
    }

    /**
     * Naplánuje povýšení na cílový tier: najde nejnižší roli, jejíž bloky ještě
     * nejsou z cílového materiálu, a zařadí je (po celých rolích, ne náhodně –
     * dům nevypadá půl na půl). Začne jen když má cílový materiál v ruce
     * (rozhodnutí „náhrada v ruce" – nikdy neubourat bez čeho hned nahradit).
     * Cap na seanci drží tempo střídmé; okna (otvor → sklo) řeší oprava proti
     * vyšší paletě, ne tahle cesta.
     */
    private void planUpgrades(Bot bot, WorldView world, ServerSideView.Snapshot snapshot) {
        var plan = HomeUpgrade.next(blueprint.cells(origin, facing), palette,
                world::materialAt, MAX_UPGRADES);
        if (plan.isEmpty()) {
            // Dům dosáhl cílového tieru – zapsat ho do paměti (monotónně). Pak
            // se příště nemusí zbytečně diffovat a údržba míří na správný
            // materiál i kdyby prosperita později splaskla.
            persistTier(bot, tier);
            return;
        }
        Material intended = palette.intended(plan.get().role()).orElse(null);
        if (intended == null || snapshot == null || !snapshot.hasItem(m -> m == intended)) {
            return; // cílový materiál nemáme – povyšovat začneme, až bude
        }
        upgrades.addAll(plan.get().cells());
    }

    /**
     * Zapíše dosažený stavební stupeň do HOME dat, když ho dům plně dosáhl
     * (konvergoval). Jen zvýšení – nikdy nesnižuje. Paměť merguje podle pozice,
     * takže se přepíše stávající záznam (celá data se předají, ať se neztratí
     * {@code bw/bh/bwood/bseed}).
     */
    private void persistTier(Bot bot, BuildTier achieved) {
        MemoryRecord home = houseRecord(bot);
        if (home == null) {
            return;
        }
        String stored = home.data().get("btier");
        if (stored != null) {
            try {
                if (Integer.parseInt(stored) >= achieved.ordinal()) {
                    return; // už zapsáno (nebo výš) – nepřepisovat
                }
            } catch (NumberFormatException ignored) {
                // poškozené číslo – přepíšeme platným
            }
        }
        Map<String, String> data = new HashMap<>(home.data());
        data.put("btier", String.valueOf(achieved.ordinal()));
        bot.memory().remember(MemoryKind.HOME, home.world(), home.x(), home.y(), home.z(),
                null, data, 0.9);
    }

    /**
     * Zapíše dosaženou VELIKOST domu (a nový roh) do HOME dat, když dům plně
     * dorostl (nový plášť stojí, starý vnitřek odklizen). Jen zvětšení – po
     * commitu je uložená velikost autoritou (idempotentní resume, monotónnost).
     * Klíč paměti (stanoviště = střed) se růstem nehýbe, takže se přepíše týž
     * záznam; parcela v evidenci sídla se posune na nový roh, aby k domu vedla
     * navigace i po restartu (roh drží týž uzel mřížky, jen jinak odsazený).
     */
    private void persistGrownSize(Bot bot) {
        MemoryRecord home = houseRecord(bot);
        if (home == null) {
            return;
        }
        Map<String, String> data = new HashMap<>(home.data());
        data.put("bw", String.valueOf(growTargetW));
        data.put("bh", String.valueOf(growTargetH));
        data.put("ox", String.valueOf(origin.x()));
        data.put("oy", String.valueOf(origin.y()));
        data.put("oz", String.valueOf(origin.z()));
        data.put("design", "house" + growTargetW + "x" + growTargetH);
        bot.memory().remember(MemoryKind.HOME, home.world(), home.x(), home.y(), home.z(),
                null, data, 0.9);
        // Člen vesnice: posunout parcelu na nový roh (týž index/uzel, jiné odsazení),
        // ať resolveOriginAndFacing i navigace míří na skutečný roh dorostlého domu.
        SettlementService settlements = ctx(bot).settlements();
        if (settlements != null) {
            var plot = settlements.claimedPlot(bot.id());
            settlements.settlementIdOf(bot.id()).ifPresent(sid -> plot.ifPresent(slot ->
                    settlements.claimPlot(sid, bot.id(),
                            new SettlementService.PlotSlot(slot.index(), origin, facing))));
        }
    }

    /** U členů vesnice naplánuje obnovu cestičky a pochodní k návsi. */
    private void planDecor(BotContext ctx, Bot bot) {
        var cfg = ctx.config().settlement();
        SettlementService settlements = ctx.settlements();
        if (settlements == null || !cfg.enabled() || (!cfg.lighting() && !cfg.paths())) {
            return;
        }
        var plot = settlements.claimedPlot(bot.id());
        if (plot.isEmpty() || plot.get().origin() == null) {
            return;
        }
        BlockPos center = settlements.settlementOf(bot.id())
                .map(SettlementService.SettlementInfo::center).orElse(null);
        var steps = dev.botalive.core.build.VillageDecor.plan(ctx.worldView(), origin,
                facing, center, cfg.plotSpacing(), cfg.lighting(), cfg.paths());
        if (!steps.isEmpty()) {
            decor = new DecorWorker(steps);
        }
    }

    /** Opravuje frontu: nejdřív vykopat ucpávky vchodu, pak doplnit bloky. */
    private void tickRepair(BotContext ctx) {
        if (current != null) {
            if (current.tick(ctx)) {
                if (current instanceof PlaceBlockTask) {
                    repairedCount++; // pokládku počítá PlaceBlockTask sám (statistika)
                }
                current = null;
            }
            return;
        }
        BlockPos clear = clears.poll();
        if (clear != null) {
            current = new MineBlockTask(clear);
            return;
        }
        PlacementCell cell = refills.poll();
        if (cell == null) {
            // Plášť stojí → odklidit starý vnitřek (bezpečné, obálka je hotová),
            // pak případné povýšení na vyšší tier.
            BlockPos demo = demolitions.poll();
            if (demo != null) {
                if (ctx.worldView() != null && ctx.worldView().traitsAt(demo).solid()) {
                    current = new MineBlockTask(demo);
                }
                return;
            }
            tickUpgrade(ctx);
            return;
        }
        if (!equipFor(ctx, cell.spec().role())) {
            // Došly bloky – zbytek oprav příště, vybavení se zkusí i tak.
            refills.clear();
            phase = Phase.FURNISH;
            return;
        }
        current = new PlaceBlockTask(cell.pos());
    }

    /**
     * Povyšuje jeden blok: nejdřív vytěží starý (jen s cílovým materiálem
     * v ruce – „náhrada v ruce", nikdy díra), příští tick na totéž místo položí
     * cílový materiál. Prázdná fronta → dál na vybavení.
     */
    private void tickUpgrade(BotContext ctx) {
        PlacementCell cell = upgrades.peek();
        if (cell == null) {
            phase = Phase.FURNISH;
            return;
        }
        WorldView world = ctx.worldView();
        if (world != null && world.traitsAt(cell.pos()).solid()) {
            // Ještě stojí starý blok. Vytěžit smíme jen když cílový materiál
            // držíme – jinak zbytek povýšení příště (žádná otevřená díra).
            Material intended = palette.intended(cell.spec().role()).orElse(null);
            var snapshot = ctx.serverView().latest();
            if (intended == null || snapshot == null || !snapshot.hasItem(m -> m == intended)) {
                upgrades.clear();
                phase = Phase.FURNISH;
                return;
            }
            current = new MineBlockTask(cell.pos());
            return;
        }
        // Vytěženo (vzduch) → položit cílový materiál na totéž místo.
        upgrades.poll();
        if (equipFor(ctx, cell.spec().role())) {
            current = new PlaceBlockTask(cell.pos());
        }
    }

    /** Vezme materiál role z palety (náhrada zaměnitelným blokem, jako engine). */
    private boolean equipFor(BotContext ctx, PaletteRole role) {
        var snapshot = ctx.serverView().latest();
        Material intended = palette.intended(role).orElse(null);
        if (intended != null && ctx.inventory().equipItem(snapshot, intended)) {
            return true;
        }
        // Okno bez skla nezazdívat generikem – otvor je legitimní stav.
        if (role.substitution() == SubstitutionPolicy.LEAVE_EMPTY) {
            return false;
        }
        return ctx.inventory().equipBuildingBlock(snapshot);
    }

    /**
     * Otvor s politikou {@link SubstitutionPolicy#LEAVE_EMPTY} (okno), který se
     * nemá zazdívat: doplní se jen když máme cílový materiál (sklo), jinak
     * zůstane otvorem, dokud sklo nebude.
     */
    private boolean leaveEmpty(PlacementCell cell, ServerSideView.Snapshot snapshot) {
        PaletteRole role = cell.spec().role();
        if (role.substitution() != SubstitutionPolicy.LEAVE_EMPTY) {
            return false;
        }
        Material intended = palette.intended(role).orElse(null);
        return intended == null || snapshot == null
                || !snapshot.hasItem(m -> m == intended);
    }

    /** Doplní vybavení (dveře, postel, pochodeň); co chybí v batohu, přeskočí. */
    private void tickFurnish(BotContext ctx) {
        if (current == null) {
            FurnishStep step = furnish.poll();
            if (step == null) {
                phase = Phase.DECOR;
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

    /** Obnoví cestičku a pochodně (sdílený vykonavatel se stavbou). */
    private void tickDecor(BotContext ctx) {
        if (decor == null || decor.tick(ctx)) {
            cooldownTicks = repairedCount > 0 ? BUSY_COOLDOWN : CALM_COOLDOWN;
            phase = Phase.DONE;
        }
    }

    /** HOME záznam typu house. */
    private static MemoryRecord houseRecord(Bot bot) {
        for (MemoryRecord record : bot.memory().recall(MemoryKind.HOME)) {
            if ("house".equals(record.data().get("type"))) {
                return record;
            }
        }
        return null;
    }
}
