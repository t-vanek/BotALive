package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.memory.MemoryRecord;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.ai.BotNeeds;
import dev.botalive.core.chat.PhraseCategory;
import dev.botalive.core.inventory.ItemVariants;
import dev.botalive.core.mining.DigPlanner;
import dev.botalive.core.nether.NetherMath;
import dev.botalive.core.nether.NetherReadiness;
import dev.botalive.core.nether.PortalBlueprint;
import dev.botalive.core.nether.PortalScanner;
import dev.botalive.core.station.ChestStation;
import dev.botalive.core.tasks.MineBlockTask;
import dev.botalive.core.tasks.PlaceBlockTask;
import dev.botalive.core.tasks.UsePortalTask;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.WorldDimension;
import dev.botalive.core.world.WorldView;
import org.bukkit.Material;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.Effect;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import dev.botalive.core.pathfinding.PathGoal;

/**
 * Výprava do Netheru – vrchol dobrodružné progrese bota.
 *
 * <p>Připravený bot (výbava dle {@link NetherReadiness}) najde portál
 * (paměť {@link MemoryKind#PORTAL}, sken okolí) nebo si ho postaví
 * (14 obsidiánu, {@link PortalBlueprint}) a zapálí křesadlem. V Netheru
 * těží quartz, netherové zlato, glowstone a starodávné trosky (tier gating
 * přes {@link BotNeeds#requiredPickTier}), vylupuje truhly pevností
 * a bastionů (kovářské šablony!), pamatuje si struktury a volitelně
 * směňuje zlato s pigliny. Domů se vrací portálem, kterým přišel
 * (PORTAL paměť příletu), nouzově k bodu overworld/8 ({@link NetherMath}).</p>
 *
 * <p>Rozpočty a pojistky: časový limit výpravy ({@code nether.max-trip-minutes}),
 * návrat při nízkém zdraví/hladu, výkopy jen s kontrolou lávy a podlahy
 * (stejné sondy jako {@code MineGoal}). Když je bot v Netheru, utility drží
 * vysoko – po přerušení (boj, jídlo) se k výpravě vždy vrátí a nikdy tam
 * „nezůstane bydlet".</p>
 */
public final class NetherGoal extends AbstractGoal {

    /** Utility výpravy, dokud je bot v Netheru (dokončit/vrátit se). */
    private static final double IN_NETHER_UTILITY = 30;

    /** Hodnoty netherové kořisti pro výběr cíle těžby. */
    private static final Map<Material, Double> NETHER_ORES = Map.of(
            Material.ANCIENT_DEBRIS, 40.0,
            Material.NETHER_GOLD_ORE, 6.0,
            Material.NETHER_QUARTZ_ORE, 4.0,
            Material.GLOWSTONE, 5.0);

    /** Cílová hloubka pro trosky (vanilla nejčastěji y ≈ 15). */
    private static final int DEBRIS_Y = 15;

    /** Rozpočet vykopaných bloků na jednu výpravu. */
    private static final int DIG_BUDGET = 96;

    /** Kolik zlatých ingotů maximálně padne na barter za výpravu. */
    private static final int BARTER_LIMIT = 4;

    private enum Phase { PREPARE, FIND_PORTAL, GO, BUILD, LIGHT, ENTER, WORK, RETURN, DONE }

    private final ChestStation chests;

    private Phase phase = Phase.PREPARE;
    private Phase afterGo;
    private BlockPos goTarget;
    private int goTicks;
    private String goWorld;

    // Portál.
    private BlockPos portalEntry;
    private BlockPos frameBase;
    private boolean frameAxisX;
    private Deque<BlockPos> buildQueue;
    private PlaceBlockTask placeTask;
    private UsePortalTask enterTask;
    private int lightTicks;
    private int lightAttempts;
    private int enterAttempts;

    // Práce v Netheru.
    private long tripDeadlineMs;
    private MineBlockTask mineTask;
    private BlockPos targetBlock;
    private Material targetMaterial;
    private final Deque<DigPlanner.Step> digSteps = new ArrayDeque<>();
    private final Deque<BlockPos> stepBlocks = new ArrayDeque<>();
    private BlockPos pendingWalk;
    private BlockPos pendingWalkAfterStep;
    private int walkTicks;
    private int digBudget;
    private boolean digTaskInPlan;
    private BlockPos lootChest;
    private CompletableFuture<Integer> lootFuture;
    private final Set<Long> lootedChests = new HashSet<>();
    private int barterCount;
    private int barterWaitTicks;
    private Vec3 barterSpot;
    private BlockPos wanderTarget;
    private int wanderTicks;
    private int bootTries;
    private boolean structuresRemembered;

    /** Odpočet do dalšího skenu okolí (rudy/truhly) – nescanovat každý tick. */
    private int scanCooldownTicks;
    /** Odpočet do dalšího hledání cesty domů (RETURN je drahý sken). */
    private int returnScanTicks;
    /** Rozpočet přiblížení k jednomu cíli těžby (nedosažitelné se vzdávají). */
    private int targetTicks;
    /** Rozpočet cesty k jedné truhle. */
    private int lootTicks;
    /** Truhly, ze kterých loot jednou nic nedal – druhý neúspěch = konec. */
    private final Set<Long> lootMisses = new HashSet<>();
    /** Nedosažitelné cíle těžby (glowstone u stropu…) – tenhle trip už ne. */
    private final Set<Long> failedTargets = new HashSet<>();
    /** Opakované pokusy o jeden blok rámu (pokládka se ověřuje světem). */
    private int buildRetries;
    /** Dopíjení lektvaru před sestupem (ticky). */
    private int drinkTicks;

    // Kolonizace: předsunutá základna u netherské strany portálu.
    private boolean outpostChecked;
    private BlockPos outpost;
    private StationPlacement outpostPlacement;
    private boolean depositMode;
    private boolean depositDone;

    /** Cache připravenosti – utility běží každý rozhodovací tick fleet-wide. */
    private NetherReadiness cachedReadiness;
    private long readinessAtMs;

    private int cooldownTicks;

