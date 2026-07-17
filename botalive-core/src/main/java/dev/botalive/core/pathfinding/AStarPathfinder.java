package dev.botalive.core.pathfinding;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.BlockTraits;
import dev.botalive.core.world.WorldView;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Vlastní A* pathfinder nad {@link WorldView}.
 *
 * <p>Prohledává „pochozí" pozice (pevný blok pod nohama, průchozí prostor pro
 * tělo). Podporované pohyby: 4 kardinální směry, diagonály (bez řezání rohů),
 * výstup o 1 blok (skok), seskok až o 3 bloky, šplhání po žebřících a plavání.
 * Cenová funkce penalizuje vodu, seskoky a blízkost hazardů (láva, oheň,
 * kaktus), tvrdě zakazuje vstup do hazardu a do nenačtených chunků.</p>
 *
 * <p>Výpočet má rozpočet expandovaných uzlů; při vyčerpání vrací částečnou
 * cestu k uzlu nejblíže cíli – bot se přiblíží a doplánuje. Pathfinder nemá
 * sdílený stav, jedna instance se smí používat z více vláken současně.</p>
 */
public final class AStarPathfinder {

    /** Maximální počet expandovaných uzlů na jeden výpočet. */
    private static final int DEFAULT_NODE_BUDGET = 8_000;

    /** Cena rovného kroku (fixed-point ×10 kvůli int aritmetice). */
    private static final int COST_STRAIGHT = 10;
    private static final int COST_DIAGONAL = 14;
    private static final int COST_JUMP = 8;
    private static final int COST_FALL_PER_BLOCK = 6;
    private static final int COST_WATER = 25;
    private static final int COST_CLIMB = 12;
    private static final int COST_DOOR = 15;
    private static final int COST_NEAR_HAZARD = 60;
    /** Přirážka za svislý záběr při plavání (stoupání je dřina). */
    private static final int COST_SWIM_VERTICAL = 6;

    /** Maximální bezpečná výška seskoku (bez poškození stojí za to). */
    private static final int MAX_DROP = 3;
    /** Maximální seskok do hluboké vody (dopad do vody neubližuje). */
    private static final int MAX_WATER_DROP = 20;

    /** Ceny za blízkost místa, kde bot zemřel / poznal nebezpečí. */
    private static final int COST_DANGER_NEAR = 80;
    private static final int COST_DANGER = 30;
    private static final int DANGER_NEAR_SQ = 3 * 3;
    private static final int DANGER_FAR_SQ = 6 * 6;

    private final WorldView world;
    private final java.util.List<BlockPos> dangers;

    /**
     * @param world pohled na svět (thread-safe)
     */
    public AStarPathfinder(WorldView world) {
        this(world, java.util.List.of());
    }

    /**
     * @param world   pohled na svět (thread-safe)
     * @param dangers místa špatných vzpomínek (smrti, nebezpečí) – cesta se
     *                jim vyhýbá, existuje-li rozumná obchůzka; průchod se
     *                zdražuje, nezakazuje (bot nesmí uvíznout)
     */
    public AStarPathfinder(WorldView world, java.util.List<BlockPos> dangers) {
        this.world = world;
        this.dangers = dangers == null ? java.util.List.of() : dangers;
    }

    /**
     * Naplánuje cestu.
     *
     * @param start      startovní blok (nohy bota)
     * @param goal       cílový blok
     * @param nodeBudget maximum expandovaných uzlů; {@code <= 0} použije default
     * @return cesta (může být částečná), nebo prázdná cesta pokud start není pochozí
     */
    public Path findPath(BlockPos start, BlockPos goal, int nodeBudget) {
        int budget = nodeBudget > 0 ? nodeBudget : DEFAULT_NODE_BUDGET;

        Node startNode = new Node(start, null, 0, heuristic(start, goal));
        PriorityQueue<Node> open = new PriorityQueue<>();
        Long2ObjectOpenHashMap<Node> visited = new Long2ObjectOpenHashMap<>();
        open.add(startNode);
        visited.put(start.asLong(), startNode);

        Node best = startNode;
        int expanded = 0;

        while (!open.isEmpty() && expanded < budget) {
            Node current = open.poll();
            if (current.closed) {
                continue;
            }
            current.closed = true;
            expanded++;

            if (current.pos.equals(goal)) {
                return new Path(reconstruct(current), true);
            }
            if (current.h < best.h) {
                best = current;
            }
            expandNeighbors(current, goal, open, visited);
        }
        // Rozpočet vyčerpán – vrátit nejlepší částečnou cestu (pokud někam vede).
        List<BlockPos> partial = reconstruct(best);
        if (partial.size() <= 1) {
            logDeadStart(start, goal, expanded);
        }
        return new Path(partial, false);
    }

