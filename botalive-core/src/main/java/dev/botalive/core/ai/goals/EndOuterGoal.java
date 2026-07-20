package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.ai.EndKnowledge;
import dev.botalive.core.chat.PhraseCategory;
import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.inventory.InventoryHelper;
import dev.botalive.core.pathfinding.PathGoal;
import dev.botalive.core.station.ChestStation;
import dev.botalive.core.tasks.GlideTask;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.WorldDimension;
import dev.botalive.core.world.WorldView;
import org.bukkit.Material;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/**
 * Výprava na vnější ostrovy Endu – gateway, end city, elytry.
 *
 * <p>Po skolení draka se na okraji hlavního ostrova objevují gateway
 * portály (prstenec ~96 bloků od středu). Bot s dostatkem perel k jednomu
 * dojde, prohodí perlu (server ho teleportuje na vnější ostrovy), zapamatuje
 * si zpáteční gateway a vydá se hledat end city: server-side asistencí
 * {@code locateNearestStructure} (precedent §9 – protokol tuhle informaci
 * nenese), v paketovém režimu skenem purpuru při průzkumu. Ve městě vyluští
 * truhly ({@code ChestStation}), z end ship sundá <b>elytry</b> (útok na
 * item frame + sebrání dropu) a rovnou si je oblékne. Domů se vrací
 * zpáteční gatewayí; z výšek smí slétat na elytrách ({@link GlideTask}).</p>
 *
 * <p>Shulkery cíleně neloví – hostilní obranu města řeší běžná bojová
 * mašinerie (zásah → hrozba → {@code CombatGoal}), levitaci po zásahu
 * klientská fyzika poctivě simuluje. Otevřenost/zavřenost krunýře klient
 * nečte (metadata se záměrně neparsují) – bot občas máchne do zavřeného
 * krunýře, jako začátečník.</p>
 *
 * <p>Pojistky: rezerva perel na návrat, časový rozpočet výpravy, návrat při
 * zranění nebo hladu; přerušení (boj, jídlo) stav výpravy neztrácí – fáze
 * přežívají přepnutí cílů.</p>
 */
public final class EndOuterGoal extends AbstractGoal {

    /** Poloměr prstence gateway portálů (vanilla 96 od středu). */
    private static final int GATEWAY_RING_RADIUS = 96;

    /** Za touto vzdáleností od středu už je bot „na vnějších ostrovech". */
    private static final double OUTER_THRESHOLD = 200;

    /** Detekce teleportu perlou: skok pozice o víc než tohle = průchod. */
    private static final double TRANSIT_JUMP = 180;

    /** Kolik truhel města se maximálně vybírá. */
    private static final int MAX_CHESTS = 4;

    /** Kolik pokusů průzkumu, než to bot vzdá (fallback bez locate). */
    private static final int MAX_EXPLORE_HOPS = 8;

    private enum Phase {
        IDLE, GATEWAY_FIND, GATEWAY_GO, THROW, OUTER_ORIENT, CITY_FIND,
        CITY_GO, LOOT, FRAME, COLLECT, EQUIP, HOME_GO, HOME_THROW, DONE
    }

    private Phase phase = Phase.IDLE;
    private final ChestStation chests;
    private final ScanRetry scanRetry = new ScanRetry(4, 30);

    private BlockPos gateway;
    private BlockPos returnGateway;
    private BlockPos cityPos;
    private final Deque<BlockPos> chestQueue = new ArrayDeque<>();
    private BlockPos currentChest;
    private CompletableFuture<Integer> lootFuture;
    private CompletableFuture<BlockPos> locateFuture;
    private Integer frameEntityId;
    private BlockPos framePos;
    private GlideTask glideTask;

    private Vec3 preThrowPos;
    private int throwTicks;
    private int pearlThrows;
    private int exploreHops;
    private int equipTries;
    private int waitTicks;
    private long tripDeadlineMs;
    private int cooldownTicks;

