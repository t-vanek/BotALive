package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.tasks.MineBlockTask;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.WorldView;
import org.bukkit.Material;
import org.bukkit.block.data.Ageable;
import org.bukkit.block.data.BlockData;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;

import java.util.Map;
import dev.botalive.core.pathfinding.PathGoal;

/**
 * Farmaření – sklizeň zralých plodin a přesazení.
 *
 * <p>Bot hledá v okolí zralé plodiny (kontrola {@link Ageable} přes block data
 * z chunk snapshotu), sklidí je (plodiny se lámou okamžitě), a pokud má
 * v hotbaru osivo, znovu je zasadí (UseItemOn na farmland – stejný paket jako
 * hráč s pravým klikem). Nalezené pole si pamatuje jako
 * {@link MemoryKind#FARM} a vrací se k němu.</p>
 *
 * <p>Vedle klasických plodin zvládá i <b>netherovou bradavici</b> (roste na
 * soul sandu i v overworldu – záhon si bot založí sám z netherové kořisti,
 * vaření lektvarů tak nezávisí na pevnostech) a <b>cukrovou třtinu</b>
 * (sklízí se druhý článek, základ dorůstá; třtina = papír = rakety).</p>
 */
public final class FarmGoal extends AbstractGoal {

    /** Plodina → osivo pro přesazení. */
    private static final Map<Material, Material> CROP_SEEDS = Map.of(
            Material.WHEAT, Material.WHEAT_SEEDS,
            Material.CARROTS, Material.CARROT,
            Material.POTATOES, Material.POTATO,
            Material.BEETROOTS, Material.BEETROOT_SEEDS,
            Material.NETHER_WART, Material.NETHER_WART
    );

    /** Velikost zakládaného záhonu bradavice (bloky soul sandu). */
    private static final int WART_BED_SIZE = 3;

    /** Délka zakládaného obilného řádku (buňky farmlandu v dosahu pokládky). */
    private static final int FIELD_SIZE = 3;

    /** Motyka libovolného materiálu – zorá hlínu/trávu na farmland. */
    private static final java.util.function.Predicate<Material> HOE =
            m -> m != null && m.name().endsWith("_HOE");

    private enum Phase {
        FIND, GO, HARVEST, REPLANT, BED_PLACE, BED_PLANT, FIELD_TILL, FIELD_PLANT, DONE
    }

    private Phase phase = Phase.FIND;
    private BlockPos crop;
    /** Kandidátní zralé plodiny – sklízí se ta, ke které se došlo (anyNear). */
    private java.util.List<BlockPos> crops = java.util.List.of();
    private Material cropType;
    private MineBlockTask harvestTask;
    private int replantTicks;
    private int cooldownTicks;
    private int harvested;
    /** Zakládání záhonu bradavice: kam přijde soul sand / sadba. */
    private final java.util.ArrayDeque<BlockPos> bedQueue = new java.util.ArrayDeque<>();
    private dev.botalive.core.tasks.PlaceBlockTask bedTask;
    private int bedTicks;

    /** Zakládání obilného pole: buňky hlíny k zorání a osetí (v pořadí). */
    private final java.util.ArrayDeque<BlockPos> fieldQueue = new java.util.ArrayDeque<>();
    private java.util.List<BlockPos> fieldCells = java.util.List.of();
    private int fieldTicks;

    /** Vytvoří cíl. */
    public FarmGoal() {
        super("farm");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        // Pole patří do overworldu (v Netheru plodiny nerostou).
        if (outsideOverworld(ctx)) {
            return 0;
        }
        // Chuť farmařit: hlad zvyšuje, trpělivost a ochota pomoci podporují.
        double patience = bot.personality().trait(Trait.PATIENCE);
        double hungerPressure = Math.max(0, 16 - ctx.clientState().food());
        // Známá farma poblíž → vyšší motivace (ví, kam jít).
        boolean knownFarm = ctx.worldView() != null && bot.memory()
                .recallNearest(MemoryKind.FARM, ctx.worldView().worldName(),
                        (int) ctx.position().x(), (int) ctx.position().y(), (int) ctx.position().z())
                .map(r -> r.distanceSquared((int) ctx.position().x(),
                        (int) ctx.position().y(), (int) ctx.position().z()) < 64 * 64)
                .orElse(false);
        return 4 + patience * 8 + hungerPressure * 1.2 + (knownFarm ? 6 : 0);
    }

