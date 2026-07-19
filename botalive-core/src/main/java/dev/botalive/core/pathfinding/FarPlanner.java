package dev.botalive.core.pathfinding;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.WorldView;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;

/**
 * Hrubý plánovač dálkových tras – A* nad mřížkou povrchových sond.
 *
 * <p>Přímková segmentace ({@link SegmentPlanner} s laterálními posuny ±24)
 * neumí obejít slepá ramena širší než posun – velké jezero, lávové pole,
 * horský masiv. FarPlanner hledá trasu na hrubé mřížce buněk
 * {@link #CELL}×{@link #CELL} bloků: buňka = povrchová sonda
 * ({@link SegmentPlanner#surfaceAt}) ukotvená na výšce souseda, hrana mezi
 * sousedy existuje, když výškový rozdíl povrchů zvládne pěší low-level plán
 * (≤ {@link #MAX_SURFACE_STEP}). Vodní hladina je průchozí s přirážkou
 * (plave se, ale suchá obchůzka má přednost), láva a void jsou zeď.</p>
 *
 * <p><b>Nenačtené chunky jsou optimisticky průchozí</b> s přirážkou
 * ({@link #UNKNOWN_MULTIPLIER}) a zděděnou výškou – bot smí vyrazit „směrem
 * tam" jako hráč s mapou; skutečný terén vyřeší low-level A* s prefetchem
 * při přiblížení (mezicíle se před použitím znovu ověřují). Rozlišení
 * „nenačteno" vs. „načteno bez povrchu" dává {@link WorldView#isAvailable}.</p>
 *
 * <p>Výsledný {@link Corridor} je posloupnost povrchových bodů po ~CELL
 * blocích; navigátor z něj vybírá mezicíle segmentů místo bodů na přímce.
 * Při vyčerpání rozpočtu se vrací částečný koridor k buňce nejblíže cíli
 * (stejný vzorec jako u hlavního A*) – bot se přiblíží a koridor přepočítá.
 * Třída je čistá (jen {@link WorldView}), bez sdíleného stavu.</p>
 */
public final class FarPlanner {

    /** Velikost buňky hrubé mřížky (bloky). */
    public static final int CELL = 8;

    /** Rozpočet expandovaných buněk (4 000 buněk ≈ obchůzky přes stovky bloků). */
    private static final int MAX_EXPANSIONS = 4_000;

    /** Maximální výškový rozdíl povrchů sousedních buněk (víc = útes/převis). */
    private static final int MAX_SURFACE_STEP = 8;

    private static final int COST_STRAIGHT = 10;
    private static final int COST_DIAGONAL = 14;
    /** Násobič ceny přes vodní hladinu – plave se, ale suchá cesta vyhrává. */
    private static final int WATER_MULTIPLIER = 2;
    /** Násobič ceny přes nenačtený chunk – optimismus s respektem. */
    private static final int UNKNOWN_MULTIPLIER = 3;
    /** Přirážka za výškový rozdíl povrchů (stoupání i klesání stojí kroky). */
    private static final int COST_CLIMB_PER_BLOCK = 2;
    /** Váha blízkosti cíle při výběru částečného koridoru (viz AStarPathfinder). */
    private static final int PARTIAL_H_WEIGHT = 16;

    private FarPlanner() {
    }

    /**
     * Hrubá trasa k dalekému cíli.
     *
     * @param points   povrchové body po ~{@link #CELL} blocích (bez startu);
     *                 body přes nenačtené chunky mají odhadnutou výšku
     * @param complete {@code true} pokud koridor dosáhl buňky cíle;
     *                 {@code false} = částečný (rozpočet) – na konci se přepočítá
     */
    public record Corridor(List<BlockPos> points, boolean complete) {

        /** Prázdný koridor (chyba výpočtu, nepochozí start). */
        public static final Corridor EMPTY = new Corridor(List.of(), false);

        /** @return {@code true} pokud koridor nenabízí žádný bod */
        public boolean isEmpty() {
            return points.isEmpty();
        }
    }

    /**
     * Naplánuje hrubý koridor.
     *
     * @param world pohled na svět (thread-safe)
     * @param from  startovní blok (nohy bota)
     * @param to    cílový blok
     * @return koridor (může být částečný či prázdný)
     */
    public static Corridor plan(WorldView world, BlockPos from, BlockPos to) {
        int goalCx = Math.floorDiv(to.x(), CELL);
        int goalCz = Math.floorDiv(to.z(), CELL);
        int startCx = Math.floorDiv(from.x(), CELL);
        int startCz = Math.floorDiv(from.z(), CELL);

        Node start = new Node(startCx, startCz, from.y(), null, 0,
                heuristic(startCx, startCz, goalCx, goalCz));
        PriorityQueue<Node> open = new PriorityQueue<>();
        Long2ObjectOpenHashMap<Node> visited = new Long2ObjectOpenHashMap<>();
        open.add(start);
        visited.put(key(startCx, startCz), start);

        Node best = start;
        long bestScore = partialScore(start);
        int expanded = 0;

        while (!open.isEmpty() && expanded < MAX_EXPANSIONS) {
            Node current = open.poll();
            if (current.closed) {
                continue;
            }
            current.closed = true;
            expanded++;

            if (current.cx == goalCx && current.cz == goalCz) {
                return new Corridor(reconstruct(current), true);
            }
            long score = partialScore(current);
            if (score < bestScore) {
                best = current;
                bestScore = score;
            }
            expand(world, current, goalCx, goalCz, open, visited);
        }
        return new Corridor(reconstruct(best), false);
    }

