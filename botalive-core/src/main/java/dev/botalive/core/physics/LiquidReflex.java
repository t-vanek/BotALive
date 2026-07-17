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
    private static final double EPS = 1.0E-4;

    private LiquidReflex() {
    }

    /**
     * Aplikuje reflexy na pohybový vstup.
     *
     * @param input     vstup od navigace/cíle
     * @param navDriven vstup pochází z navigace s aktivní cestou (voda se nechá být)
     * @param position  pozice nohou bota
     * @param world     pohled na svět
     * @return upravený vstup (beze změny, když žádný reflex nezasahuje)
     */
    public static MoveInput apply(MoveInput input, boolean navDriven, Vec3 position, WorldView world) {
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

        // Voda: hlava pod hladinou → vyplavat. Aktivní navigace si plave sama;
        // stojící bot nebo cíl s ručním pohybem by se ale potopil a utopil.
        boolean submerged = headTraits.liquid() && !headTraits.hazard();
        if (submerged && !input.jump()
                && (!navDriven || input.direction().horizontalLength() < EPS)) {
            return new MoveInput(input.direction(), input.sprint(), true, input.sneak());
        }
        return input;
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