    /**
     * @param chests sdílená stanice truhel (loot bastionů a pevností)
     */
    public NetherGoal(ChestStation chests) {
        super("nether");
        this.chests = chests;
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        WorldDimension dimension = ctx.dimension();
        // V Netheru se výprava vždy dokončí (i s vypnutým configem se musí
        // bot umět vrátit domů). V Endu tenhle cíl neběží: DimensionPolicy
        // ho tam nuluje a návrat z Endu vlastní pravidla řeší `end-return`
        // (výstupní portál existuje až po drakovi – nether logika by tam
        // marně hledala symetrický portál).
        if (dimension == WorldDimension.NETHER) {
            return IN_NETHER_UTILITY;
        }
        if (!ctx.config().nether().enabled() || dimension != WorldDimension.OVERWORLD) {
            return 0;
        }
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        var snapshot = ctx.serverView().latest();
        if (snapshot == null) {
            return 0;
        }
        // Na výpravu se nevyráží polomrtvý ani hladový – jinak bot pendluje
        // mezi dimenzemi na pár HP (návratové prahy jsou 8 HP / 6 hladu).
        if (!expeditionFit(ctx)) {
            return 0;
        }
        int minTier = ctx.config().nether().minGearTier();
        NetherReadiness readiness = readiness(ctx);
        if (!readiness.gearReady(minTier)) {
            return 0;
        }
        if (!knownPortalNearby(ctx, bot)
                && !(ctx.config().nether().buildPortals() && readiness.canBuildPortal())) {
            return 0;
        }
        double courage = bot.personality().trait(Trait.COURAGE);
        double greed = bot.personality().trait(Trait.GREED);
        double caution = bot.personality().trait(Trait.CAUTION);
        return 7 + courage * 14 + greed * 6 - caution * 5;
    }

    @Override
    public void start(Bot bot) {
        BotContext ctx = ctx(bot);
        resetTransients();
        if (ctx.dimension() == WorldDimension.NETHER) {
            // Restart/přerušení uprostřed výpravy: dodělat práci a vrátit se.
            tripDeadlineMs = System.currentTimeMillis()
                    + ctx.config().nether().maxTripMinutes() * 60_000L / 2;
            digBudget = DIG_BUDGET / 2;
            phase = Phase.WORK;
            return;
        }
        if (ctx.dimension() == WorldDimension.END) {
            // V Endu tenhle cíl nemá co dělat (viz utility) – kdyby ho tam
            // někdo vynutil, hned skončí a předá velení End cílům.
            phase = Phase.DONE;
            cooldownTicks = 1200;
            return;
        }
        phase = Phase.PREPARE;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        if (ctx.worldView() == null) {
            return;
        }
        switch (phase) {
            case PREPARE -> tickPrepare(ctx, bot);
            case FIND_PORTAL -> tickFindPortal(ctx, bot);
            case GO -> tickGo(ctx);
            case BUILD -> tickBuild(ctx);
            case LIGHT -> tickLight(ctx, bot);
            case ENTER -> tickEnter(ctx, bot);
            case WORK -> tickWork(ctx, bot);
            case RETURN -> tickReturn(ctx, bot);
            case DONE -> {
                // finished() ukončí
            }
        }
    }

    // ==================================================================
    // Cesta k portálu (overworld)
    // ==================================================================

    private void tickPrepare(BotContext ctx, Bot bot) {
        if (ctx.rng().chance(0.5)) {
            ctx.chat().sayFrom(PhraseCategory.NETHER_DEPART, null);
        }
        phase = Phase.FIND_PORTAL;
    }

    private void tickFindPortal(BotContext ctx, Bot bot) {
        WorldView world = ctx.worldView();
        BlockPos feet = ctx.position().toBlockPos();

        // 1) Zapamatovaný portál v tomto světě – jen takový, který vede do
        // Netheru („to" z průchodu). End portál by bota poslal do Endu.
        Optional<MemoryRecord> remembered = nearestNetherPortalMemory(bot, world, feet);
        if (remembered.isPresent()
                && remembered.get().distanceSquared(feet.x(), feet.y(), feet.z()) < 300 * 300) {
            MemoryRecord r = remembered.get();
            portalEntry = new BlockPos(r.x(), r.y(), r.z());
            goTo(portalEntry, Phase.ENTER);
            return;
        }

        // 2) Aktivní nether portál v okolí (End portál by bota poslal do Endu).
        Optional<BlockPos> active = PortalScanner.findActivePortal(world, feet, 16, 8,
                Material.NETHER_PORTAL);
        if (active.isPresent()) {
            portalEntry = active.get();
            goTo(portalEntry, Phase.ENTER);
            return;
        }

        // 3) Nezapálený rám v okolí – stačí křesadlo.
        Optional<PortalScanner.Frame> frame = PortalScanner.findFrame(world, feet, 16, 8);
        if (frame.isPresent()) {
            frameBase = frame.get().base();
            frameAxisX = frame.get().axisX();
            portalEntry = frame.get().entry();
            if (frame.get().lit()) {
                goTo(portalEntry, Phase.ENTER);
            } else {
                goTo(PortalBlueprint.standPoint(frameBase, frameAxisX), Phase.LIGHT);
            }
            return;
        }

        // 4) Stavba vlastního portálu.
        if (ctx.config().nether().buildPortals() && readiness(ctx).canBuildPortal()) {
            BlockPos site = PortalBlueprint.findBuildSite(world, feet, 24);
            if (site != null) {
                frameBase = site;
                frameAxisX = PortalBlueprint.siteUsable(world, site, true);
                portalEntry = PortalBlueprint.entryCell(frameBase);
                buildQueue = null;
                goTo(PortalBlueprint.standPoint(frameBase, frameAxisX), Phase.BUILD);
                return;
            }
        }
        finish(ctx, 4000);
    }

    /** Obecný přesun: dojdi k {@code target} a pokračuj fází {@code then}. */
    private void goTo(BlockPos target, Phase then) {
        goTarget = target;
        afterGo = then;
        goTicks = 0;
        goWorld = null;
        phase = Phase.GO;
    }

