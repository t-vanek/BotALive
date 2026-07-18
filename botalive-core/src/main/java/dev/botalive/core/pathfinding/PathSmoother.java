package dev.botalive.core.pathfinding;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.BlockTraits;
import dev.botalive.core.world.WorldView;

import java.util.List;

/**
 * Vyhlazení trasy – „string pulling" nad pochozí plochou.
 *
 * <p>A* vrací lomenou čáru přes středy bloků; člověk ale rohy řeže. Smoother
 * najde nejvzdálenější waypoint (v okně {@link #LOOKAHEAD}), ke kterému vede
 * z aktuální pozice přímý pochozí koridor, a bot míří rovnou na něj – chůze
 * dostane přirozené oblouky a diagonály místo pravítka.</p>
 *
 * <p>Vyhlazuje se jen po rovině (stejné Y waypointů) a mimo skokové segmenty;
 * skoky, seskoky, šplhání a plavání zůstávají na přesných waypointech.
 * Koridor se vzorkuje po půl bloku včetně rohů hitboxu – zkratka nesmí
 * škrtnout o zeď, sráz, pavučinu ani pomalý povrch nevadí (jen tvar).</p>
 */
public final class PathSmoother {

    /** Kolik waypointů dopředu se zkouší vyhladit. */
    public static final int LOOKAHEAD = 6;

    /** Poloviční šíře hitboxu s rezervou (kontrola rohů koridoru). */
    private static final double HALF_WIDTH = 0.35;
    /** Hustota vzorkování přímky (bloky). */
    private static final double SAMPLE_STEP = 0.5;
    private static final double EPS = 1.0E-6;

    private PathSmoother() {
    }

    /**
     * Najde index nejvzdálenějšího waypointu dosažitelného přímou chůzí.
     *
     * @param world     pohled na svět
     * @param position  aktuální pozice bota (nohy)
     * @param waypoints waypointy cesty
     * @param fromIndex index aktuálního waypointu
     * @return index z intervalu {@code [fromIndex, fromIndex+LOOKAHEAD]};
     *         {@code fromIndex} když se vyhladit nedá
     */
    public static int smoothTarget(WorldView world, Vec3 position,
                                   List<BlockPos> waypoints, int fromIndex) {
        if (fromIndex >= waypoints.size()) {
            return fromIndex;
        }
        int last = fromIndex;
        int baseY = waypoints.get(fromIndex).y();
        int limit = Math.min(fromIndex + LOOKAHEAD, waypoints.size() - 1);
        BlockPos prev = waypoints.get(fromIndex);
        for (int i = fromIndex + 1; i <= limit; i++) {
            BlockPos wp = waypoints.get(i);
            if (wp.y() != baseY) {
                break; // svislá změna – skok/seskok/šplhání nechat lomené
            }
            if (Math.abs(wp.x() - prev.x()) > 1 || Math.abs(wp.z() - prev.z()) > 1) {
                break; // skokový segment (mezera) – vyžaduje přesný rozběh
            }
            if (!corridorWalkable(world, position, wp, baseY)) {
                break;
            }
            last = i;
            prev = wp;
        }
        return last;
    }

    /** Je přímka od pozice ke středu waypointu pochozí v celé šíři hitboxu? */
    private static boolean corridorWalkable(WorldView world, Vec3 from, BlockPos to, int y) {
        double tx = to.x() + 0.5;
        double tz = to.z() + 0.5;
        double dx = tx - from.x();
        double dz = tz - from.z();
        double dist = Math.hypot(dx, dz);
        if (dist < EPS) {
            return true;
        }
        int samples = (int) Math.ceil(dist / SAMPLE_STEP);
        for (int s = 1; s <= samples; s++) {
            double px = from.x() + dx * s / samples;
            double pz = from.z() + dz * s / samples;
            if (!columnWalkable(world, px - HALF_WIDTH, y, pz - HALF_WIDTH)
                    || !columnWalkable(world, px + HALF_WIDTH, y, pz - HALF_WIDTH)
                    || !columnWalkable(world, px - HALF_WIDTH, y, pz + HALF_WIDTH)
                    || !columnWalkable(world, px + HALF_WIDTH, y, pz + HALF_WIDTH)) {
                return false;
            }
        }
        return true;
    }

    /** Pochozí sloupec: nízká/žádná překážka v nohách, opora, volná hlava. */
    private static boolean columnWalkable(WorldView world, double x, int y, double z) {
        BlockPos feet = new BlockPos((int) Math.floor(x), y, (int) Math.floor(z));
        BlockTraits t = world.traitsAt(feet);
        if (t == BlockTraits.UNKNOWN || t.hazard() || t.web() || t.liquid()) {
            return false;
        }
        double fh = t.floorHeight();
        if (fh > 0.6) {
            return false; // vyšší práh (plný blok, plot) – tudy zkratka nevede
        }
        if (fh <= 0) {
            BlockTraits below = world.traitsAt(feet.down());
            if (below == BlockTraits.UNKNOWN
                    || below.floorHeight() < 0.99 || below.floorHeight() > 1.01) {
                return false; // bez plné opory pod nohama se neřeže (sráz, deska níž)
            }
        }
        BlockTraits head = world.traitsAt(feet.up());
        return head != BlockTraits.UNKNOWN && !head.hazard() && !head.web()
                && !head.liquid() && head.lowProfile();
    }
}
