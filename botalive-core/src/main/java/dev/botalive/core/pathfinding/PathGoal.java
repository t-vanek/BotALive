package dev.botalive.core.pathfinding;

import dev.botalive.core.util.BlockPos;

import java.util.List;

/**
 * Cíl hledání cesty – predikát dosažení + přípustná heuristika.
 *
 * <p>„Dojdi na blok" je jen jeden druh cíle. Cíle botů častěji zní „dostaň
 * se k truhle na dosah", „uteč od creepera aspoň na 12 bloků", „sestup na
 * těžební hladinu" nebo „dojdi k nejbližšímu z těchle stromů" – a bez
 * predikátů si je goaly musely překládat na konkrétní blok předem, aby pak
 * zjistily, že je nedosažitelný. A* s predikátem najde nejlevnější
 * vyhovující buňku sám (multi-target zadarmo: hledání skončí u nejbližšího
 * dosažitelného kandidáta).</p>
 *
 * <p>Kontrakt: {@link #heuristic} nesmí přestřelit skutečnou cenu
 * (fixed-point ×10 jako v {@link AStarPathfinder} – vodorovný krok 10,
 * diagonála 14, pád 6/blok), jinak A* ztrácí optimalitu. {@link #anchor}
 * je reprezentativní bod cíle pro prefetch, dálkovou segmentaci a debug –
 * nemusí být dosažitelný. Implementace jsou nemutabilní recordy
 * (equals/hashCode řídí deduplikaci opakovaných {@code navigateTo}).</p>
 */
public interface PathGoal {

    /**
     * @param pos kandidátní buňka (pochozí pozice nohou)
     * @return {@code true} když buňka splňuje cíl
     */
    boolean reached(BlockPos pos);

    /**
     * @param pos buňka
     * @return přípustný (nepřestřelující) odhad ceny k cíli, fixed-point ×10
     */
    int heuristic(BlockPos pos);

    /**
     * @return reprezentativní bod cíle (prefetch, směr segmentů, debug)
     */
    BlockPos anchor();

    /**
     * @return {@code true} pro cíl „přesně tento blok" – jen ten smí
     *         normalizovat cíl nad deskou a používat drift throttle
     *         pohyblivých cílů (porovnávání konců cest s cílem)
     */
    default boolean exactBlock() {
        return false;
    }

    // ------------------------------------------------------------- továrny

    /**
     * @param pos cílový blok
     * @return cíl „dojdi přesně na blok" (dnešní chování)
     */
    static PathGoal block(BlockPos pos) {
        return new Block(pos);
    }

    /**
     * @param center střed oblasti
     * @param radius poloměr dosažení (bloky, 3D)
     * @return cíl „dostaň se do okruhu" – interakce s truhlou, ponkem, entitou
     */
    static PathGoal near(BlockPos center, int radius) {
        return new Near(center, Math.max(0, radius));
    }

    /**
     * @param targets kandidátní bloky (pochozí buňky), aspoň jeden
     * @return cíl „nejbližší dosažitelný z kandidátů" – strom/ruda/truhla se
     *         vybere až podle skutečné dosažitelnosti, ne vzdušnou čarou
     */
    static PathGoal anyOf(List<BlockPos> targets) {
        if (targets.isEmpty()) {
            throw new IllegalArgumentException("anyOf vyžaduje aspoň jednoho kandidáta");
        }
        return new AnyOf(List.copyOf(targets));
    }

    /**
     * @param threat      místo hrozby
     * @param minDistance minimální bezpečná vzdálenost (bloky, vodorovně)
     * @return cíl „uteč aspoň na danou vzdálenost" – plánovaný útěk po
     *         pochozím terénu (žádné hrany, láva ani slepé kouty panického
     *         přímého běhu)
     */
    static PathGoal awayFrom(BlockPos threat, int minDistance) {
        return new AwayFrom(threat, Math.max(1, minDistance));
    }

    /**
     * @param level  cílová výška (Y souřadnice nohou)
     * @param around vodorovné okolí, ve kterém hladinu hledat (kotva)
     * @return cíl „sestup/vystup na hladinu" – těžební patra
     */
    static PathGoal yLevel(int level, BlockPos around) {
        return new YLevel(level, around);
    }

    // -------------------------------------------------------- implementace

    /** Oktilová heuristika k bodu (konzistentní s cenami A*). */
    private static int octile(BlockPos from, BlockPos to) {
        int dx = Math.abs(from.x() - to.x());
        int dy = Math.abs(from.y() - to.y());
        int dz = Math.abs(from.z() - to.z());
        int min = Math.min(dx, dz);
        int max = Math.max(dx, dz);
        return 14 * min + 10 * (max - min) + 10 * dy;
    }

    /** Přesný blok. */
    record Block(BlockPos pos) implements PathGoal {
        @Override
        public boolean reached(BlockPos p) {
            return pos.equals(p);
        }

        @Override
        public int heuristic(BlockPos p) {
            return octile(p, pos);
        }

        @Override
        public BlockPos anchor() {
            return pos;
        }

        @Override
        public boolean exactBlock() {
            return true;
        }
    }

    /** Okruh kolem středu (3D). */
    record Near(BlockPos center, int radius) implements PathGoal {
        @Override
        public boolean reached(BlockPos p) {
            return p.distanceSquared(center) <= (double) radius * radius;
        }

        @Override
        public int heuristic(BlockPos p) {
            return Math.max(0, octile(p, center) - radius * 10);
        }

        @Override
        public BlockPos anchor() {
            return center;
        }
    }

    /** Nejbližší dosažitelný z kandidátů. */
    record AnyOf(List<BlockPos> targets) implements PathGoal {
        @Override
        public boolean reached(BlockPos p) {
            for (BlockPos target : targets) {
                if (target.equals(p)) {
                    return true;
                }
            }
            return false;
        }

        @Override
        public int heuristic(BlockPos p) {
            int best = Integer.MAX_VALUE;
            for (BlockPos target : targets) {
                best = Math.min(best, octile(p, target));
            }
            return best;
        }

        @Override
        public BlockPos anchor() {
            return targets.getFirst();
        }
    }

    /**
     * Útěk od hrozby. Heuristika ×7 na chybějící blok vzdálenosti je pod
     * skutečnou cenou (diagonální krok prodlouží vzdálenost o √2 bloku za
     * cenu 14, tedy ~9,9/blok) – přípustnost drží i diagonální úprky.
     */
    record AwayFrom(BlockPos threat, int minDistance) implements PathGoal {
        @Override
        public boolean reached(BlockPos p) {
            double dx = p.x() - threat.x();
            double dz = p.z() - threat.z();
            return dx * dx + dz * dz >= (double) minDistance * minDistance;
        }

        @Override
        public int heuristic(BlockPos p) {
            double dx = p.x() - threat.x();
            double dz = p.z() - threat.z();
            double missing = minDistance - Math.sqrt(dx * dx + dz * dz);
            return missing <= 0 ? 0 : (int) Math.ceil(missing * 7);
        }

        @Override
        public BlockPos anchor() {
            return threat;
        }
    }

    /**
     * Výšková hladina. Heuristika ×6 na blok převýšení = nejlevnější svislý
     * pohyb (pád 6/blok) – přípustná pro sestup i výstup.
     */
    record YLevel(int level, BlockPos around) implements PathGoal {
        @Override
        public boolean reached(BlockPos p) {
            return p.y() == level;
        }

        @Override
        public int heuristic(BlockPos p) {
            return Math.abs(p.y() - level) * 6;
        }

        @Override
        public BlockPos anchor() {
            return new BlockPos(around.x(), level, around.z());
        }
    }
}