    /** Diagnostika mrtvého startu: mapa traits okolí (S=solid W=liquid .=passable ?=unknown). */
    private void logDeadStart(BlockPos start, BlockPos goal, int expanded) {
        StringBuilder sb = new StringBuilder();
        for (int dy = 2; dy >= -1; dy--) {
            sb.append("y").append(dy >= 0 ? "+" : "").append(dy).append("[");
            for (int dz = -1; dz <= 1; dz++) {
                for (int dx = -1; dx <= 1; dx++) {
                    BlockTraits t = world.traitsAt(start.offset(dx, dy, dz));
                    sb.append(t == BlockTraits.UNKNOWN ? '?'
                            : t.solid() ? 'S' : t.liquid() ? 'W' : t.passable() ? '.' : 'x');
                }
                sb.append(dz < 1 ? '|' : ']');
            }
            sb.append(' ');
        }
        org.slf4j.LoggerFactory.getLogger(AStarPathfinder.class).debug(
                "[astar] mrtvý start {} → {} (expanded={}): {}", start, goal, expanded, sb);
    }

    /** Vygeneruje sousedy uzlu a zařadí je do open setu. */
    private void expandNeighbors(Node current, BlockPos goal,
                                 PriorityQueue<Node> open, Long2ObjectOpenHashMap<Node> visited) {
        BlockPos pos = current.pos;

        // Kardinální + diagonální pohyby.
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                boolean diagonal = dx != 0 && dz != 0;
                if (diagonal && !canCutCorner(pos, dx, dz)) {
                    continue;
                }
                stepTowards(current, pos.offset(dx, 0, dz), diagonal, goal, open, visited);
            }
        }

        // Šplhání: žebřík/liána v aktuální pozici → pohyb nahoru/dolů.
        if (world.traitsAt(pos).climbable()) {
            tryAdd(current, pos.up(), COST_CLIMB, goal, open, visited);
            tryAdd(current, pos.down(), COST_CLIMB, goal, open, visited);
        }

        // Plavání svisle: ve vodním sloupci se bot může vynořit i potopit.
        BlockTraits here = world.traitsAt(pos);
        if (here.liquid() && !here.hazard()) {
            if (isStandable(pos.up())) {
                tryAdd(current, pos.up(), COST_WATER + COST_SWIM_VERTICAL, goal, open, visited);
            }
            if (isStandable(pos.down())) {
                tryAdd(current, pos.down(), COST_WATER, goal, open, visited);
            }
        }
    }

    /**
     * Krok na sousední sloupec: rovně, skok o 1 nahoru, nebo seskok až o {@link #MAX_DROP}.
     */
    private void stepTowards(Node current, BlockPos target, boolean diagonal, BlockPos goal,
                             PriorityQueue<Node> open, Long2ObjectOpenHashMap<Node> visited) {
        int base = diagonal ? COST_DIAGONAL : COST_STRAIGHT;

        if (isStandable(target)) {
            tryAdd(current, target, base + terrainPenalty(target), goal, open, visited);
            return;
        }

        // Skok o blok výš (jen ne-diagonálně, potřebuje volný strop nad hlavou).
        if (!diagonal) {
            BlockPos jumpTarget = target.up();
            if (isStandable(jumpTarget)
                    && isPassable(current.pos.offset(0, 2, 0))
                    && isPassable(target.offset(0, 2, 0))) {
                tryAdd(current, jumpTarget, base + COST_JUMP + terrainPenalty(jumpTarget), goal, open, visited);
                return;
            }
        }

        // Seskok: sloupec pod cílem musí být průchozí až k dopadu. Do výšky
        // MAX_DROP kamkoli; hlouběji jen do vody hluboké aspoň 2 (dopad do
        // mělčiny nebo na zem by bota zranil či zabil).
        if (isPassable(target) && isPassable(target.up())) {
            BlockPos drop = target;
            for (int depth = 1; depth <= MAX_WATER_DROP; depth++) {
                drop = drop.down();
                if (isStandable(drop)) {
                    BlockTraits landing = world.traitsAt(drop);
                    boolean deepWater = landing.liquid() && !landing.hazard()
                            && world.traitsAt(drop.down()).liquid();
                    if (depth > MAX_DROP && !deepWater) {
                        return; // vysoký pád bez vodní jistoty – nejdeme
                    }
                    int cost = base + Math.min(depth, MAX_DROP + 2) * COST_FALL_PER_BLOCK
                            + terrainPenalty(drop);
                    tryAdd(current, drop, cost, goal, open, visited);
                    return;
                }
                if (!isPassable(drop)) {
                    return; // narazili jsme do zdi/hazardu – seskok nelze
                }
            }
        }
    }

    /** Diagonála je povolená jen, když jsou oba přiléhající sloupce průchozí (žádné řezání rohů). */
    private boolean canCutCorner(BlockPos pos, int dx, int dz) {
        return isPassable(pos.offset(dx, 0, 0)) && isPassable(pos.offset(dx, 1, 0))
                && isPassable(pos.offset(0, 0, dz)) && isPassable(pos.offset(0, 1, dz));
    }

    /** Přidá uzel do open setu, pokud zlepšuje dosavadní cestu. */
    private void tryAdd(Node parent, BlockPos pos, int moveCost, BlockPos goal,
                        PriorityQueue<Node> open, Long2ObjectOpenHashMap<Node> visited) {
        long key = pos.asLong();
        int g = parent.g + moveCost + dangerPenalty(pos);
        Node existing = visited.get(key);
        if (existing != null && existing.g <= g) {
            return;
        }
        Node node = new Node(pos, parent, g, heuristic(pos, goal));
        visited.put(key, node);
        open.add(node);
    }

    /** Přirážka za krok poblíž špatné vzpomínky (smrt, nebezpečí). */
    private int dangerPenalty(BlockPos pos) {
        if (dangers.isEmpty()) {
            return 0;
        }
        int penalty = 0;
        for (BlockPos danger : dangers) {
            double distSq = pos.distanceSquared(danger);
            if (distSq <= DANGER_NEAR_SQ) {
                penalty += COST_DANGER_NEAR;
            } else if (distSq <= DANGER_FAR_SQ) {
                penalty += COST_DANGER;
            }
        }
        return penalty;
    }

    /** Oktilová heuristika (konzistentní pro 8-směrový pohyb). */
    private static int heuristic(BlockPos from, BlockPos to) {
        int dx = Math.abs(from.x() - to.x());
        int dy = Math.abs(from.y() - to.y());
        int dz = Math.abs(from.z() - to.z());
        int min = Math.min(dx, dz);
        int max = Math.max(dx, dz);
        return COST_DIAGONAL * min + COST_STRAIGHT * (max - min) + COST_STRAIGHT * dy;
    }

    /**
     * Pochozí pozice: pevná podlaha pod nohama (nebo voda/žebřík v pozici)
     * a průchozí prostor pro nohy i hlavu.
     */
    private boolean isStandable(BlockPos feet) {
        BlockTraits feetTraits = world.traitsAt(feet);
        BlockTraits headTraits = world.traitsAt(feet.up());
        if (feetTraits == BlockTraits.UNKNOWN || headTraits == BlockTraits.UNKNOWN) {
            return false;
        }
        boolean bodyFits = bodyPassable(feetTraits) && bodyPassable(headTraits);
        if (!bodyFits) {
            return false;
        }
        if (feetTraits.liquid() && !feetTraits.hazard()) {
            return true; // plavání ve vodě
        }
        if (feetTraits.climbable()) {
            return true; // držení na žebříku
        }
        BlockTraits below = world.traitsAt(feet.down());
        return below.solid();
    }

    /** Prostor pro tělo – průchozí, voda (ne láva), žebřík nebo otevíratelné dveře. */
    private static boolean bodyPassable(BlockTraits traits) {
        if (traits.hazard()) {
            return false;
        }
        return traits.passable() || traits.liquid() || traits.climbable() || traits.door();
    }

    /** Průchozí prostor (bez hazardu, bez pevného bloku, bez „neznáma"). */
    private boolean isPassable(BlockPos pos) {
        BlockTraits traits = world.traitsAt(pos);
        return traits != BlockTraits.UNKNOWN && bodyPassable(traits);
    }

    /** Penalizace terénu: voda, dveře, sousedství hazardu. */
    private int terrainPenalty(BlockPos feet) {
        int penalty = 0;
        BlockTraits feetTraits = world.traitsAt(feet);
        if (feetTraits.liquid()) {
            penalty += COST_WATER;
        }
        if (feetTraits.door()) {
            penalty += COST_DOOR;
        }
        // Hazard v okolí 1 bloku → vysoká penalizace (bot se drží dál od lávy).
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (world.traitsAt(feet.offset(dx, 0, dz)).hazard()
                        || world.traitsAt(feet.offset(dx, -1, dz)).hazard()) {
                    return penalty + COST_NEAR_HAZARD;
                }
            }
        }
        return penalty;
    }

    /** Zrekonstruuje cestu od cílového uzlu ke startu. */
    private static List<BlockPos> reconstruct(Node node) {
        List<BlockPos> path = new ArrayList<>();
        for (Node n = node; n != null; n = n.parent) {
            path.add(n.pos);
        }
        Collections.reverse(path);
        if (!path.isEmpty()) {
            path.removeFirst(); // startovní pozici bot už má
        }
        return path;
    }

    /** Uzel A* – mutable kvůli výkonu (open set s lazy mazáním). */
    private static final class Node implements Comparable<Node> {
        final BlockPos pos;
        final Node parent;
        final int g;
        final int h;
        boolean closed;

        Node(BlockPos pos, Node parent, int g, int h) {
            this.pos = pos;
            this.parent = parent;
            this.g = g;
            this.h = h;
        }

        @Override
        public int compareTo(Node o) {
            return Integer.compare(g + h, o.g + o.h);
        }
    }
}
