package dev.botalive.core.physics;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.BlockTraits;
import dev.botalive.core.world.WorldView;

/**
 * Tekutinové reflexy – poslední slovo nad pohybovým vstupem.
 *
 * <p>Instinkty, které má hráč „v rukou" bez přemýšlení a bot je potřebuje
 * nezávisle na tom, jaký cíl zrovna běží:</p>
 * <ul>
 *   <li><b>Láva:</b> okamžitá panika – držet skok (v lávě se stoupá stejně
 *       jako ve vodě) a hrabat se k nejbližšímu bezpečnému bloku v okolí.
 *       Přebíjí všechno včetně navigace.</li>
 *   <li><b>Voda:</b> s hlavou pod hladinou vyplavat. Nepřebíjí aktivní
 *       navigaci (ta si vodu řídí sama včetně záměrného potápění) – chrání
 *       boty stojící na místě a cíle, které si pohyb řídí ručně.</li>
 * </ul>
 */
public final class LiquidReflex {

    /** Poloměr hledání bezpečného bloku při úniku z lávy. */
    private static final int ESCAPE_RADIUS = 5;
    /** Poloměr hledání vzduchové kapsy při docházejícím dechu. */
    private static final int AIR_RADIUS = 8;
    /** Od kolika ticků pod hladinou se bot začne bát o dech (vanilla ~300). */
    private static final int LOW_AIR_TICKS = 150;
    private static final double EPS = 1.0E-4;

    private LiquidReflex() {
    }

    /**
     * Aplikuje reflexy na pohybový vstup.
     *
     * @param input          vstup od navigace/cíle
     * @param navDriven      vstup pochází z navigace s aktivní cestou (voda se nechá být)
     * @param position       pozice nohou bota
     * @param submergedTicks ticky v kuse s hlavou pod hladinou (dech)
     * @param world          pohled na svět
     * @return upravený vstup (beze změny, když žádný reflex nezasahuje)
     */
    public static MoveInput apply(MoveInput input, boolean navDriven, Vec3 position,
                                  int submergedTicks, WorldView world) {
        if (world == null) {
            return input;
        }
        BlockPos feet = position.toBlockPos();
        BlockTraits feetTraits = world.traitsAt(feet);
        BlockTraits headTraits = world.traitsAt(feet.up());

        // Láva: panika. Skok drží bota u hladiny, směr vede k nejbližšímu bezpečí.
        if (isBurningLiquid(feetTraits) || isBurningLiquid(headTraits)) {
            Vec3 escape = towardNearestSafe(world, feet);
            Vec3 direction = escape.horizontalLength() > EPS ? escape : input.direction();
            return new MoveInput(direction, false, true, false);
        }

        boolean submerged = headTraits.liquid() && !headTraits.hazard();

        // Dochází dech: přebíjí VŠECHNO včetně navigace (utopení je horší než
        // rozbitá cesta). Plavat k nejbližší DOPLAVATELNÉ vzduchové kapse
        // a držet skok – v zatopené jeskyni se stropem samotné stoupání nestačí.
        if (submerged && submergedTicks >= LOW_AIR_TICKS) {
            BlockPos air = nearestReachableAir(world, feet);
            Vec3 direction;
            if (air != null) {
                // Kapsa přímo nad hlavou → jen stoupat (vodorovně stát).
                direction = new Vec3(air.x() - feet.x(), 0, air.z() - feet.z())
                        .horizontal().normalized();
            } else {
                direction = input.direction(); // nic v dosahu – aspoň stoupat
            }
            return new MoveInput(direction, false, true, false);
        }

        // Voda: hlava pod hladinou → vyplavat. Aktivní navigace si plave sama;
        // stojící bot nebo cíl s ručním pohybem by se ale potopil a utopil.
        if (submerged && !input.jump()
                && (!navDriven || input.direction().horizontalLength() < EPS)) {
            return new MoveInput(input.direction(), input.sprint(), true, input.sneak());
        }
        return input;
    }