    private void tickGo(BotContext ctx) {
        if (goTarget == null) {
            phase = Phase.FIND_PORTAL;
            return;
        }
        // Bot mohl do portálu vejít už cestou (cíl navigace JE portálová
        // buňka) – přenos se pak stane bez UsePortalTask.
        if (goWorld == null) {
            goWorld = ctx.worldView().worldName();
        } else if (!goWorld.equals(ctx.worldView().worldName())) {
            goWorld = null;
            onTransited(ctx);
            return;
        }
        double distSq = goTarget.center().distanceSquared(ctx.position());
        if (distSq <= 2.5 * 2.5) {
            ctx.navigator().stop();
            phase = afterGo;
            return;
        }
        ctx.navigator().navigateTo(ctx.position(), PathGoal.near(goTarget, 1));
        // Dlouhé cesty (vzpomínka na portál přes půl mapy) potřebují čas –
        // limit je štědrý, ale konečný; selhání pathfindingu hlásí navigátor
        // asynchronně, proto nedojití hlídá jen časový rozpočet.
        if (++goTicks > 3600) {
            ctx.navigator().stop();
            if (ctx.dimension() == WorldDimension.NETHER) {
                phase = Phase.RETURN; // v Netheru se nevzdává, zkusí to jinak
            } else {
                finish(ctx, 2400);
            }
        }
    }

    // ==================================================================
    // Stavba a zapálení rámu
    // ==================================================================

    private void tickBuild(BotContext ctx) {
        WorldView world = ctx.worldView();
        if (buildQueue == null) {
            buildQueue = new ArrayDeque<>(PortalBlueprint.framePlacements(frameBase, frameAxisX));
        }
        if (placeTask != null) {
            if (placeTask.tick(ctx)) {
                placeTask = null;
                // Blok zůstává ve frontě, dokud world view nepotvrdí obsidián
                // (stejný vzor jako výkop v MineGoal) – PlaceBlockTask hlásí
                // dokončení i při tichém neúspěchu a jeden ztracený blok by
                // jinak nechal rám nedostavěný a obsidián utopený.
                if (world.materialAt(buildQueue.peek()) != Material.OBSIDIAN
                        && ++buildRetries > 2) {
                    finish(ctx, 2400);
                }
            }
            return;
        }
        // Přeskočit už hotové bloky (rozestavěný rám, opakovaný pokus).
        while (!buildQueue.isEmpty()
                && world.materialAt(buildQueue.peek()) == Material.OBSIDIAN) {
            buildQueue.poll();
            buildRetries = 0;
        }
        BlockPos next = buildQueue.peek();
        if (next == null) {
            buildQueue = null;
            if (PortalBlueprint.isFrame(world, frameBase, frameAxisX)) {
                lightAttempts = 0;
                phase = Phase.LIGHT;
            } else {
                finish(ctx, 2400); // stavba nedopadla (došel obsidián…)
            }
            return;
        }
        var snapshot = ctx.serverView().latest();
        if (snapshot == null || !ctx.inventory().equipItem(snapshot, Material.OBSIDIAN)) {
            finish(ctx, 2400); // bez obsidiánu nemá cenu pokračovat
            return;
        }
        placeTask = new PlaceBlockTask(next);
    }

