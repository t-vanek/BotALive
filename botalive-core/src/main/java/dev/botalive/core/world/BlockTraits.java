package dev.botalive.core.world;

import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.block.data.BlockData;
import org.bukkit.block.data.Bisected;
import org.bukkit.block.data.Openable;
import org.bukkit.block.data.Waterlogged;
import org.bukkit.block.data.type.Slab;
import org.bukkit.block.data.type.Snow;
import org.bukkit.block.data.type.Stairs;
import org.bukkit.block.data.type.TrapDoor;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Předpočítané vlastnosti bloků pro fyziku a pathfinding.
 *
 * <p>Vlastnosti existují ve dvou úrovních přesnosti:</p>
 * <ul>
 *   <li>{@link #of(Material)} – podle materiálu (levné, bez stavu bloku);
 *       desky/schody vychází jako plné bloky, dveře jako zavřené.</li>
 *   <li>{@link #of(BlockData)} – podle celého block state: poloviny desek
 *       a schodů, vrstvy sněhu, otevřenost dveří/vrat/poklopů, waterlogging
 *       a přesné kolizní boxy (přes interní registry serveru, jsou-li
 *       dostupné – {@link dev.botalive.core.world.state.CollisionShapes}).</li>
 * </ul>
 *
 * <p>Obě cache se sdílí mezi všemi boty; ve vnitřní smyčce A* se nedotýkáme
 * Bukkit API vůbec – jen těchto map.</p>
 *
 * @param passable  bot může stát/procházet prostorem bloku (vzduch, tráva, květiny)
 * @param solid     blok má podstatnou kolizi a dá se na něm stát (materiálová
 *                  úroveň – zahrnuje i desky, schody a ploty; přesný tvar
 *                  popisují {@link #boxes()} a {@link #floorHeight()})
 * @param liquid    voda/láva (i waterlogged bloky a vodní rostliny)
 * @param climbable žebřík/liána/lešení – bot může šplhat
 * @param door      zavřené dveře/vrata otevíratelné rukou – průchozí po interakci
 * @param hazard      kontakt zraňuje nebo zabíjí (láva, oheň, kaktus, magma)
 * @param openable    lze otevřít interakcí (dveře, vrata, branky, poklopy)
 * @param softLanding dopad na blok tlumí poškození z pádu (seno, slime, med, postel)
 * @param powderSnow  prašan – hustý sníh, do kterého se entity boří (mrznutí,
 *                    pomalé klesání, únik skokem); zároveň {@code hazard}
 * @param portal      portálový blok (nether/end portál) – průchozí, ale delší
 *                    pobyt v něm teleportuje; pathfinding ho penalizuje, aby
 *                    boti neměnili dimenzi omylem (záměrný vstup řeší výprava)
 * @param floorHeight výška horní pochozí plochy kolize v buňce: 0 = žádná
 *                    (vzduch, koberec), (0,1) = částečný blok, ve kterém bot
 *                    stojí nohama (deska 0.5, postel 0.5625, sníh po vrstvách),
 *                    1.0 = plný blok (stojí se na buňce nad ním),
 *                    1.5 = plot/zídka (nedá se překročit ani na ni vystoupit)
 * @param speedFactor násobič rychlosti chůze po bloku (soul sand/med 0.4)
 * @param slipperiness kluzkost povrchu (vanilla: 0.6 běžné bloky, 0.98 led,
 *                     0.989 modrý led) – ovlivňuje tření a akceleraci
 * @param web         pavučina – bez kolize, ale drasticky zpomaluje; pathfinding
 *                    se jí vyhýbá, fyzika v ní „vázne"
 * @param stepFriendly kolize tvoří schůdky ≤ 0.6 (schody) – výstup o celý blok
 *                     zvládne step-up fyzika bez skoku
 * @param boxes       kolizní boxy v lokálních souřadnicích buňky, po šesticích
 *                    {@code minX,minY,minZ,maxX,maxY,maxZ}; prázdné pole = bez kolize
 */
public record BlockTraits(
        boolean passable,
        boolean solid,
        boolean liquid,
        boolean climbable,
        boolean door,
        boolean hazard,
        boolean openable,
        boolean softLanding,
        boolean powderSnow,
        boolean portal,
        double floorHeight,
        double speedFactor,
        double slipperiness,
        boolean web,
        boolean stepFriendly,
        double[] boxes
) {

    /** Výchozí kluzkost povrchu (vanilla). */
    public static final double DEFAULT_SLIPPERINESS = 0.6;

    /** Bez kolize. */
    public static final double[] NO_BOXES = {};
    /** Plná kostka. */
    public static final double[] FULL_BOXES = {0, 0, 0, 1, 1, 1};
    /** Plot/zídka – 1,5 bloku vysoká překážka. */
    public static final double[] TALL_BOXES = {0, 0, 0, 1, 1.5, 1};

    private static final Map<Material, BlockTraits> CACHE = new EnumMap<>(Material.class);
    private static final Map<BlockData, BlockTraits> STATE_CACHE = new ConcurrentHashMap<>();

    /** Vzduch – nejčastější případ, sdílená instance. */
    public static final BlockTraits AIR = simple(true, false, false, false, false, false, false, false, false);

    /** Neznámý/nenačtený blok – konzervativně neprůchozí (plná kolize). */
    public static final BlockTraits UNKNOWN = simple(false, false, false, false, false, false, false, false, false)
            .withBoxes(FULL_BOXES);

    /**
     * Materiály, jejichž chování se liší podle stavu bloku (vyplatí se číst
     * block data). Lazy holder – Bukkit tagy jsou dostupné až na běžícím
     * serveru a třída se inicializuje i v unit testech.
     */
    private static final class StateSensitive {
        static final Set<Material> SET = buildStateSensitive();
    }

    /**
     * Vytvoří vlastnosti z legacy „booleovské" definice – tvar kolize se odvodí
     * ze {@code solid} (plná kostka, nebo nic). Pro testy a fallback režim.
     */
    public static BlockTraits simple(boolean passable, boolean solid, boolean liquid, boolean climbable,
                                     boolean door, boolean hazard, boolean openable, boolean softLanding,
                                     boolean powderSnow) {
        return new BlockTraits(passable, solid, liquid, climbable, door, hazard, openable, softLanding,
                powderSnow, false, solid ? 1.0 : 0.0, 1.0, DEFAULT_SLIPPERINESS, false, false,
                solid ? FULL_BOXES : NO_BOXES);
    }

    /** @return kopie s jinými kolizními boxy (floorHeight a stepFriendly se přepočítají) */
    public BlockTraits withBoxes(double[] newBoxes) {
        return new BlockTraits(passable, solid, liquid, climbable, door, hazard, openable, softLanding,
                powderSnow, portal, floorOf(newBoxes), speedFactor, slipperiness, web, stepFriendlyOf(newBoxes),
                newBoxes);
    }

    /** @return {@code true} pokud blok nemá žádnou kolizi (tělo jím projde) */
    public boolean noCollision() {
        return boxes.length == 0;
    }

    /**
     * @return {@code true} pokud kolize nepřekáží tělu při průchodu – žádné boxy,
     *         nebo jen nízký profil do 1/16 (koberec, nášlapná deska)
     */
    public boolean lowProfile() {
        return floorHeight <= 0.0625 + 1.0E-9;
    }

    /**
     * @return nejnižší začátek kolize v buňce ({@code Double.MAX_VALUE} bez
     *         kolize) – pathfinding podle něj posuzuje, jestli buňka překáží
     *         tělu v úrovni hlavy (horní poklop u stropu nevadí, deska ano)
     */
    public double lowestCollisionStart() {
        if (boxes.length == 0) {
            return Double.MAX_VALUE;
        }
        double min = Double.MAX_VALUE;
        for (int i = 0; i < boxes.length; i += 6) {
            min = Math.min(min, boxes[i + 1]);
        }
        return min;
    }

    /**
     * @param material materiál bloku
     * @return vlastnosti bloku podle materiálu (cachované)
     */
    public static synchronized BlockTraits of(Material material) {
        if (material == null || material.isAir()) {
            return AIR;
        }
        return CACHE.computeIfAbsent(material, BlockTraits::compute);
    }

    /**
     * Vlastnosti podle celého block state – poloviny desek a schodů, vrstvy
     * sněhu, otevřenost dveří, waterlogging, přesné kolizní boxy.
     *
     * @param data block data; {@code null} vrací {@link #UNKNOWN}
     * @return vlastnosti bloku (cachované per state)
     */
    public static BlockTraits of(BlockData data) {
        if (data == null) {
            return UNKNOWN;
        }
        BlockTraits cached = STATE_CACHE.get(data);
        if (cached != null) {
            return cached;
        }
        BlockTraits computed = computeState(data);
        STATE_CACHE.putIfAbsent(data, computed);
        return computed;
    }

    /**
     * @param material materiál bloku
     * @return {@code true} pokud se vlastnosti bloku liší podle stavu a stojí
     *         za to sáhnout po block datech ({@link #of(BlockData)})
     */
    public static boolean stateSensitive(Material material) {
        return material != null && StateSensitive.SET.contains(material);
    }

    // ------------------------------------------------------------------ výpočty

    private static BlockTraits compute(Material m) {
        boolean liquid = m == Material.WATER || m == Material.LAVA
                // Vodní rostliny a bublinový sloupec jsou vždy „zvodnělé" – pro
                // pohyb je to vodní sloupec, ne vzduch (bot jimi plave, nepadá).
                || m == Material.KELP || m == Material.KELP_PLANT
                || m == Material.SEAGRASS || m == Material.TALL_SEAGRASS
                || m == Material.BUBBLE_COLUMN;
        boolean climbable = m == Material.LADDER || m == Material.VINE
                || m == Material.TWISTING_VINES || m == Material.TWISTING_VINES_PLANT
                || m == Material.WEEPING_VINES || m == Material.WEEPING_VINES_PLANT
                || m == Material.SCAFFOLDING;
        boolean doorLike = Tag.DOORS.isTagged(m) || Tag.FENCE_GATES.isTagged(m);
        // Železné dveře/poklop ruka neotevře – pro bota jsou to zdi.
        boolean handOpenable = doorLike && m != Material.IRON_DOOR;
        boolean hazard = m == Material.LAVA || m == Material.FIRE || m == Material.SOUL_FIRE
                || m == Material.MAGMA_BLOCK || m == Material.CACTUS || m == Material.SWEET_BERRY_BUSH
                || m == Material.WITHER_ROSE || m == Material.POWDER_SNOW || m == Material.CAMPFIRE
                || m == Material.SOUL_CAMPFIRE;
        boolean openable = doorLike || Tag.TRAPDOORS.isTagged(m);
        boolean web = m == Material.COBWEB;
        boolean portal = m == Material.NETHER_PORTAL || m == Material.END_PORTAL
                || m == Material.END_GATEWAY;
        // occluding = plný neprůhledný blok; isSolid zahrnuje i ploty apod.
        boolean solid = m.isSolid() && !doorLike && !Tag.TRAPDOORS.isTagged(m);
        boolean passable = !solid && !liquid && !hazard;
        boolean softLanding = m == Material.HAY_BLOCK || m == Material.SLIME_BLOCK
                || m == Material.HONEY_BLOCK || Tag.BEDS.isTagged(m);
        boolean powderSnow = m == Material.POWDER_SNOW;

        double speed = m == Material.SOUL_SAND || m == Material.HONEY_BLOCK ? 0.4 : 1.0;
        double slip = slipperinessOf(m);

        double[] boxes = materialBoxes(m, solid, handOpenable);
        return new BlockTraits(passable, solid, liquid, climbable,
                // Materiálová úroveň nezná otevřenost – dveře/vrata se berou jako
                // zavřené (bot je před průchodem otevře; otevřený stav zjemní
                // až computeState).
                handOpenable, hazard, openable, softLanding, powderSnow, portal,
                floorOf(boxes), speed, slip, web, stepFriendlyOf(boxes), boxes);
    }

    /** Kolizní boxy odhadnuté z materiálu (bez znalosti stavu). */
    private static double[] materialBoxes(Material m, boolean solid, boolean handOpenable) {
        if (Tag.FENCES.isTagged(m) || Tag.WALLS.isTagged(m) || Tag.FENCE_GATES.isTagged(m)) {
            return TALL_BOXES; // 1,5 bloku – nepřeskočitelné, nepřekročitelné
        }
        if (Tag.DOORS.isTagged(m)) {
            // Zavřené dveře blokují průchod (fyzika); pathfinding je pouští
            // přes door flag a bot je otevře interakcí.
            return FULL_BOXES;
        }
        if (Tag.TRAPDOORS.isTagged(m)) {
            // Bez stavu nevíme, zda je poklop otevřený a kde je pant – tenká
            // podlaha u země je nejčastější použití (mostky, vstupy).
            return new double[]{0, 0, 0, 1, 0.1875, 1};
        }
        if (Tag.BEDS.isTagged(m)) {
            return new double[]{0, 0, 0, 1, 0.5625, 1};
        }
        if (m == Material.SNOW) {
            return NO_BOXES; // jedna vrstva – bez kolize; víc vrstev řeší computeState
        }
        return solid ? FULL_BOXES : NO_BOXES;
    }

    /** Kluzkost povrchu (vanilla hodnoty). */
    private static double slipperinessOf(Material m) {
        return switch (m) {
            case ICE, PACKED_ICE, FROSTED_ICE -> 0.98;
            case BLUE_ICE -> 0.989;
            case SLIME_BLOCK -> 0.8;
            default -> DEFAULT_SLIPPERINESS;
        };
    }

    /** Vlastnosti z celého block state. */
    private static BlockTraits computeState(BlockData data) {
        Material m = data.getMaterial();
        BlockTraits base = of(m);

        boolean liquid = base.liquid()
                || (data instanceof Waterlogged wl && wl.isWaterlogged());

        boolean open = data instanceof Openable o && o.isOpen();
        // door flag = zavřená překážka otevíratelná rukou (dveře, vrata, branky).
        boolean door = base.door() && !open;

        double[] boxes = stateBoxes(data, base, open);
        double floor = floorOf(boxes);
        boolean passable = !liquid && !base.hazard() && floor <= 0.0625 + 1.0E-9;

        return new BlockTraits(passable, base.solid(), liquid, base.climbable(), door,
                base.hazard(), base.openable(), base.softLanding(), base.powderSnow(),
                base.portal(), floor, base.speedFactor(), base.slipperiness(), base.web(),
                stepFriendlyOf(boxes), boxes);
    }

    /** Kolizní boxy pro konkrétní stav – přesné z registrů serveru, jinak heuristika. */
    private static double[] stateBoxes(BlockData data, BlockTraits base, boolean open) {
        // Šplhatelné bloky bereme bez kolize i tehdy, když tvar nějakou mají
        // (lešení) – bot jimi prochází a šplhá, kolize by ho vytlačovala.
        if (base.climbable()) {
            return NO_BOXES;
        }
        // Otevřené dveře/vrata/poklopy: zbylý panel je tenký a u kraje buňky,
        // bot prochází středem – kolizi ignorujeme (jinak by přesný tvar
        // panelu udělal z otevřených dveří překážku).
        if (open) {
            return NO_BOXES;
        }
        double[] exact = dev.botalive.core.world.state.CollisionShapes.boxesOf(data);
        if (exact != null) {
            return exact;
        }
        // Heuristika z API vlastností (bez interních registrů).
        if (data instanceof Slab slab) {
            return switch (slab.getType()) {
                case BOTTOM -> new double[]{0, 0, 0, 1, 0.5, 1};
                case TOP -> new double[]{0, 0.5, 0, 1, 1, 1};
                case DOUBLE -> FULL_BOXES;
            };
        }
        if (data instanceof Stairs stairs) {
            return stairBoxes(stairs);
        }
        if (data instanceof Snow snow) {
            int layers = snow.getLayers();
            return layers <= 1 ? NO_BOXES : new double[]{0, 0, 0, 1, (layers - 1) * 0.125, 1};
        }
        if (data instanceof TrapDoor trapDoor) {
            return trapDoor.getHalf() == Bisected.Half.TOP
                    ? new double[]{0, 0.8125, 0, 1, 1, 1}
                    : new double[]{0, 0, 0, 1, 0.1875, 1};
        }
        return base.boxes();
    }

    /** Přibližné boxy schodů: podstava + zábradlí na zadní polovině dle orientace. */
    private static double[] stairBoxes(Stairs stairs) {
        boolean top = stairs.getHalf() == Bisected.Half.TOP;
        double[] slabPart = top
                ? new double[]{0, 0.5, 0, 1, 1, 1}
                : new double[]{0, 0, 0, 1, 0.5, 1};
        // Zábradlí (riser) na straně, KAM schody míří (facing = směr stoupání).
        double[] riser = switch (stairs.getFacing()) {
            case NORTH -> new double[]{0, top ? 0 : 0.5, 0, 1, top ? 0.5 : 1, 0.5};
            case SOUTH -> new double[]{0, top ? 0 : 0.5, 0.5, 1, top ? 0.5 : 1, 1};
            case WEST -> new double[]{0, top ? 0 : 0.5, 0, 0.5, top ? 0.5 : 1, 1};
            case EAST -> new double[]{0.5, top ? 0 : 0.5, 0, 1, top ? 0.5 : 1, 1};
            default -> FULL_BOXES;
        };
        if (stairs.getShape() == Stairs.Shape.INNER_LEFT || stairs.getShape() == Stairs.Shape.INNER_RIGHT) {
            // Vnitřní roh má zábradlí do L – konzervativně celé horní patro.
            return FULL_BOXES;
        }
        double[] result = new double[12];
        System.arraycopy(slabPart, 0, result, 0, 6);
        System.arraycopy(riser, 0, result, 6, 6);
        return result;
    }

    /** Nejvyšší strop kolize v buňce (pochozí plocha). */
    private static double floorOf(double[] boxes) {
        double max = 0;
        for (int i = 0; i < boxes.length; i += 6) {
            max = Math.max(max, boxes[i + 4]);
        }
        return max;
    }

    /**
     * Tvoří kolize schůdky ≤ 0.6? (výstup o celý blok bez skoku – schody).
     * Vyžaduje nejnižší stupeň ≤ 0.6 a mezistupně po ≤ 0.6.
     */
    private static boolean stepFriendlyOf(double[] boxes) {
        if (boxes.length == 0) {
            return false;
        }
        double floor = floorOf(boxes);
        if (floor <= 0.6 || floor > 1.01) {
            return false; // nízké zvládne step-up přímo; ploty nejdou vůbec
        }
        // Seřadit stropy boxů a zkontrolovat schodovitost od země.
        double[] tops = new double[boxes.length / 6];
        for (int i = 0; i < tops.length; i++) {
            tops[i] = boxes[i * 6 + 4];
        }
        java.util.Arrays.sort(tops);
        double reached = 0;
        for (double top : tops) {
            if (top - reached > 0.6) {
                return false;
            }
            reached = Math.max(reached, top);
        }
        return true;
    }

    private static Set<Material> buildStateSensitive() {
        Set<Material> set = EnumSet.noneOf(Material.class);
        set.addAll(Tag.DOORS.getValues());
        set.addAll(Tag.TRAPDOORS.getValues());
        set.addAll(Tag.FENCE_GATES.getValues());
        set.addAll(Tag.SLABS.getValues());
        set.addAll(Tag.STAIRS.getValues());
        set.addAll(Tag.BEDS.getValues());
        // Waterlogging mění pohyb (plavání) – ploty a zídky pod hladinou.
        set.addAll(Tag.FENCES.getValues());
        set.addAll(Tag.WALLS.getValues());
        set.add(Material.SNOW);
        return set;
    }
}