    /**
     * @param chests sdílená služba truhel (lootování end city)
     */
    public EndOuterGoal(ChestStation chests) {
        super("end-outer");
        this.chests = chests;
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        var outer = ctx.config().end().outer();
        if (!ctx.config().end().enabled() || !outer.enabled()
                || ctx.clientState().dead() || ctx.dimension() != WorldDimension.END) {
            return 0;
        }
        // Rozjetá výprava (nebo bot uvízlý na vnějších ostrovech) se dokončuje.
        if (phase != Phase.IDLE && phase != Phase.DONE) {
            return 30;
        }
        if (onOuterIslands(ctx.position())) {
            return 30; // ocitl se venku bez plánu (teleport) – cíl ho dovede domů
        }
        if (!EndKnowledge.dragonSlain(bot.memory()) || !expeditionFit(ctx)) {
            return 0;
        }
        var snapshot = ctx.serverView().latest();
        if (snapshot == null
                || InventoryHelper.countItem(snapshot, Material.ENDER_PEARL)
                        < outer.pearlReserve() + 2) {
            return 0;
        }
        if (InventoryHelper.countItem(snapshot, Material.ELYTRA) > 0) {
            return 0; // elytry už má, není za čím letět
        }
        double courage = bot.personality().trait(Trait.COURAGE);
        if (courage < ctx.config().end().minCourage()) {
            return 0;
        }
        return 10 + courage * 8 + bot.personality().trait(Trait.CURIOSITY) * 5;
    }

    /** @return {@code true} mimo hlavní ostrov (za prstencem gatewayí) */
    static boolean onOuterIslands(Vec3 position) {
        return position.horizontal().distance(Vec3.ZERO) > OUTER_THRESHOLD;
    }

    @Override
    public void start(Bot bot) {
        BotContext ctx = ctx(bot);
        // Přerušená výprava (boj, jídlo) pokračuje, kde skončila.
        if (phase != Phase.IDLE && phase != Phase.DONE) {
            return;
        }
        if (onOuterIslands(ctx.position())) {
            // Venku bez plánu: zorientovat se a domů (s kořistí, když to půjde).
            phase = Phase.OUTER_ORIENT;
        } else {
            phase = Phase.GATEWAY_FIND;
            ctx.chat().sayFrom(PhraseCategory.END_OUTER_DEPART, null);
        }
        tripDeadlineMs = System.currentTimeMillis()
                + ctx.config().end().outer().maxTripMinutes() * 60_000L;
        pearlThrows = 0;
        exploreHops = 0;
        equipTries = 0;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        if (phase == Phase.IDLE || phase == Phase.DONE) {
            return;
        }
        // Pojistky: rozpočet, zdraví, hlad → otočit to domů.
        if (phase != Phase.HOME_GO && phase != Phase.HOME_THROW
                && onOuterIslands(ctx.position())
                && (System.currentTimeMillis() > tripDeadlineMs
                        || ctx.clientState().health() < 8
                        || ctx.clientState().food() < 6)) {
            beginHomeRun(ctx);
        }
        switch (phase) {
            case GATEWAY_FIND -> tickGatewayFind(ctx, bot);
            case GATEWAY_GO -> tickGatewayGo(ctx);
            case THROW -> tickThrow(ctx, bot, gateway, Phase.OUTER_ORIENT);
            case OUTER_ORIENT -> tickOuterOrient(ctx, bot);
            case CITY_FIND -> tickCityFind(ctx, bot);
            case CITY_GO -> tickCityGo(ctx, bot);
            case LOOT -> tickLoot(ctx);
            case FRAME -> tickFrame(ctx);
            case COLLECT -> tickCollect(ctx, bot);
            case EQUIP -> tickEquip(ctx, bot);
            case HOME_GO -> tickHomeGo(ctx);
            case HOME_THROW -> tickThrow(ctx, bot, returnGateway, Phase.DONE);
            default -> { }
        }
    }

    // ============================================================== gateway

    private void tickGatewayFind(BotContext ctx, Bot bot) {
        // Vzpomínka na gateway z minula šetří hledání.
        Optional<BlockPos> remembered = bot.memory().recall(MemoryKind.PORTAL).stream()
                .filter(r -> "end_gateway".equals(r.data().get("type")))
                .filter(r -> r.world().equals(ctx.worldView().worldName()))
                .map(r -> new BlockPos(r.x(), r.y(), r.z()))
                .findFirst();
        if (remembered.isPresent()) {
            gateway = remembered.get();
            phase = Phase.GATEWAY_GO;
            return;
        }
        // Prstenec gatewayí: nejbližší bod na kružnici r=96 od středu.
        Vec3 pos = ctx.position();
        double length = Math.max(1, pos.horizontal().distance(Vec3.ZERO));
        int ax = (int) Math.round(pos.x() / length * GATEWAY_RING_RADIUS);
        int az = (int) Math.round(pos.z() / length * GATEWAY_RING_RADIUS);
        BlockPos anchor = new BlockPos(ax, 75, az);
        if (pos.distance(anchor.center()) > 28) {
            if (!ctx.navigator().navigating()) {
                ctx.navigator().navigateTo(pos, PathGoal.near(anchor, 16));
            }
            return;
        }
        if (scanRetry.waiting()) {
            return;
        }
        BlockPos found = scanFor(ctx.worldView(), anchor, 24, 24, Material.END_GATEWAY);
        if (found != null) {
            gateway = found;
            rememberGateway(ctx, bot, found);
            scanRetry.reset();
            phase = Phase.GATEWAY_GO;
            return;
        }
        if (!scanRetry.shouldRetry()) {
            finish(ctx, 6000); // gateway nikde – možná drak padl bez gatewayí
        }
    }