    /** Expanduje 8 sousedních buněk (na hrubé mřížce se rohy neřežou). */
    private static void expand(WorldView world, Node current, int goalCx, int goalCz,
                               PriorityQueue<Node> open, Long2ObjectOpenHashMap<Node> visited) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                int cx = current.cx + dx;
                int cz = current.cz + dz;
                int x = cx * CELL + CELL / 2;
                int z = cz * CELL + CELL / 2;
                int base = dx != 0 && dz != 0 ? COST_DIAGONAL : COST_STRAIGHT;

                BlockPos surface = SegmentPlanner.surfaceAt(world, x, current.surfaceY, z);
                int surfaceY;
                int cost;
                if (surface != null) {
                    surfaceY = surface.y();
                    if (Math.abs(surfaceY - current.surfaceY) > MAX_SURFACE_STEP) {
                        continue; // útes/převis – tudy hrubá trasa nevede
                    }
                    boolean water = world.traitsAt(surface).liquid();
                    cost = base * (water ? WATER_MULTIPLIER : 1)
                            + Math.abs(surfaceY - current.surfaceY) * COST_CLIMB_PER_BLOCK;
                } else if (!world.isAvailable(new BlockPos(x, current.surfaceY, z))) {
                    // Nenačtený chunk: optimisticky průchozí s přirážkou a
                    // zděděnou výškou – detail vyřeší low-level plán s
                    // prefetchem při přiblížení.
                    surfaceY = current.surfaceY;
                    cost = base * UNKNOWN_MULTIPLIER;
                } else {
                    continue; // načteno a bez povrchu (láva, void, strop) – zeď
                }

                long cellKey = key(cx, cz);
                int g = current.g + cost;
                Node existing = visited.get(cellKey);
                if (existing != null && existing.g <= g) {
                    continue;
                }
                Node node = new Node(cx, cz, surfaceY, current, g,
                        heuristic(cx, cz, goalCx, goalCz));
                visited.put(cellKey, node);
                open.add(node);
            }
        }
    }

    /** Oktilová heuristika na mřížce buněk. */
    private static int heuristic(int cx, int cz, int goalCx, int goalCz) {
        int dx = Math.abs(cx - goalCx);
        int dz = Math.abs(cz - goalCz);
        int min = Math.min(dx, dz);
        int max = Math.max(dx, dz);
        return COST_DIAGONAL * min + COST_STRAIGHT * (max - min);
    }

    /** Skóre výběru částečného koridoru: blízkost cíli především, cena mírně. */
    private static long partialScore(Node node) {
        return (long) node.h * PARTIAL_H_WEIGHT + node.g;
    }

    private static long key(int cx, int cz) {
        return ((long) cx << 32) ^ (cz & 0xFFFFFFFFL);
    }

    /** Body koridoru od startu k uzlu (bez startovní buňky). */
    private static List<BlockPos> reconstruct(Node node) {
        List<BlockPos> points = new ArrayList<>();
        for (Node n = node; n != null; n = n.parent) {
            points.add(new BlockPos(n.cx * CELL + CELL / 2, n.surfaceY, n.cz * CELL + CELL / 2));
        }
        Collections.reverse(points);
        if (!points.isEmpty()) {
            points.removeFirst(); // startovní buňku bot už má pod nohama
        }
        return points;
    }

    /** Uzel hrubé mřížky – mutable kvůli výkonu (lazy mazání v open setu). */
    private static final class Node implements Comparable<Node> {
        final int cx;
        final int cz;
        final int surfaceY;
        final Node parent;
        final int g;
        final int h;
        boolean closed;

        Node(int cx, int cz, int surfaceY, Node parent, int g, int h) {
            this.cx = cx;
            this.cz = cz;
            this.surfaceY = surfaceY;
            this.parent = parent;
            this.g = g;
            this.h = h;
        }

        @Override
        public int compareTo(Node o) {
            int byF = Integer.compare(g + h, o.g + o.h);
            if (byF != 0) {
                return byF;
            }
            return Integer.compare(o.g, g); // tie-break: hlouběji po své cestě
        }
    }
}