    /**
     * Najde nejbližší buňku, kde se dá nadechnout a KAM SE DÁ DOPLAVAT:
     * BFS skrz vodní těleso (a vzduch nad hladinou) od pozice bota – kapsy
     * za zdí nebo nad stropem jeskyně nejsou řešení, i když jsou blízko.
     *
     * @param world pohled na svět
     * @param feet  pozice nohou bota (pod hladinou)
     * @return pozice nohou u nejbližšího vzduchu, nebo {@code null}
     */
    static BlockPos nearestReachableAir(WorldView world, BlockPos feet) {
        java.util.ArrayDeque<BlockPos> queue = new java.util.ArrayDeque<>();
        java.util.HashSet<Long> visited = new java.util.HashSet<>();
        queue.add(feet);
        visited.add(feet.asLong());
        while (!queue.isEmpty()) {
            BlockPos cell = queue.poll();
            if (breathable(world, cell) && !cell.equals(feet)) {
                return cell;
            }
            for (BlockPos next : new BlockPos[]{cell.up(), cell.down(),
                    cell.offset(1, 0, 0), cell.offset(-1, 0, 0),
                    cell.offset(0, 0, 1), cell.offset(0, 0, -1)}) {
                if (Math.abs(next.x() - feet.x()) > AIR_RADIUS
                        || Math.abs(next.y() - feet.y()) > AIR_RADIUS
                        || Math.abs(next.z() - feet.z()) > AIR_RADIUS
                        || !visited.add(next.asLong())) {
                    continue;
                }
                if (swimmable(world.traitsAt(next))) {
                    queue.add(next);
                }
            }
        }
        return null;
    }

    /** Buňka, kterou lze proplavat/projít (voda nebo volný prostor, bez hazardu). */
    private static boolean swimmable(BlockTraits traits) {
        if (traits == BlockTraits.UNKNOWN || traits.hazard() || traits.web()) {
            return false;
        }
        return traits.liquid() || traits.lowProfile();
    }

    /** V buňce se dá nadechnout: hlava mimo vodu, voda nebo opora pod nohama. */
    private static boolean breathable(WorldView world, BlockPos cell) {
        BlockTraits head = world.traitsAt(cell.up());
        if (head == BlockTraits.UNKNOWN || head.liquid() || head.hazard()
                || !head.lowProfile()) {
            return false;
        }
        BlockTraits at = world.traitsAt(cell);
        if (at.liquid()) {
            return true; // hladina – šlapání vody s hlavou venku
        }
        BlockTraits below = world.traitsAt(cell.down());
        return below.liquid() || below.floorHeight() > 0; // břeh/mělčina
    }

    /** Hořlavá tekutina (láva) – liquid s hazardem. */
    private static boolean isBurningLiquid(BlockTraits traits) {
        return traits.liquid() && traits.hazard();
    }

    /**
     * Vodorovný jednotkový směr k nejbližší bezpečné pozici v okolí
     * (pochozí, bez hazardu), nebo {@link Vec3#ZERO} když žádná není.
     * Sdíleno s {@link PowderSnowReflex} (únik z prašanu).
     */
    static Vec3 towardNearestSafe(WorldView world, BlockPos feet) {
        BlockPos best = null;
        int bestDistSq = Integer.MAX_VALUE;
        for (int dx = -ESCAPE_RADIUS; dx <= ESCAPE_RADIUS; dx++) {
            for (int dz = -ESCAPE_RADIUS; dz <= ESCAPE_RADIUS; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                for (int dy = -1; dy <= 2; dy++) {
                    BlockPos candidate = feet.offset(dx, dy, dz);
                    if (!isSafeStand(world, candidate)) {
                        continue;
                    }
                    int distSq = dx * dx + dy * dy + dz * dz;
                    if (distSq < bestDistSq) {
                        bestDistSq = distSq;
                        best = candidate;
                    }
                }
            }
        }
        if (best == null) {
            return Vec3.ZERO;
        }
        return new Vec3(best.x() - feet.x(), 0, best.z() - feet.z()).horizontal().normalized();
    }

    /** Bezpečné stání: průchozí tělo bez hazardu, pevná podlaha bez hazardu. */
    static boolean isSafeStand(WorldView world, BlockPos feet) {
        BlockTraits feetTraits = world.traitsAt(feet);
        BlockTraits headTraits = world.traitsAt(feet.up());
        BlockTraits below = world.traitsAt(feet.down());
        return feetTraits.passable() && headTraits.passable()
                && below.solid() && !below.hazard()
                && !feetTraits.hazard() && !headTraits.hazard();
    }
}