    private void tickGatewayGo(BotContext ctx) {
        double distance = ctx.position().distance(gateway.center());
        if (distance <= 4.5) {
            ctx.navigator().stop();
            preThrowPos = ctx.position();
            throwTicks = 0;
            pearlThrows = 0;
            phase = Phase.THROW;
            return;
        }
        if (!ctx.navigator().navigating()) {
            ctx.navigator().navigateTo(ctx.position(), PathGoal.near(gateway, 3));
        }
    }

    /** Hod perlou do gatewaye + čekání na teleport na druhou stranu. */
    private void tickThrow(BotContext ctx, Bot bot, BlockPos portal, Phase after) {
        if (portal == null) {
            finish(ctx, 2400);
            return;
        }
        // Teleport se pozná skokem pozice (stále tentýž svět – End).
        if (preThrowPos != null
                && ctx.position().distance(preThrowPos) > TRANSIT_JUMP) {
            ctx.navigator().stop();
            phase = after;
            waitTicks = 30; // po příletu chvíli počkat na chunky
            scanRetry.reset();
            return;
        }
        var snapshot = ctx.serverView().latest();
        if (snapshot == null) {
            return;
        }
        int pearls = InventoryHelper.countItem(snapshot, Material.ENDER_PEARL);
        boolean homeRun = after == Phase.DONE;
        if (pearls <= 0 || (!homeRun
                && pearls <= ctx.config().end().outer().pearlReserve())) {
            beginHomeRun(ctx); // perly došly – bez rezervy se ven nechodí
            return;
        }
        if (++throwTicks % 50 == 10) {
            // Zamířit do portálu a hodit perlu (precedent házení lektvarů).
            if (!ctx.inventory().equipItem(snapshot, Material.ENDER_PEARL)) {
                finish(ctx, 2400);
                return;
            }
            ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0), portal.center());
            pearlThrows++;
        } else if (throwTicks % 50 == 16) {
            ctx.actions().useItem(ctx.humanizer().yaw(), ctx.humanizer().pitch());
        }
        if (pearlThrows > 4) {
            finish(ctx, 6000); // perly lítají, teleport nikde – vzdát to
        }
    }

    // ======================================================== vnější ostrovy

    private void tickOuterOrient(BotContext ctx, Bot bot) {
        if (--waitTicks > 0 || scanRetry.waiting()) {
            return;
        }
        // Zpáteční gateway stojí u místa příletu – zapamatovat!
        BlockPos found = scanFor(ctx.worldView(), ctx.position().toBlockPos(),
                24, 24, Material.END_GATEWAY);
        if (found != null) {
            returnGateway = found;
        } else if (scanRetry.shouldRetry()) {
            return; // chunky se ještě sypou – zkusit za chvíli znovu
        }
        scanRetry.reset();
        // Server-side nápověda, kde je end city (jen vlastní server, §9).
        var outer = ctx.config().end().outer();
        if (outer.locateAssist() && !ctx.config().network().packetWorldModel()) {
            locateFuture = locateCity(ctx);
        }
        phase = Phase.CITY_FIND;
    }

    /** Server-side locate end city přes hlavní vlákno (Folia-safe). */
    private CompletableFuture<BlockPos> locateCity(BotContext ctx) {
        String worldName = ctx.worldView().worldName();
        Vec3 pos = ctx.position();
        org.bukkit.World world = org.bukkit.Bukkit.getWorld(worldName);
        if (world == null) {
            return CompletableFuture.completedFuture(null);
        }
        org.bukkit.Location origin = new org.bukkit.Location(
                world, pos.x(), pos.y(), pos.z());
        return ctx.bridge().callAt(origin, () -> {
            var result = world.locateNearestStructure(origin,
                    org.bukkit.generator.structure.Structure.END_CITY, 48, false);
            if (result == null || result.getLocation() == null) {
                return null;
            }
            var location = result.getLocation();
            return new BlockPos(location.getBlockX(), Math.max(60, location.getBlockY()),
                    location.getBlockZ());
        });
    }

    private void tickCityFind(BotContext ctx, Bot bot) {
        // 1) Vzpomínka na město z dřívějška.
        if (cityPos == null) {
            bot.memory().recall(MemoryKind.END_CITY).stream()
                    .filter(r -> r.world().equals(ctx.worldView().worldName()))
                    .findFirst()
                    .ifPresent(r -> cityPos = new BlockPos(r.x(), r.y(), r.z()));
        }
        // 2) Výsledek server-side locate.
        if (cityPos == null && locateFuture != null && locateFuture.isDone()) {
            cityPos = locateFuture.getNow(null);
            locateFuture = null;
        }
        // 3) Sken purpuru kolem sebe (fallback i doplněk).
        if (cityPos == null) {
            BlockPos purpur = scanFor(ctx.worldView(), ctx.position().toBlockPos(),
                    32, 32, Material.PURPUR_BLOCK);
            if (purpur != null) {
                cityPos = purpur;
            }
        }
        if (cityPos != null) {
            if (tooFar(ctx, cityPos)) {
                beginHomeRun(ctx); // město za horizontem – přes void se nemostí
                return;
            }
            rememberCity(ctx, bot, cityPos);
            ctx.chat().sayFrom(PhraseCategory.END_CITY_FOUND, null);
            phase = Phase.CITY_GO;
            return;
        }
        // 4) Průzkumné skoky po ostrově, dokud město nevykoukne.
        if (!ctx.navigator().navigating()) {
            if (++exploreHops > MAX_EXPLORE_HOPS) {
                beginHomeRun(ctx); // město se nenašlo, aspoň domů s perlami
                return;
            }
            Vec3 pos = ctx.position();
            double angle = ctx.rng().rangeInt(0, 360) * Math.PI / 180.0;
            int hop = ctx.rng().rangeInt(48, 112);
            BlockPos target = new BlockPos(
                    (int) (pos.x() + Math.cos(angle) * hop),
                    (int) Math.max(50, Math.min(90, pos.y())),
                    (int) (pos.z() + Math.sin(angle) * hop));
            ctx.navigator().navigateTo(pos, PathGoal.near(target, 12));
        }
    }

    private boolean tooFar(BotContext ctx, BlockPos city) {
        return ctx.position().distance(city.center())
                > ctx.config().end().outer().maxCityDistance();
    }

    private void tickCityGo(BotContext ctx, Bot bot) {
        double distance = ctx.position().distance(cityPos.center());
        if (distance <= 24) {
            ctx.navigator().stop();
            chestQueue.clear();
            collectChests(ctx);
            phase = Phase.LOOT;
            return;
        }
        if (!ctx.navigator().navigating()) {
            ctx.navigator().navigateTo(ctx.position(), PathGoal.near(cityPos, 16));
        }
    }

    // ================================================================ kořist

    private void collectChests(BotContext ctx) {
        WorldView world = ctx.worldView();
        BlockPos center = cityPos;
        int found = 0;
        for (int dy = 40; dy >= -16 && found < MAX_CHESTS; dy--) {
            for (int dx = -20; dx <= 20 && found < MAX_CHESTS; dx++) {
                for (int dz = -20; dz <= 20 && found < MAX_CHESTS; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (world.materialAt(pos) == Material.CHEST) {
                        chestQueue.add(pos);
                        found++;
                    }
                }
            }
        }
    }

    private void tickLoot(BotContext ctx) {
        if (lootFuture != null) {
            if (!lootFuture.isDone()) {
                return;
            }
            lootFuture = null;
            currentChest = null;
        }
        if (currentChest == null) {
            currentChest = chestQueue.poll();
            if (currentChest == null) {
                phase = Phase.FRAME;
                waitTicks = 0;
                return;
            }
        }
        double distance = ctx.position().distance(currentChest.center());
        if (distance <= 3.5) {
            ctx.navigator().stop();
            lootFuture = chests.lootValuables(ctx, ctx.worldView().worldName(),
                    currentChest);
            return;
        }
        if (!ctx.navigator().navigating()) {
            ctx.navigator().navigateTo(ctx.position(), PathGoal.near(currentChest, 2));
            if (!ctx.navigator().navigating()) {
                currentChest = null; // nedostupná truhla (věž) – další
            }
        }
    }

    /** Elytry visí v item frame na end ship – sundat úderem. */
    private void tickFrame(BotContext ctx) {
        Optional<TrackedEntity> frame = ctx.entities()
                .nearby(ctx.position(), 40, TrackedEntity::isItemFrame)
                .stream().findFirst();
        if (frame.isEmpty()) {
            if (++waitTicks > 100) {
                beginHomeRun(ctx); // město bez lodi – elytry nebudou
            }
            return;
        }
        TrackedEntity entity = frame.get();
        framePos = entity.position().toBlockPos();
        double distance = ctx.position().distance(entity.position());
        if (distance <= 3.5) {
            ctx.navigator().stop();
            ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0), entity.position());
            ctx.actions().attack(entity.entityId());
            frameEntityId = entity.entityId();
            phase = Phase.COLLECT;
            waitTicks = 0;
            return;
        }
        if (!ctx.navigator().navigating()) {
            ctx.navigator().navigateTo(ctx.position(), PathGoal.near(framePos, 2));
            if (!ctx.navigator().navigating() && ++waitTicks > 200) {
                beginHomeRun(ctx); // loď mimo dosah (za voidem)
            }
        }
    }

    /** Sebrat vypadlé elytry (chůze na drop). */
    private void tickCollect(BotContext ctx, Bot bot) {
        var snapshot = ctx.serverView().latest();
        if (snapshot != null
                && InventoryHelper.countItem(snapshot, Material.ELYTRA) > 0) {
            ctx.chat().sayFrom(PhraseCategory.ELYTRA_FOUND, null);
            rememberTrophy(ctx, bot);
            phase = Phase.EQUIP;
            waitTicks = 0;
            return;
        }
        if (frameEntityId != null
                && ctx.entities().byId(frameEntityId).isPresent()
                && waitTicks % 20 == 5) {
            ctx.actions().attack(frameEntityId); // frame ještě visí – doklepnout
        }
        Optional<TrackedEntity> drop = ctx.entities()
                .nearest(ctx.position(), 8, TrackedEntity::isItem);
        if (drop.isPresent() && !ctx.navigator().navigating()) {
            ctx.navigator().navigateTo(ctx.position(),
                    PathGoal.block(drop.get().position().toBlockPos()));
        }
        if (++waitTicks > 300) {
            beginHomeRun(ctx); // drop spadl do voidu... smůla
        }
    }

    /** Obléknout elytry (pravý klik – vanilla swap do hrudního slotu). */
    private void tickEquip(BotContext ctx, Bot bot) {
        var snapshot = ctx.serverView().latest();
        if (snapshot == null) {
            return;
        }
        if (InventoryHelper.countItem(snapshot, Material.ELYTRA) == 0) {
            beginHomeRun(ctx); // už nejsou v batohu = jsou na zádech
            return;
        }
        if (--waitTicks > 0) {
            return;
        }
        if (++equipTries > 3) {
            beginHomeRun(ctx); // nasazení nevyšlo, domů i tak (elytry v batohu)
            return;
        }
        if (ctx.inventory().equipItem(snapshot, Material.ELYTRA)) {
            ctx.actions().useItem(ctx.humanizer().yaw(), ctx.humanizer().pitch());
        }
        waitTicks = 30;
    }

    // ================================================================= domů

    private void beginHomeRun(BotContext ctx) {
        glideTask = null;
        if (!onOuterIslands(ctx.position())) {
            finish(ctx, 2400); // ještě na hlavním ostrově – návrat řeší end-return
            return;
        }
        phase = Phase.HOME_GO;
        ctx.navigator().stop();
    }

    private void tickHomeGo(BotContext ctx) {
        if (returnGateway == null) {
            BlockPos found = scanFor(ctx.worldView(), ctx.position().toBlockPos(),
                    32, 32, Material.END_GATEWAY);
            if (found != null) {
                returnGateway = found;
            } else if (!ctx.navigator().navigating()) {
                // Zpáteční gateway se ztratila – bez ní se domů nedá; čekat
                // u okraje by znamenalo smrt, tak aspoň bezpečný konec cíle.
                finish(ctx, 6000);
                return;
            }
        }
        if (returnGateway == null) {
            return;
        }
        // Slet na elytrách, když je kam a na čem letět.
        if (glideTask != null) {
            boolean done = glideTask.tick(ctx);
            ctx.requestMove(glideTask.move());
            if (done) {
                glideTask = null;
            }
            return;
        }
        var snapshot = ctx.serverView().latest();
        boolean wearingElytra = snapshot != null
                && InventoryHelper.countItem(snapshot, Material.ELYTRA) == 0
                && equipTries > 0; // oblékal je a z batohu zmizely
        if (ctx.config().end().outer().elytra() && wearingElytra
                && GlideTask.viable(ctx.position(), returnGateway)) {
            ctx.navigator().stop();
            glideTask = new GlideTask(returnGateway);
            return;
        }
        double distance = ctx.position().distance(returnGateway.center());
        if (distance <= 4.5) {
            ctx.navigator().stop();
            preThrowPos = ctx.position();
            throwTicks = 0;
            pearlThrows = 0;
            phase = Phase.HOME_THROW;
            return;
        }
        if (!ctx.navigator().navigating()) {
            ctx.navigator().navigateTo(ctx.position(), PathGoal.near(returnGateway, 3));
        }
    }

    // ================================================================ závěr

    private void finish(BotContext ctx, int cooldown) {
        if (phase == Phase.DONE && cooldown <= 0) {
            return;
        }
        if (onOuterIslands(ctx.position())) {
            // Nikdy nekončit „uvízlý venku" bez cooldownu – utility cíl
            // znovu zvedne a návrat se dokončí.
            phase = Phase.HOME_GO;
            return;
        }
        phase = Phase.DONE;
        cooldownTicks = cooldown;
        glideTask = null;
        lootFuture = null;
        locateFuture = null;
        ctx.navigator().stop();
    }

    @Override
    public void stop(Bot bot) {
        BotContext ctx = ctx(bot);
        if (glideTask != null) {
            glideTask.cancel(ctx);
            glideTask = null;
        }
        super.stop(bot);
    }

    @Override
    public boolean finished(Bot bot) {
        if (phase == Phase.DONE) {
            BotContext ctx = ctx(bot);
            if (!onOuterIslands(ctx.position())) {
                // Zpátky na hlavním ostrově: oslava a předání end-return cíli.
                ctx.chat().sayFrom(PhraseCategory.END_OUTER_RETURN, null);
                phase = Phase.IDLE;
                if (cooldownTicks <= 0) {
                    cooldownTicks = 6000;
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public boolean blocksRelocation() {
        return phase != Phase.IDLE && phase != Phase.DONE;
    }

    @Override
    public String explain(Bot bot) {
        return switch (phase) {
            case GATEWAY_FIND, GATEWAY_GO -> "mířím ke gateway – vnější ostrovy volají";
            case THROW -> "házím perlu do gatewaye";
            case OUTER_ORIENT, CITY_FIND -> "hledám end city na vnějších ostrovech";
            case CITY_GO -> "jdu k end city";
            case LOOT -> "vybírám truhly end city";
            case FRAME, COLLECT -> "sundávám elytry z end ship";
            case EQUIP -> "oblékám si elytry!";
            case HOME_GO, HOME_THROW -> "vracím se z vnějších ostrovů domů";
            default -> null;
        };
    }

    // ================================================================ nitro

    /** Nejbližší výskyt materiálu v kvádru kolem středu (nebo {@code null}). */
    private static BlockPos scanFor(WorldView world, BlockPos center,
                                    int radius, int vertical, Material material) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -vertical; dy <= vertical; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    if (world.materialAt(pos) != material) {
                        continue;
                    }
                    double dist = (double) dx * dx + (double) dy * dy + (double) dz * dz;
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = pos;
                    }
                }
            }
        }
        return best;
    }

    private void rememberGateway(BotContext ctx, Bot bot, BlockPos pos) {
        bot.memory().remember(MemoryKind.PORTAL, ctx.worldView().worldName(),
                pos.x(), pos.y(), pos.z(), null,
                Map.of("type", "end_gateway"), 0.8);
    }

    private void rememberCity(BotContext ctx, Bot bot, BlockPos pos) {
        bot.memory().remember(MemoryKind.END_CITY, ctx.worldView().worldName(),
                pos.x(), pos.y(), pos.z(), null, Map.of(), 0.9);
    }

    private void rememberTrophy(BotContext ctx, Bot bot) {
        Vec3 pos = ctx.position();
        bot.memory().remember(MemoryKind.TROPHY, ctx.worldView().worldName(),
                (int) pos.x(), (int) pos.y(), (int) pos.z(), null,
                Map.of("type", "elytra"), 1.0);
        ctx.gainExperience(dev.botalive.core.personality.PersonalityEvolution
                .BotExperience.DRAGON_SLAIN);
    }
}