    @Override
    public void start(Bot bot) {
        phase = Phase.FIND;
        crop = null;
        crops = java.util.List.of();
        harvestTask = null;
        harvested = 0;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        switch (phase) {
            case FIND -> {
                crops = findMatureCrops(ctx);
                if (crops.isEmpty()) {
                    if (startWartBed(ctx, bot)) {
                        return; // z netherové kořisti se zakládá záhon
                    }
                    if (startCropField(ctx, bot)) {
                        return; // s osivem a motykou u zázemí se zakládá pole
                    }
                    cooldownTicks = 900; // v okolí nic zralého
                    phase = Phase.DONE;
                    return;
                }
                crop = crops.getFirst();
                cropType = ctx.worldView().materialAt(crop);
                phase = Phase.GO;
            }
            case GO -> {
                // Sklízí se plodina, ke které se skutečně došlo – o pořadí
                // kandidátů rozhoduje dosažitelnost (anyNear), ne vzdušná čára.
                BlockPos reachable = null;
                double bestDist = 3.5 * 3.5;
                for (BlockPos candidate : crops) {
                    double dist = candidate.center().distanceSquared(ctx.position());
                    if (dist <= bestDist) {
                        bestDist = dist;
                        reachable = candidate;
                    }
                }
                if (reachable == null) {
                    ctx.navigator().navigateTo(ctx.position(), crops.size() > 1
                            ? PathGoal.anyNear(crops, 2)
                            : PathGoal.near(crop, 2));
                    if (!ctx.navigator().navigating()) {
                        phase = Phase.FIND; // nedosažitelné – hledat jinde
                    }
                    return;
                }
                crop = reachable;
                cropType = ctx.worldView().materialAt(crop);
                ctx.navigator().stop();
                harvestTask = new MineBlockTask(crop);
                phase = Phase.HARVEST;
            }
            case HARVEST -> {
                if (harvestTask.tick(ctx)) {
                    harvestTask = null;
                    harvested++;
                    ctx.stats().addMined();
                    rememberFarm(ctx, bot);
                    // Přesazení, pokud má bot osivo v hotbaru.
                    // cropType může být null: materialAt() ho vrací pro
                    // nenacachovaný chunk (TTL 3 s) a CROP_SEEDS je Map.of(),
                    // které na null klíči hodí NPE (na rozdíl od HashMap).
                    // Bez téhle pojistky celý cíl spadl a mozek ho deaktivoval.
                    Material seed = cropType == null ? null : CROP_SEEDS.get(cropType);
                    var snapshot = ctx.serverView().latest();
                    if (seed != null && snapshot != null
                            && snapshot.findHotbarSlot(m -> m == seed) >= 0) {
                        ctx.actions().selectHotbar(snapshot.findHotbarSlot(m -> m == seed));
                        replantTicks = ctx.rng().rangeInt(4, 10);
                        phase = Phase.REPLANT;
                    } else {
                        phase = Phase.FIND; // další plodina
                    }
                }
            }
            case REPLANT -> {
                if (--replantTicks <= 0) {
                    // Osivo se sází pravým klikem na farmland pod plodinou.
                    ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0), crop.center());
                    ctx.actions().useItemOn(crop.down(), Direction.UP);
                    ctx.stats().addPlaced();
                    phase = Phase.FIND;
                }
            }
            case BED_PLACE -> tickBedPlace(ctx);
            case BED_PLANT -> tickBedPlant(ctx, bot);
            case FIELD_TILL -> tickFieldTill(ctx);
            case FIELD_PLANT -> tickFieldPlant(ctx, bot);
            case DONE -> {
                // finished() ukončí
            }
        }
    }

    /**
     * Založení záhonu bradavice: bot s netherovou kořistí (soul sand
     * + bradavice navíc) položí u domova/pole řádek soul sandu a osází ho.
     * Vaření lektvarů tak přestává záviset na návratech do pevnosti.
     *
     * @return {@code true} pokud se záhon začal zakládat
     */
    private boolean startWartBed(BotContext ctx, dev.botalive.api.bot.Bot bot) {
        var snapshot = ctx.serverView().latest();
        if (snapshot == null || !ctx.config().nether().brewing()
                || dev.botalive.core.inventory.InventoryHelper.countItem(
                        snapshot, Material.SOUL_SAND) < WART_BED_SIZE
                || dev.botalive.core.inventory.InventoryHelper.countItem(
                        snapshot, Material.NETHER_WART) < WART_BED_SIZE + 1) {
            return false;
        }
        // Záhon patří k zázemí – zakládá se jen u domova nebo známého pole.
        BlockPos feet = ctx.position().toBlockPos();
        boolean nearBase = ctx.worldView() != null && (bot.memory()
                .recallNearest(MemoryKind.HOME, ctx.worldView().worldName(),
                        feet.x(), feet.y(), feet.z())
                .map(r -> r.distanceSquared(feet.x(), feet.y(), feet.z()) < 32 * 32)
                .orElse(false)
                || bot.memory().recallNearest(MemoryKind.FARM, ctx.worldView().worldName(),
                        feet.x(), feet.y(), feet.z())
                .map(r -> r.distanceSquared(feet.x(), feet.y(), feet.z()) < 32 * 32)
                .orElse(false));
        if (!nearBase) {
            return false;
        }
        // Řádek volných buněk s pevnou podlahou vedle bota (žádná chůze –
        // do dosahu pokládky se vejde řádek hned za zády).
        bedQueue.clear();
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            bedQueue.clear();
            for (int i = 1; i <= WART_BED_SIZE; i++) {
                BlockPos cell = feet.offset(d[0] * 2, 0, d[1] * 2)
                        .offset(d[1] * (i - 2), 0, d[0] * (i - 2));
                if (ctx.worldView().traitsAt(cell).passable()
                        && ctx.worldView().traitsAt(cell.up()).passable()
                        && ctx.worldView().traitsAt(cell.down()).solid()) {
                    bedQueue.add(cell);
                }
            }
            if (bedQueue.size() == WART_BED_SIZE) {
                break;
            }
        }
        if (bedQueue.size() < WART_BED_SIZE) {
            bedQueue.clear();
            return false;
        }
        bedTask = null;
        phase = Phase.BED_PLACE;
        return true;
    }

    /** Pokládka soul sandu záhonu (PlaceBlockTask po buňkách). */
    private void tickBedPlace(BotContext ctx) {
        if (bedTask != null) {
            if (bedTask.tick(ctx)) {
                bedTask = null;
            }
            return;
        }
        BlockPos next = bedQueue.poll();
        if (next == null) {
            // Sand položený – najít ho ve světě a osázet.
            collectBedForPlanting(ctx);
            bedTicks = 0;
            phase = Phase.BED_PLANT;
            return;
        }
        var snapshot = ctx.serverView().latest();
        if (snapshot == null
                || !ctx.inventory().equipItem(snapshot, Material.SOUL_SAND)) {
            phase = Phase.FIND; // sand došel/nejde vzít – záhon jindy
            bedQueue.clear();
            return;
        }
        bedTask = new dev.botalive.core.tasks.PlaceBlockTask(next);
    }

    /** Najde čerstvě položený soul sand kolem bota pro osázení. */
    private void collectBedForPlanting(BotContext ctx) {
        bedQueue.clear();
        BlockPos feet = ctx.position().toBlockPos();
        for (int dx = -4; dx <= 4; dx++) {
            for (int dy = -1; dy <= 1; dy++) {
                for (int dz = -4; dz <= 4; dz++) {
                    BlockPos pos = feet.offset(dx, dy, dz);
                    if (ctx.worldView().materialAt(pos) == Material.SOUL_SAND
                            && ctx.worldView().traitsAt(pos.up()).passable()) {
                        bedQueue.add(pos);
                    }
                }
            }
        }
    }

    /** Sázení bradavice na položený soul sand (use na horní plochu). */
    private void tickBedPlant(BotContext ctx, dev.botalive.api.bot.Bot bot) {
        if (--bedTicks > 0) {
            return;
        }
        BlockPos sand = bedQueue.poll();
        if (sand == null) {
            BlockPos feet = ctx.position().toBlockPos();
            if (ctx.worldView() != null) {
                bot.memory().remember(MemoryKind.FARM, ctx.worldView().worldName(),
                        feet.x(), feet.y(), feet.z(), null,
                        Map.of("crop", Material.NETHER_WART.name()), 0.7);
            }
            if (ctx.rng().chance(0.6)) {
                ctx.chat().say("zahonek bradavice, at nemusim porad do pekla");
            }
            cooldownTicks = 900;
            phase = Phase.DONE;
            return;
        }
        var snapshot = ctx.serverView().latest();
        int seed = snapshot == null ? -1
                : snapshot.findHotbarSlot(m -> m == Material.NETHER_WART);
        if (seed < 0) {
            if (snapshot != null) {
                ctx.inventory().equipItem(snapshot, Material.NETHER_WART);
            }
            bedTicks = 4;
            bedQueue.addFirst(sand);
            return;
        }
        ctx.actions().selectHotbar(seed);
        ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0),
                sand.center().add(0, 0.5, 0));
        ctx.actions().useItemOn(sand, Direction.UP);
        ctx.stats().addPlaced();
        bedTicks = ctx.rng().rangeInt(5, 10);
    }

    /**
     * Založení obilného pole: bot s osivem a motykou u zázemí zorá krátký
     * řádek hlíny/trávy na farmland a oseje ho pšenicí. Pole tak nemusí ve
     * světě existovat předem – farmář si ho vytvoří sám (dosud uměl jen
     * udržovat zděděná políčka). Bez závlahy roste pomaleji, ale roste, a
     * osetá půda se nerozpadá zpět na hlínu.
     *
     * @return {@code true} pokud se pole začalo zakládat
     */
    private boolean startCropField(BotContext ctx, Bot bot) {
        WorldView world = ctx.worldView();
        var snapshot = ctx.serverView().latest();
        if (world == null || snapshot == null || !snapshot.hasItem(HOE)
                || dev.botalive.core.inventory.InventoryHelper.countItem(
                        snapshot, Material.WHEAT_SEEDS) < FIELD_SIZE) {
            return false;
        }
        // Pole patří k zázemí – zakládá se jen u domova nebo známého pole.
        BlockPos feet = ctx.position().toBlockPos();
        boolean nearBase = bot.memory()
                .recallNearest(MemoryKind.HOME, world.worldName(), feet.x(), feet.y(), feet.z())
                .map(r -> r.distanceSquared(feet.x(), feet.y(), feet.z()) < 32 * 32)
                .orElse(false)
                || bot.memory().recallNearest(MemoryKind.FARM, world.worldName(),
                        feet.x(), feet.y(), feet.z())
                .map(r -> r.distanceSquared(feet.x(), feet.y(), feet.z()) < 32 * 32)
                .orElse(false);
        if (!nearBase) {
            return false;
        }
        // Řádek zoratelné země v dosahu (žádná chůze): hlína/tráva s volným
        // místem nad ní pro plodinu; zkusí čtyři směry od bota.
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
        for (int[] d : dirs) {
            java.util.List<BlockPos> row = new java.util.ArrayList<>();
            for (int i = 1; i <= FIELD_SIZE; i++) {
                BlockPos ground = feet.offset(d[0] * i, -1, d[1] * i);
                if (tillable(world, ground) && world.traitsAt(ground.up()).passable()) {
                    row.add(ground);
                }
            }
            if (row.size() == FIELD_SIZE) {
                fieldCells = java.util.List.copyOf(row);
                fieldQueue.clear();
                fieldQueue.addAll(row);
                fieldTicks = 0;
                phase = Phase.FIELD_TILL;
                return true;
            }
        }
        return false;
    }

    /** Zoratelná půda: hlína nebo tráva (motyka z ní udělá farmland). */
    private static boolean tillable(WorldView world, BlockPos pos) {
        Material material = world.materialAt(pos);
        return material == Material.DIRT || material == Material.GRASS_BLOCK;
    }

    /** Orání řádku motykou (use na horní plochu, lidské rozestupy). */
    private void tickFieldTill(BotContext ctx) {
        if (--fieldTicks > 0) {
            return;
        }
        BlockPos cell = fieldQueue.peek();
        if (cell == null) {
            // Zorané – osít stejné buňky pšenicí.
            fieldQueue.addAll(fieldCells);
            fieldTicks = 0;
            phase = Phase.FIELD_PLANT;
            return;
        }
        var snapshot = ctx.serverView().latest();
        if (snapshot == null || !ctx.inventory().equipMatching(snapshot, HOE)) {
            phase = Phase.FIND; // motyka došla/nejde vzít – pole jindy
            fieldQueue.clear();
            return;
        }
        ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0), cell.center().add(0, 0.5, 0));
        ctx.actions().useItemOn(cell, Direction.UP);
        ctx.stats().addPlaced();
        fieldQueue.poll();
        fieldTicks = ctx.rng().rangeInt(5, 10);
    }

    /** Setí pšenice na zorané buňky (use na horní plochu farmlandu). */
    private void tickFieldPlant(BotContext ctx, Bot bot) {
        if (--fieldTicks > 0) {
            return;
        }
        BlockPos cell = fieldQueue.poll();
        if (cell == null) {
            BlockPos site = fieldCells.isEmpty() ? ctx.position().toBlockPos()
                    : fieldCells.getFirst();
            if (ctx.worldView() != null) {
                bot.memory().remember(MemoryKind.FARM, ctx.worldView().worldName(),
                        site.x(), site.y(), site.z(), null,
                        Map.of("crop", Material.WHEAT.name()), 0.7);
            }
            if (ctx.rng().chance(0.6)) {
                ctx.chat().say("zaseju si políčko, ať mám z čeho péct");
            }
            cooldownTicks = 900;
            phase = Phase.DONE;
            return;
        }
        var snapshot = ctx.serverView().latest();
        int seed = snapshot == null ? -1
                : snapshot.findHotbarSlot(m -> m == Material.WHEAT_SEEDS);
        if (seed < 0) {
            if (snapshot != null) {
                ctx.inventory().equipItem(snapshot, Material.WHEAT_SEEDS);
            }
            fieldTicks = 4;
            fieldQueue.addFirst(cell);
            return;
        }
        ctx.actions().selectHotbar(seed);
        ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0), cell.center().add(0, 0.5, 0));
        ctx.actions().useItemOn(cell, Direction.UP);
        ctx.stats().addPlaced();
        fieldTicks = ctx.rng().rangeInt(5, 10);
    }

    @Override
    public void stop(Bot bot) {
        if (harvestTask != null) {
            harvestTask.cancel(ctx(bot));
            harvestTask = null;
        }
        if (bedTask != null) {
            bedTask.cancel(ctx(bot));
            bedTask = null;
        }
        bedQueue.clear();
        fieldQueue.clear();
        super.stop(bot);
    }

    @Override
    public boolean finished(Bot bot) {
        if (harvested >= 12) {
            cooldownTicks = ctx(bot).rng().rangeInt(1200, 2400);
            return true;
        }
        return phase == Phase.DONE;
    }

    /** Uloží pole do paměti (jednou za sklizeň lokality). */
    private void rememberFarm(BotContext ctx, Bot bot) {
        if (ctx.worldView() != null && crop != null) {
            bot.memory().remember(MemoryKind.FARM, ctx.worldView().worldName(),
                    crop.x(), crop.y(), crop.z(), null,
                    Map.of("crop", cropType == null ? "?" : cropType.name()), 0.6);
        }
    }

    /** Sken okolí na zralou plodinu (Ageable s max věkem). */
    private java.util.List<BlockPos> findMatureCrops(BotContext ctx) {
        WorldView world = ctx.worldView();
        if (world == null) {
            return java.util.List.of();
        }
        BlockPos center = ctx.position().toBlockPos();
        java.util.ArrayList<BlockPos> found = new java.util.ArrayList<>();
        int radius = 12;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    Material material = world.materialAt(pos);
                    if (material == Material.SUGAR_CANE) {
                        // Třtina: sklízí se druhý článek (základ zůstává
                        // a dorůstá) – papír je základ raket na elytry.
                        if (world.materialAt(pos.down()) == Material.SUGAR_CANE
                                && world.materialAt(pos.down().down())
                                        != Material.SUGAR_CANE) {
                            found.add(pos);
                        }
                        continue;
                    }
                    if (material == null || !CROP_SEEDS.containsKey(material)) {
                        continue;
                    }
                    BlockData data = world.blockDataAt(pos);
                    if (!(data instanceof Ageable ageable)
                            || ageable.getAge() < ageable.getMaximumAge()) {
                        continue;
                    }
                    found.add(pos);
                }
            }
        }
        found.sort(java.util.Comparator.comparingDouble(p -> p.distanceSquared(center)));
        return found.size() > 6 ? java.util.List.copyOf(found.subList(0, 6))
                : java.util.List.copyOf(found);
    }

    @Override
    public String explain(dev.botalive.api.bot.Bot bot) {
        return "starám se o pole";
    }
}