    private void tickLight(BotContext ctx, Bot bot) {
        WorldView world = ctx.worldView();
        if (PortalBlueprint.isLit(world, frameBase, frameAxisX)) {
            // Vlastní/nalezený portál si bot zapamatuje hned – vzpomínka
            // z průchodu by vznikla až při transitu.
            bot.memory().remember(MemoryKind.PORTAL, world.worldName(),
                    portalEntry.x(), portalEntry.y(), portalEntry.z(), null,
                    Map.of("built", "true"), 0.8);
            enterAttempts = 0;
            goTo(portalEntry, Phase.ENTER);
            return;
        }
        if (lightTicks > 0) {
            lightTicks--;
            return;
        }
        if (++lightAttempts > 4) {
            finish(ctx, 2400);
            return;
        }
        var snapshot = ctx.serverView().latest();
        if (snapshot == null || !ctx.inventory().equipItem(snapshot, Material.FLINT_AND_STEEL)) {
            finish(ctx, 2400);
            return;
        }
        BlockPos ignite = PortalBlueprint.igniteSupport(frameBase);
        ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0), ignite.center().add(0, 0.5, 0));
        ctx.actions().useItemOn(ignite, Direction.UP);
        lightTicks = 15;
    }

    // ==================================================================
    // Průchod portálem
    // ==================================================================

    private void tickEnter(BotContext ctx, Bot bot) {
        if (enterTask == null) {
            // Vstup mohl být z paměti – ověřit, že tam nether portál
            // skutečně je (materiálem, ne traitem – trait by uznal i End).
            WorldView world = ctx.worldView();
            if (world.materialAt(portalEntry) != Material.NETHER_PORTAL) {
                Optional<BlockPos> near = PortalScanner.findActivePortal(world, portalEntry, 6, 4,
                        Material.NETHER_PORTAL);
                if (near.isPresent()) {
                    portalEntry = near.get();
                } else if (world.isAvailable(portalEntry)
                        && world.materialAt(portalEntry) != null) {
                    // Portál zmizel (rozbitý/zhasnutý) – vzpomínka je mrtvá.
                    bot.memory().forgetIf(MemoryKind.PORTAL, r ->
                            r.world().equals(world.worldName())
                                    && r.distanceSquared(portalEntry.x(), portalEntry.y(),
                                            portalEntry.z()) < 6 * 6);
                    phase = ctx.dimension() == WorldDimension.NETHER
                            ? Phase.RETURN : Phase.FIND_PORTAL;
                    return;
                }
            }
            enterTask = new UsePortalTask(portalEntry);
        }
        if (!enterTask.tick(ctx)) {
            // Task se pohybuje sám (vejít do portálu) – vstup se musí předat.
            ctx.requestMove(enterTask.move());
            return;
        }
        boolean transited = enterTask.transited();
        enterTask = null;
        if (!transited) {
            if (++enterAttempts > 2) {
                if (ctx.dimension() == WorldDimension.NETHER) {
                    enterAttempts = 0;
                    phase = Phase.RETURN;
                } else {
                    finish(ctx, 2400);
                }
                return;
            }
            goTo(portalEntry, Phase.ENTER);
            return;
        }
        onTransited(ctx);
    }

    /** Po průchodu: podle nové dimenze začíná práce, nebo končí výprava. */
    private void onTransited(BotContext ctx) {
        if (ctx.dimension() == WorldDimension.NETHER) {
            tripDeadlineMs = System.currentTimeMillis()
                    + ctx.config().nether().maxTripMinutes() * 60_000L;
            digBudget = DIG_BUDGET;
            barterCount = 0;
            bootTries = 0;
            lootedChests.clear();
            lootMisses.clear();
            failedTargets.clear();
            structuresRemembered = false;
            if (ctx.rng().chance(0.7)) {
                ctx.chat().sayFrom(PhraseCategory.NETHER_ARRIVE, null);
            }
            phase = Phase.WORK;
            return;
        }
        if (ctx.dimension() == WorldDimension.END) {
            // Cizí portál vedl do Endu – výpravu ukončit a předat velení End
            // cílům (`end-return` má bazální chuť domů, `dragon-fight` boj).
            finish(ctx, 2400);
            return;
        }
        // Zpátky v overworldu – výprava končí.
        if (ctx.rng().chance(0.8)) {
            ctx.chat().sayFrom(PhraseCategory.NETHER_RETURN, null);
        }
        finish(ctx, ctx.rng().rangeInt(12_000, 24_000));
    }

    // ==================================================================
    // Práce v Netheru
    // ==================================================================

    private void tickWork(BotContext ctx, Bot bot) {
        if (ctx.dimension() != WorldDimension.NETHER) {
            // Někdo bota přenesl domů (teleport, smrt řeší respawn) – konec.
            finish(ctx, 6000);
            return;
        }
        if (shouldReturn(ctx)) {
            ctx.navigator().stop();
            phase = Phase.RETURN;
            return;
        }
        wearGoldenBoots(ctx);
        if (drinkTicks > 0) {
            drinkTicks--; // bot dopíjí lektvar – stojí a drží use
            return;
        }

        // Rozdělaná práce má přednost (pořadí dle MineGoal).
        if (mineTask != null) {
            if (mineTask.tick(ctx)) {
                mineTask = null;
                onBlockMined(ctx, bot);
            }
            return;
        }
        if (placeTask != null) {
            if (placeTask.tick(ctx)) {
                placeTask = null;
            }
            return;
        }
        if (pendingWalk != null && tickWalk(ctx)) {
            return;
        }
        if (!stepBlocks.isEmpty() || !digSteps.isEmpty()) {
            tickDig(ctx);
            return;
        }
        if (lootChest != null) {
            tickLoot(ctx, bot);
            return;
        }
        if (barterWaitTicks > 0) {
            tickBarterWait(ctx);
            return;
        }
        if (outpostPlacement != null) {
            tickOutpostPlacement(ctx, bot);
            return;
        }
        // Toulání dokončit dřív, než se znovu skenuje – a mezi skeny držet
        // rozestup (plný sken rud a truhel každý tick by žral tick rozpočet).
        if (wanderTarget != null) {
            tickWander(ctx);
            return;
        }
        if (scanCooldownTicks > 0) {
            scanCooldownTicks--;
            return;
        }
        acquireWork(ctx, bot);
    }

    /** Návratové podmínky: čas, zdraví, hlad, dost kořisti. */
    private boolean shouldReturn(BotContext ctx) {
        if (System.currentTimeMillis() >= tripDeadlineMs) {
            return true;
        }
        var snapshot = ctx.serverView().latest();
        if (snapshot == null) {
            return false;
        }
        if (snapshot.health() < 8.0 || snapshot.foodLevel() < 6) {
            return true;
        }
        int debris = dev.botalive.core.inventory.InventoryHelper.countEstimate(snapshot,
                m -> m == Material.ANCIENT_DEBRIS);
        int quartz = dev.botalive.core.inventory.InventoryHelper.countEstimate(snapshot,
                m -> m == Material.QUARTZ);
        return debris >= 4 || quartz >= 32;
    }

    /** Zlaté boty v Netheru: piglini nechají bota na pokoji. */
    private void wearGoldenBoots(BotContext ctx) {
        if (bootTries >= 2) {
            return;
        }
        var snapshot = ctx.serverView().latest();
        if (snapshot == null) {
            return;
        }
        Material boots = snapshot.armor().length > 0 ? snapshot.armor()[0] : null;
        if (boots == Material.GOLDEN_BOOTS
                || !snapshot.hasItem(m -> m == Material.GOLDEN_BOOTS)) {
            bootTries = 2;
            return;
        }
        bootTries++;
        if (ctx.inventory().equipItem(snapshot, Material.GOLDEN_BOOTS)) {
            ctx.actions().useItem(ctx.humanizer().yaw(), ctx.humanizer().pitch());
        }
    }

    /**
     * Základna: existující OUTPOST vzpomínka v tomto světě, jinak položení
     * vlastní truhly u příletového portálu (kolonizace – bot se sem vrací
     * stejným portálem díky PORTAL paměti).
     */
    private void resolveOutpost(BotContext ctx, Bot bot) {
        WorldView world = ctx.worldView();
        BlockPos feet = ctx.position().toBlockPos();
        var remembered = bot.memory().recallNearest(MemoryKind.OUTPOST,
                world.worldName(), feet.x(), feet.y(), feet.z());
        if (remembered.isPresent()
                && remembered.get().distanceSquared(feet.x(), feet.y(), feet.z()) < 96 * 96) {
            var r = remembered.get();
            outpost = new BlockPos(r.x(), r.y(), r.z());
            lootedChests.add(outpost.asLong()); // vlastní truhla se nevylupuje
            outpostChecked = true;
            return;
        }
        var snapshot = ctx.serverView().latest();
        if (snapshot == null || !snapshot.hasItem(m -> m == Material.CHEST)) {
            outpostChecked = true; // bez truhly se kolonizuje až příště
            return;
        }
        outpostPlacement = new StationPlacement(Material.CHEST);
    }

    /** Dotáhne položení truhly základny a zapamatuje ji. */
    private void tickOutpostPlacement(BotContext ctx, Bot bot) {
        if (outpostPlacement.tick(ctx)) {
            return;
        }
        outpostPlacement = null;
        outpostChecked = true;
        WorldView world = ctx.worldView();
        BlockPos feet = ctx.position().toBlockPos();
        for (int dx = -3; dx <= 3 && outpost == null; dx++) {
            for (int dy = -2; dy <= 2 && outpost == null; dy++) {
                for (int dz = -3; dz <= 3; dz++) {
                    BlockPos pos = feet.offset(dx, dy, dz);
                    if (world.materialAt(pos) == Material.CHEST) {
                        outpost = pos;
                        break;
                    }
                }
            }
        }
        if (outpost != null) {
            bot.memory().remember(MemoryKind.OUTPOST, world.worldName(),
                    outpost.x(), outpost.y(), outpost.z(), null,
                    Map.of("type", "chest"), 0.8);
            lootedChests.add(outpost.asLong()); // vlastní truhla se nevylupuje
            if (ctx.rng().chance(0.7)) {
                ctx.chat().say("zakladam si tu malou zakladnu, at se sem mam kam vracet");
            }
        }
    }

    /**
     * Preventivní odolnost ohni z hotbaru (splash hned, láhev ~1,6 s).
     * Lektvar zastrčený v batohu se neřeší – to umí nouzový cíl „drink".
     *
     * @return {@code true} pokud se pije/hodilo (tick je spotřebovaný)
     */
    private boolean drinkFireResistance(BotContext ctx) {
        if (ctx.clientState().effectActive(Effect.FIRE_RESISTANCE)) {
            return false;
        }
        var snapshot = ctx.serverView().latest();
        int drink = ItemVariants.findPotionSlot(snapshot, ItemVariants.FIRE_RESISTANCE);
        int splash = ItemVariants.findSplashSlot(snapshot, ItemVariants.FIRE_RESISTANCE);
        int slot = drink >= 0 && drink < 9 ? drink : splash;
        if (slot < 0 || slot >= 9) {
            return false;
        }
        boolean throwIt = slot == splash && slot != drink;
        ctx.navigator().stop();
        ctx.actions().selectHotbar(slot);
        ctx.actions().useItem(ctx.humanizer().yaw(),
                throwIt ? 90f : ctx.humanizer().pitch());
        drinkTicks = throwIt ? 10 : 40;
        return true;
    }

    /** Výběr další práce: ruda → truhla → barter → schodiště za troskami → toulání. */
    private void acquireWork(BotContext ctx, Bot bot) {
        WorldView world = ctx.worldView();
        BlockPos feet = ctx.position().toBlockPos();
        BotNeeds needs = BotNeeds.assess(ctx.serverView().latest());

        // 0) Kolonizace: základna u portálu (jednou) + odkládání balastu.
        if (!outpostChecked) {
            resolveOutpost(ctx, bot);
            if (outpostPlacement != null) {
                return;
            }
        }
        if (outpost != null && !depositDone
                && dev.botalive.core.inventory.InventoryHelper.countEstimate(
                        ctx.serverView().latest(),
                        dev.botalive.core.inventory.ContainerService::isJunk) >= 48
                && feet.distanceSquared(outpost) < 48 * 48) {
            depositMode = true;
            lootChest = outpost;
            lootFuture = null;
            lootTicks = 0;
            return;
        }

        // 1) Viditelná kořist (nejcennější, kterou aktuální krumpáč vytěží).
        BlockPos ore = scanBestOre(world, feet, needs);
        if (ore != null) {
            targetBlock = ore;
            targetMaterial = world.materialAt(ore);
            targetTicks = 0;
            approachAndMine(ctx);
            return;
        }

        // 2) Truhly struktur (šablony!) + zapamatování pevnosti/bastionu.
        BlockPos chest = scanChest(world, feet);
        if (chest != null) {
            rememberStructure(ctx, bot, chest);
            lootChest = chest;
            lootFuture = null;
            lootTicks = 0;
            return;
        }

        // 3) Barter s pigliny (jen v klidu, se zlatem a zlatými botami).
        if (tryStartBarter(ctx)) {
            return;
        }

        // 4) Schodiště do hloubky trosek (jen s diamantovým krumpáčem).
        if (ctx.config().ai().terraforming() && needs.pickaxeTier() >= 5
                && feet.y() > DEBRIS_Y + 6 && digBudget > 8 && ctx.rng().chance(0.5)) {
            // Před sestupem preventivně odolnost ohni (barterová klasika) –
            // v hloubce trosek jsou lávové kapsy nejzrádnější. Nouzi za pochodu
            // řeší cíl „drink"; tady jde o rozvahu hráče před kopáním.
            if (drinkFireResistance(ctx)) {
                return;
            }
            digSteps.addAll(DigPlanner.staircase(feet,
                    Math.max(DEBRIS_Y, feet.y() - 20), ctx.rng().rangeInt(0, 4), 20));
            return;
        }

        // 5) Toulání – nová krajina, nové žíly; další sken až po přesunu.
        scanCooldownTicks = 30;
        tickWander(ctx);
    }

    /** Nejcennější vytěžitelná ruda v okolí (exponovaná, r=10). */
    private BlockPos scanBestOre(WorldView world, BlockPos center, BotNeeds needs) {
        BlockPos best = null;
        double bestScore = 0;
        for (int dx = -10; dx <= 10; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -10; dz <= 10; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    Material material = world.materialAt(pos);
                    Double value = material == null ? null : NETHER_ORES.get(material);
                    if (value == null || !needs.canHarvest(material)
                            || failedTargets.contains(pos.asLong())
                            || !DigPlanner.isExposed(world, pos)) {
                        continue;
                    }
                    double score = value / (1 + Math.sqrt(center.distanceSquared(pos)));
                    if (score > bestScore) {
                        bestScore = score;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    /** Nejbližší nevyloupená truhla v okolí (struktury: pevnosti, bastiony). */
    private BlockPos scanChest(WorldView world, BlockPos center) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -12; dx <= 12; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -12; dz <= 12; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (world.materialAt(pos) != Material.CHEST
                            || lootedChests.contains(pos.asLong())) {
                        continue;
                    }
                    double dist = center.distanceSquared(pos);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    /** Nether brick/blackstone kolem truhly → FORTRESS/BASTION vzpomínka. */
    private void rememberStructure(BotContext ctx, Bot bot, BlockPos around) {
        if (structuresRemembered) {
            return;
        }
        WorldView world = ctx.worldView();
        int bricks = 0;
        int blackstone = 0;
        for (int dx = -6; dx <= 6; dx += 2) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -6; dz <= 6; dz += 2) {
                    Material material = world.materialAt(around.offset(dx, dy, dz));
                    if (material == Material.NETHER_BRICKS) {
                        bricks++;
                    } else if (material != null && material.name().contains("BLACKSTONE")) {
                        blackstone++;
                    }
                }
            }
        }
        MemoryKind kind = bricks >= 8 ? MemoryKind.FORTRESS
                : blackstone >= 8 ? MemoryKind.BASTION : null;
        if (kind != null) {
            structuresRemembered = true;
            bot.memory().remember(kind, world.worldName(),
                    around.x(), around.y(), around.z(), null, Map.of(), 0.75);
        }
    }

    /** Přiblížení k cíli těžby; u cíle spustit kopání. */
    private void approachAndMine(BotContext ctx) {
        double distSq = targetBlock.center().distanceSquared(ctx.position());
        if (distSq > 4.2 * 4.2) {
            ctx.navigator().navigateTo(ctx.position(), PathGoal.near(targetBlock, 2));
            // Nedosažitelné cíle (ruda za lávovým jezerem, glowstone u stropu)
            // hlídá časový rozpočet – navigátor selhání hlásí asynchronně, ne
            // stavem – a vzdaný cíl jde na černou listinu, jinak by ho další
            // sken vybral znovu a bot u něj protočil celý trip.
            if (++targetTicks > 300) {
                failedTargets.add(targetBlock.asLong());
                targetBlock = null;
            }
            return;
        }
        ctx.navigator().stop();
        if (DigPlanner.unsafeToBreak(ctx.worldView(), targetBlock)) {
            failedTargets.add(targetBlock.asLong());
            targetBlock = null;
            return;
        }
        ctx.inventory().equipBestTool(ctx.serverView().latest(),
                targetMaterial != null ? targetMaterial : Material.NETHERRACK);
        mineTask = new MineBlockTask(targetBlock);
    }

    /** Po vytěžení: žíla, ekonomika (hodnotu má hlavně debris). */
    private void onBlockMined(BotContext ctx, Bot bot) {
        ctx.stats().addMined();
        // Netherová těžba se propisuje do ekonomiky jako overworldová
        // (MineGoal) – jinak by kopáč v pekle „nevydělával".
        Double value = targetMaterial == null ? null : NETHER_ORES.get(targetMaterial);
        if (value != null && ctx.config().economy().enabled() && !digTaskInPlan) {
            bot.wallet().deposit(value,
                    "těžba " + targetMaterial.name().toLowerCase(java.util.Locale.ROOT));
        }
        if (targetMaterial != null && targetMaterial == Material.ANCIENT_DEBRIS
                && ctx.rng().chance(0.8)) {
            ctx.chat().sayFrom(PhraseCategory.NETHER_LOOT, null);
        }
        // Bloky z plánu výkopu (schodiště) žílu nesledují – v Netheru je
        // vedle VŽDY další netherrack a řetězení by obešlo digBudget
        // (stejná pojistka „valuable" jako v MineGoal).
        boolean plannedBlock = digTaskInPlan;
        digTaskInPlan = false;
        if (!plannedBlock && targetBlock != null && targetMaterial != null
                && NETHER_ORES.containsKey(targetMaterial)) {
            // Krátké sledování žíly (quartz bývá v hnízdech).
            WorldView world = ctx.worldView();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -1; dz <= 1; dz++) {
                        BlockPos next = targetBlock.offset(dx, dy, dz);
                        if (world.materialAt(next) == targetMaterial) {
                            targetBlock = next;
                            targetTicks = 0;
                            approachAndMine(ctx);
                            return;
                        }
                    }
                }
            }
        }
        targetBlock = null;
    }

    // ---- výkop (zrcadlí MineGoal, bez pochodní – Nether nespawnuje po tmě)

    private void tickDig(BotContext ctx) {
        WorldView world = ctx.worldView();
        if (stepBlocks.isEmpty()) {
            DigPlanner.Step step = digSteps.poll();
            if (step == null) {
                return;
            }
            if (!DigPlanner.hasFloorBelow(world, step.feet(), 3)) {
                abortDig();
                return;
            }
            stepBlocks.addAll(step.toBreak());
            pendingWalkAfterStep = step.feet();
        }
        BlockPos block = stepBlocks.peek();
        if (block == null) {
            return;
        }
        if (world.traitsAt(block).passable()) {
            stepBlocks.poll();
            if (stepBlocks.isEmpty()) {
                pendingWalk = pendingWalkAfterStep;
                walkTicks = 0;
                pendingWalkAfterStep = null;
            }
            return;
        }
        if (DigPlanner.unsafeToBreak(world, block)) {
            abortDig();
            if (ctx.rng().chance(0.4)) {
                ctx.chat().say("lava hned vedle, tudy ne");
            }
            return;
        }
        if (digBudget-- <= 0) {
            abortDig();
            return;
        }
        Material material = world.materialAt(block);
        ctx.inventory().equipBestTool(ctx.serverView().latest(),
                material != null ? material : Material.NETHERRACK);
        targetMaterial = material;
        targetBlock = block;
        mineTask = new MineBlockTask(block);
        digTaskInPlan = true;
    }

    private boolean tickWalk(BotContext ctx) {
        Vec3 pos = ctx.position();
        if (pos.toBlockPos().distanceSquared(pendingWalk) <= 2) {
            pendingWalk = null;
            ctx.navigator().stop();
            return false;
        }
        if (++walkTicks > 100) {
            pendingWalk = null;
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
    }

    // ---- loot truhel

    private void tickLoot(BotContext ctx, Bot bot) {
        if (lootFuture != null) {
            if (!lootFuture.isDone()) {
                return;
            }
            Integer taken = lootFuture.getNow(0);
            lootFuture = null;
            ctx.actions().closeContainer();
            if (depositMode) {
                // Odkládání balastu do vlastní základny – žádná černá listina.
                depositMode = false;
                depositDone = true;
                lootChest = null;
                return;
            }
            // Úspěch (nebo druhý prázdný pokus) truhlu uzavírá; jeden prázdný
            // výsledek může být i timeout/plný batoh – dostane druhou šanci.
            if (taken != null && taken > 0) {
                lootedChests.add(lootChest.asLong());
                if (ctx.rng().chance(0.6)) {
                    ctx.chat().sayFrom(PhraseCategory.NETHER_LOOT, null);
                }
            } else if (!lootMisses.add(lootChest.asLong())) {
                lootedChests.add(lootChest.asLong());
            }
            lootChest = null;
            return;
        }
        // Nedosažitelnou truhlu (za lávou) hlídá časový rozpočet.
        if (++lootTicks > 300) {
            if (depositMode) {
                // Vlastní základna se neblacklistuje – jen to dnes nevyšlo;
                // režim se MUSÍ vypnout, jinak by další strukturální truhla
                // dostala depositJunk místo vyloupení.
                depositMode = false;
                depositDone = true;
            } else {
                lootedChests.add(lootChest.asLong());
            }
            lootChest = null;
            return;
        }
        double distSq = lootChest.center().distanceSquared(ctx.position());
        if (distSq > 3.0 * 3.0) {
            ctx.navigator().navigateTo(ctx.position(), PathGoal.near(lootChest, 2));
            return;
        }
        ctx.navigator().stop();
        ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0),
                lootChest.center().add(0, 0.5, 0));
        ctx.actions().useItemOn(lootChest, Direction.UP);
        lootFuture = depositMode
                ? chests.depositJunk(ctx, ctx.worldView().worldName(), lootChest)
                : chests.lootValuables(ctx, ctx.worldView().worldName(), lootChest);
    }

    // ---- barter s pigliny

    private boolean tryStartBarter(BotContext ctx) {
        if (!ctx.config().nether().barter() || barterCount >= BARTER_LIMIT
                || !ctx.rng().chance(0.35)) {
            return false;
        }
        var snapshot = ctx.serverView().latest();
        if (snapshot == null
                || (snapshot.armor().length > 0 && snapshot.armor()[0] != Material.GOLDEN_BOOTS)
                || !snapshot.hasItem(m -> m == Material.GOLD_INGOT)) {
            return false;
        }
        var piglin = ctx.entities().nearest(ctx.position(), 12,
                e -> e.type() == EntityType.PIGLIN);
        if (piglin.isEmpty()) {
            return false;
        }
        Vec3 target = piglin.get().position();
        double distSq = target.distanceSquared(ctx.position());
        if (distSq > 4 * 4) {
            ctx.navigator().navigateTo(ctx.position(), PathGoal.near(target.toBlockPos(), 2));
            return true;
        }
        ctx.navigator().stop();
        if (!ctx.inventory().equipItem(snapshot, Material.GOLD_INGOT)) {
            return false;
        }
        ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0), target.add(0, 1.5, 0));
        ctx.actions().dropItem();
        barterCount++;
        barterWaitTicks = 170; // piglin ingot zkoumá ~6 s, pak upustí zboží
        barterSpot = target;
        return true;
    }

    private void tickBarterWait(BotContext ctx) {
        barterWaitTicks--;
        if (barterWaitTicks > 0) {
            return;
        }
        // Dojít si pro zboží (upadlo poblíž piglina; sběr je vanilla pickup).
        if (barterSpot != null) {
            ctx.navigator().navigateTo(ctx.position(), barterSpot.toBlockPos());
            barterSpot = null;
        }
    }

    // ---- toulání

    private void tickWander(BotContext ctx) {
        if (wanderTarget != null) {
            double distSq = wanderTarget.center().distanceSquared(ctx.position());
            if (distSq < 3 * 3 || ++wanderTicks > 500
                    || (!ctx.navigator().navigating() && wanderTicks > 60)) {
                wanderTarget = null;
                return;
            }
            ctx.navigator().navigateTo(ctx.position(), wanderTarget);
            return;
        }
        double angle = ctx.rng().range(0, Math.PI * 2);
        int distance = ctx.rng().rangeInt(24, 40);
        BlockPos feet = ctx.position().toBlockPos();
        BlockPos raw = feet.offset((int) (Math.cos(angle) * distance), 0,
                (int) (Math.sin(angle) * distance));
        wanderTarget = new BlockPos(raw.x(), NetherMath.clampNetherY(raw.y()), raw.z());
        wanderTicks = 0;
    }

    // ==================================================================
    // Návrat domů
    // ==================================================================

    private void tickReturn(BotContext ctx, Bot bot) {
        if (ctx.dimension() == WorldDimension.OVERWORLD) {
            finish(ctx, 6000);
            return;
        }
        // Hledání cesty domů je drahé (velký sken) – mezi pokusy se bot
        // toulá a rozhlíží; sken se opakuje až po rozestupu.
        if (returnScanTicks > 0) {
            returnScanTicks--;
            tickWander(ctx);
            return;
        }
        WorldView world = ctx.worldView();
        BlockPos feet = ctx.position().toBlockPos();

        // 1) Portál, kterým bot přišel (vzpomínka příletu).
        Optional<MemoryRecord> remembered = bot.memory().recallNearest(
                MemoryKind.PORTAL, world.worldName(), feet.x(), feet.y(), feet.z());
        if (remembered.isPresent()) {
            MemoryRecord r = remembered.get();
            portalEntry = new BlockPos(r.x(), r.y(), r.z());
            enterAttempts = 0;
            returnScanTicks = 100;
            goTo(portalEntry, Phase.ENTER);
            return;
        }

        // 2) Aktivní nether portál v okolí.
        Optional<BlockPos> active = PortalScanner.findActivePortal(world, feet, 16, 8,
                Material.NETHER_PORTAL);
        if (active.isPresent()) {
            portalEntry = active.get();
            enterAttempts = 0;
            returnScanTicks = 100;
            goTo(portalEntry, Phase.ENTER);
            return;
        }

        // 3) Vlastní portál z kořisti (obsidián z bastionů/barteru).
        if (readiness(ctx).canBuildPortal()) {
            BlockPos site = PortalBlueprint.findBuildSite(world, feet, 16);
            if (site != null) {
                frameBase = site;
                frameAxisX = PortalBlueprint.siteUsable(world, site, true);
                portalEntry = PortalBlueprint.entryCell(frameBase);
                buildQueue = null;
                goTo(PortalBlueprint.standPoint(frameBase, frameAxisX), Phase.BUILD);
                return;
            }
        }

        // 4) K bodu odpovídajícímu domovu (overworld/8) a hledat cestou.
        returnScanTicks = 80;
        BlockPos anchor = overworldAnchor(bot, world.worldName());
        if (anchor != null && feet.distanceSquared(anchor) > 12 * 12) {
            goTo(anchor, Phase.RETURN);
            return;
        }
        tickWander(ctx); // u kotvy nic – rozhlížet se po okolí
    }

    /** Bod v Netheru odpovídající domovu v overworldu (x/8, z/8). */
    private BlockPos overworldAnchor(Bot bot, String netherWorld) {
        for (MemoryRecord home : bot.memory().recall(MemoryKind.HOME)) {
            if (!netherWorld.equals(home.world())) {
                return NetherMath.toNether(new BlockPos(home.x(), home.y(), home.z()));
            }
        }
        return null;
    }

    // ==================================================================
    // Životní cyklus
    // ==================================================================

    private void finish(BotContext ctx, int cooldown) {
        cooldownTicks = cooldown;
        phase = Phase.DONE;
    }

    private void resetTransients() {
        afterGo = null;
        goTarget = null;
        buildQueue = null;
        placeTask = null;
        enterTask = null;
        mineTask = null;
        targetBlock = null;
        pendingWalk = null;
        pendingWalkAfterStep = null;
        lootChest = null;
        lootFuture = null;
        wanderTarget = null;
        barterSpot = null;
        barterWaitTicks = 0;
        digSteps.clear();
        stepBlocks.clear();
        lootedChests.clear();
        lootMisses.clear();
        failedTargets.clear();
        lightAttempts = 0;
        enterAttempts = 0;
        bootTries = 0;
        buildRetries = 0;
        scanCooldownTicks = 0;
        returnScanTicks = 0;
        targetTicks = 0;
        lootTicks = 0;
        drinkTicks = 0;
        digTaskInPlan = false;
        outpostChecked = false;
        outpost = null;
        outpostPlacement = null;
        depositMode = false;
        depositDone = false;
    }

    @Override
    public void stop(Bot bot) {
        BotContext ctx = ctx(bot);
        if (mineTask != null) {
            mineTask.cancel(ctx);
            mineTask = null;
        }
        if (placeTask != null) {
            placeTask.cancel(ctx);
            placeTask = null;
        }
        if (enterTask != null) {
            enterTask.cancel(ctx);
            enterTask = null;
        }
        digSteps.clear();
        stepBlocks.clear();
        super.stop(bot);
    }

    @Override
    public boolean finished(Bot bot) {
        return phase == Phase.DONE;
    }

    @Override
    public boolean blocksRelocation() {
        return true; // uprostřed výpravy se nerozhoduje o stěhování vesnice
    }

    /** Zná bot použitelný nether portál v tomto světě? (dotaz do paměti) */
    private boolean knownPortalNearby(BotContext ctx, Bot bot) {
        WorldView world = ctx.worldView();
        if (world == null) {
            return false;
        }
        BlockPos feet = ctx.position().toBlockPos();
        return nearestNetherPortalMemory(bot, world, feet)
                .filter(r -> r.distanceSquared(feet.x(), feet.y(), feet.z()) < 300 * 300)
                .isPresent();
    }

    /**
     * Nejbližší PORTAL vzpomínka v daném světě, která vede do Netheru –
     * end portál nebo neoznačený záznam by výpravu poslal jinam.
     */
    private Optional<MemoryRecord> nearestNetherPortalMemory(Bot bot, WorldView world,
                                                             BlockPos feet) {
        MemoryRecord best = null;
        double bestDist = Double.MAX_VALUE;
        for (MemoryRecord r : bot.memory().recall(MemoryKind.PORTAL)) {
            if (!world.worldName().equals(r.world())) {
                continue;
            }
            String to = r.data() == null ? null : r.data().get("to");
            // Vlastnoručně postavené portály ("built") vedou do Netheru vždy;
            // u průchodů rozhoduje dimenze cílového světa.
            boolean leadsToNether = (r.data() != null && r.data().containsKey("built"))
                    || (to != null && WorldDimension.fromWorldKey(to) == WorldDimension.NETHER);
            if (!leadsToNether) {
                continue;
            }
            double dist = r.distanceSquared(feet.x(), feet.y(), feet.z());
            if (dist < bestDist) {
                bestDist = dist;
                best = r;
            }
        }
        return Optional.ofNullable(best);
    }

    /** Připravenost s krátkou cache – utility ji čte každý rozhodovací tick. */
    private NetherReadiness readiness(BotContext ctx) {
        long now = System.currentTimeMillis();
        if (cachedReadiness == null || now - readinessAtMs > 2_000) {
            cachedReadiness = NetherReadiness.assess(ctx.serverView().latest(),
                    ctx.config().nether().minGearTier());
            readinessAtMs = now;
        }
        return cachedReadiness;
    }

    @Override
    public String explain(Bot bot) {
        return switch (phase) {
            case PREPARE, FIND_PORTAL -> "chystám se na výpravu do Netheru";
            case GO -> afterGo == Phase.ENTER ? "mířím k portálu do Netheru"
                    : "jdu na místo pro portál";
            case BUILD -> "stavím portál do Netheru";
            case LIGHT -> "zapaluju portál křesadlem";
            case ENTER -> "procházím portálem";
            case WORK -> targetMaterial == Material.ANCIENT_DEBRIS
                    ? "těžím starodávné trosky v Netheru"
                    : "sbírám kořist v Netheru";
            case RETURN -> "vracím se domů (hledám portál)";
            case DONE -> null;
        };
    }
}
