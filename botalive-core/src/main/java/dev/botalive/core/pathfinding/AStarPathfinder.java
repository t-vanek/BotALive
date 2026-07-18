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
 * <p>Prohledává „pochozí" pozice (opora pod nohama, průchozí prostor pro
 * tělo). Podporované pohyby: 4 kardinální směry, diagonály (bez řezání rohů),
 * výstup o 1 blok (skok), seskok až o 3 bloky, skok přes mezeru 1–2 bloků
 * (sprint-skok, i s dopadem o blok níž; diagonálně přes rohovou mezeru),
 * šplhání po žebřících a plavání.</p>
 *
 * <p>Terén se čte přes {@link BlockTraits#floorHeight()} – bot rozumí deskám
 * (stojí v půlce bloku), schodům (výstup bez skoku), vrstvám sněhu, postelím
 * i zavřeným poklopům. Ploty a zídky (1,5 bloku) jsou nepřekročitelné, pavučinám
 * se cesta vyhýbá, zavřené dveře jsou průchozí po interakci, otevřené rovnou.
 * Cenová funkce penalizuje vodu, seskoky, pomalé povrchy (soul sand, med)
 * a blízkost hazardů (láva, oheň, kaktus), tvrdě zakazuje vstup do hazardu
 * a do nenačtených chunků.</p>
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
    /** Přirážka za pomalý povrch (soul sand, med) – vyplatí se obejít. */
    private static final int COST_SLOW_SURFACE = 15;
    /** Přirážka za svislý záběr při plavání (stoupání je dřina). */
    private static final int COST_SWIM_VERTICAL = 6;

    /** Maximální bezpečná výška seskoku (bez poškození stojí za to). */
    private static final int MAX_DROP = 3;
    /** Maximální seskok do hluboké vody (dopad do vody neubližuje). */
    private static final int MAX_WATER_DROP = 20;

    /** Nejširší mezera, kterou bot přeskočí (sprint-skok zvládne 2 bloky prázdna). */
    private static final int MAX_GAP = 2;
    /** Přirážka za skok přes mezeru (rizikovější než chůze, levnější než obcházka). */
    private static final int COST_GAP_JUMP = 18;
    /** Do jaké hloubky kontrolovat dno mezery na hazard (láva = skok zakázán). */
    private static final int GAP_HAZARD_SCAN = 8;

    /** Výška schodu zvládnutá bez skoku (step-up fyziky). */
    private static final double STEP_UP = 0.6;
    /** Maximální zdvih výskokem (vanilla skok ~1.25, rezerva na doskok). */
    private static final double JUMP_RISE = 1.15;
    private static final double EPS = 1.0E-6;

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
        goal = normalizeGoal(goal);

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

    /**
     * Cíl mířící „do vzduchu" nad částečným blokem (deska, sníh) se přesune
     * o buňku níž – tam, kde bot skutečně skončí nohama.
     */
    private BlockPos normalizeGoal(BlockPos goal) {
        if (Double.isNaN(feetHeight(goal)) && !Double.isNaN(feetHeight(goal.down()))) {
            return goal.down();
        }
        return goal;
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
        double curFeet = feetHeight(pos);
        if (Double.isNaN(curFeet)) {
            curFeet = pos.y(); // start uprostřed „nemožné" pozice – konzervativní základ
        }

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
                stepTowards(current, curFeet, pos.offset(dx, 0, dz), diagonal, goal, open, visited);
                tryGapJump(current, curFeet, dx, dz, goal, open, visited);
            }
        }

        // Šplhání: žebřík/liána v aktuální pozici → pohyb nahoru/dolů.
        if (world.traitsAt(pos).climbable()) {
            tryAddStandable(current, pos.up(), COST_CLIMB, goal, open, visited);
            tryAddStandable(current, pos.down(), COST_CLIMB, goal, open, visited);
        }

        // Plavání svisle: ve vodním sloupci se bot může vynořit i potopit.
        BlockTraits here = world.traitsAt(pos);
        if (here.liquid() && !here.hazard()) {
            tryAddStandable(current, pos.up(), COST_WATER + COST_SWIM_VERTICAL, goal, open, visited);
            tryAddStandable(current, pos.down(), COST_WATER, goal, open, visited);
        }
    }

    /**
     * Krok na sousední sloupec: rovně (i výstup na desku/schod v rámci
     * step-up výšky), výskok o blok, nebo seskok až o {@link #MAX_DROP}.
     */
    private void stepTowards(Node current, double curFeet, BlockPos target, boolean diagonal,
                             BlockPos goal, PriorityQueue<Node> open,
                             Long2ObjectOpenHashMap<Node> visited) {
        int base = diagonal ? COST_DIAGONAL : COST_STRAIGHT;

        // Stejné patro: chůze, mini-schody (desky, schody), mini-seskoky.
        double targetFeet = feetHeight(target);
        if (!Double.isNaN(targetFeet)) {
            double rise = targetFeet - curFeet;
            if (rise <= STEP_UP + EPS) {
                tryAdd(current, target, base + terrainPenalty(target), goal, open, visited);
                return;
            }
            // Vyšší částečný blok ve stejné buňce (6–7 vrstev sněhu) – výskok.
            if (!diagonal && rise <= JUMP_RISE + EPS
                    && transitClear(current.pos.offset(0, 2, 0))) {
                tryAdd(current, target, base + COST_JUMP + terrainPenalty(target), goal, open, visited);
                return;
            }
        }

        // Výskok o blok výš (jen ne-diagonálně, potřebuje volný strop nad hlavou).
        if (!diagonal) {
            BlockPos jumpCell = target.up();
            double jumpFeet = feetHeight(jumpCell);
            if (!Double.isNaN(jumpFeet)
                    && jumpFeet - curFeet <= JUMP_RISE + EPS
                    && transitClear(current.pos.offset(0, 2, 0))
                    && transitClear(target.offset(0, 2, 0))) {
                // Schodovité bloky zvládne step-up fyzika bez výskoku – bez přirážky.
                boolean stairStep = world.traitsAt(target).stepFriendly();
                int cost = base + (stairStep ? 2 : COST_JUMP) + terrainPenalty(jumpCell);
                tryAdd(current, jumpCell, cost, goal, open, visited);
                return;
            }
        }

        // Seskok: sloupec pod cílem musí být průchozí až k dopadu. Do výšky
        // MAX_DROP kamkoli; hlouběji jen do vody hluboké aspoň 2 (dopad do
        // mělčiny nebo na zem by bota zranil či zabil).
        if (transitClear(target) && transitClear(target.up())) {
            BlockPos drop = target;
            for (int depth = 1; depth <= MAX_WATER_DROP; depth++) {
                drop = drop.down();
                double dropFeet = feetHeight(drop);
                if (!Double.isNaN(dropFeet)) {
                    double fall = curFeet - dropFeet;
                    if (fall <= STEP_UP) {
                        return; // není seskok – řeší větev stejného patra
                    }
                    BlockTraits landing = world.traitsAt(drop);
                    boolean deepWater = landing.liquid() && !landing.hazard()
                            && world.traitsAt(drop.down()).liquid();
                    if (fall > MAX_DROP + 0.51 && !deepWater) {
                        return; // vysoký pád bez vodní jistoty – nejdeme
                    }
                    int fallBlocks = (int) Math.ceil(fall - 0.01);
                    int cost = base + Math.min(fallBlocks, MAX_DROP + 2) * COST_FALL_PER_BLOCK
                            + terrainPenalty(drop);
                    tryAdd(current, drop, cost, goal, open, visited);
                    return;
                }
                if (!transitClear(drop)) {
                    return; // narazili jsme do zdi/hazardu – seskok nelze
                }
            }
        }
    }

    /**
     * Skok přes mezeru. Kardinálně 1–2 sloupce prázdna s dopadem ve stejné
     * výšce nebo o blok níž; diagonálně jen rohová mezera 1 sloupce (delší let)
     * se stejnou výškou dopadu. Letová dráha musí být průchozí v úrovni nohou,
     * hlavy i nad hlavou – u diagonály včetně čtyř sloupců u rohů, přes které
     * hitbox při letu zavadí. Mezisloupec nesmí být pochozí (jinak stačí
     * obyčejný krok) a nad lávou/ohněm se neskáče – nepovedený skok by byl
     * smrtelný. Odraz i dopad jen z „celých" výšek – z hrany desky se neskáče.
     */
    private void tryGapJump(Node current, double curFeet, int dx, int dz, BlockPos goal,
                            PriorityQueue<Node> open, Long2ObjectOpenHashMap<Node> visited) {
        BlockPos pos = current.pos;
        boolean diagonal = dx != 0 && dz != 0;
        // Odraz jen z celé výšky (ne z hrany desky) a s volným stropem.
        if (Math.abs(curFeet - pos.y()) > 0.05 || !transitClear(pos.offset(0, 2, 0))) {
            return;
        }
        int maxSpan = diagonal ? 2 : MAX_GAP + 1;
        for (int span = 2; span <= maxSpan; span++) {
            // Nový mezisloupec (poslední před dopadem pro tento rozpon).
            BlockPos gap = pos.offset(dx * (span - 1), 0, dz * (span - 1));
            if (!Double.isNaN(feetHeight(gap))) {
                return; // není mezera – řeší obyčejný krok
            }
            if (!columnClear(gap)) {
                return; // zeď/hazard v letové dráze
            }
            if (diagonal && !(columnClear(pos.offset(dx, 0, 0))
                    && columnClear(pos.offset(0, 0, dz))
                    && columnClear(gap.offset(dx, 0, 0))
                    && columnClear(gap.offset(0, 0, dz)))) {
                return; // rohové sloupce blokují letovou dráhu
            }
            if (hazardBelow(gap)) {
                return; // láva na dně – radši obchůzka
            }
            int base = span * (diagonal ? COST_DIAGONAL : COST_STRAIGHT) + COST_GAP_JUMP
                    + (span - 2) * COST_GAP_JUMP / 2   // širší mezera = větší riziko
                    + (diagonal ? COST_GAP_JUMP / 2 : 0); // rohový let je delší a těsnější
            BlockPos landing = pos.offset(dx * span, 0, dz * span);
            double landFeet = feetHeight(landing);
            if (flatLanding(landing, landFeet) && transitClear(landing.offset(0, 2, 0))
                    && !world.traitsAt(landing).liquid()) {
                tryAdd(current, landing, base + terrainPenalty(landing), goal, open, visited);
                return;
            }
            // Dopad o blok níž (jen kardinálně): letí se dál a klesá, fyzikálně
            // snazší než rovný dopad – ale dopadová plocha musí být volná i
            // v celé letové výšce.
            if (!diagonal) {
                BlockPos lower = landing.down();
                double lowerFeet = feetHeight(lower);
                if (flatLanding(lower, lowerFeet) && transitClear(landing.up())
                        && transitClear(landing.offset(0, 2, 0))
                        && !world.traitsAt(lower).liquid()) {
                    int cost = base + COST_FALL_PER_BLOCK + terrainPenalty(lower);
                    tryAdd(current, lower, cost, goal, open, visited);
                    return;
                }
            }
        }
    }

    /** Dopadová plocha skoku: pochozí v celé výšce buňky (ne hrana desky). */
    private static boolean flatLanding(BlockPos cell, double feet) {
        return !Double.isNaN(feet) && Math.abs(feet - cell.y()) < 0.05;
    }

    /** Průchozí sloupec letové dráhy: úroveň nohou, hlavy i nad hlavou. */
    private boolean columnClear(BlockPos feet) {
        return transitClear(feet) && transitClear(feet.up()) && transitClear(feet.offset(0, 2, 0));
    }

    /** Je na dně sloupce (do {@link #GAP_HAZARD_SCAN} bloků) hazard – láva, oheň? */
    private boolean hazardBelow(BlockPos top) {
        BlockPos pos = top.down();
        for (int depth = 1; depth <= GAP_HAZARD_SCAN; depth++) {
            BlockTraits t = world.traitsAt(pos);
            if (t.hazard()) {
                return true;
            }
            if (!t.noCollision() || t.liquid()) {
                return false; // pevné/vodní dno je bezpečné
            }
            pos = pos.down();
        }
        return false; // bezedno – pád je riziko skoku, ne hazard dna
    }

    /** Diagonála je povolená jen, když jsou oba přiléhající sloupce průchozí (žádné řezání rohů). */
    private boolean canCutCorner(BlockPos pos, int dx, int dz) {
        return transitClear(pos.offset(dx, 0, 0)) && transitClear(pos.offset(dx, 1, 0))
                && transitClear(pos.offset(0, 0, dz)) && transitClear(pos.offset(0, 1, dz));
    }

    /** Přidá cíl, pokud je pochozí (pomocník pro šplhání/plavání). */
    private void tryAddStandable(Node parent, BlockPos pos, int moveCost, BlockPos goal,
                                 PriorityQueue<Node> open, Long2ObjectOpenHashMap<Node> visited) {
        if (!Double.isNaN(feetHeight(pos))) {
            tryAdd(parent, pos, moveCost, goal, open, visited);
        }
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
     * Absolutní výška chodidel bota stojícího v dané buňce, nebo {@code NaN}
     * pokud v ní stát nelze.
     *
     * <p>Bot stojí: na částečném bloku v buňce (deska, sníh – chodidla na jeho
     * stropě), na plném bloku pod buňkou, ve vodě (plavání), na žebříku, nebo
     * v buňce dveří (otevře si je). Ploty a zídky (výška 1,5) oporou nejsou –
     * nejde na ně vystoupit ani je překročit. Nad hlavou musí zbýt místo na
     * tělo (1,8) – kontroluje se i buňka +2 při zvednutých chodidlech.</p>
     */
    private double feetHeight(BlockPos feet) {
        BlockTraits t = world.traitsAt(feet);
        BlockTraits head = world.traitsAt(feet.up());
        if (t == BlockTraits.UNKNOWN || head == BlockTraits.UNKNOWN) {
            return Double.NaN;
        }
        if (t.hazard() || head.hazard() || t.web() || head.web()) {
            return Double.NaN;
        }

        boolean passThrough = t.liquid() || t.climbable() || t.door();
        double fh = t.door() ? 0 : t.floorHeight();
        if (!passThrough) {
            if (fh >= 0.99) {
                return Double.NaN; // plný blok / plot – tady se nestojí
            }
            if (fh <= 0) {
                // Prázdná buňka – oporu musí dát plný blok pod ní.
                BlockTraits below = world.traitsAt(feet.down());
                if (below == BlockTraits.UNKNOWN
                        || below.floorHeight() < 0.99 || below.floorHeight() > 1.01) {
                    return Double.NaN;
                }
            }
        } else if (fh >= 0.99) {
            return Double.NaN; // vodou zalitý plný tvar – plave se v buňce nad ním
        }

        // Prostor pro tělo: hlava (celá buňka nad) a u zvednutých chodidel
        // i spodek buňky +2.
        if (!headClear(head, fh + 0.8)) {
            return Double.NaN;
        }
        if (fh > 0.2 && !headClear(world.traitsAt(feet.offset(0, 2, 0)), fh - 0.2)) {
            return Double.NaN;
        }
        return feet.y() + fh;
    }

    /** Buňka hlavy nepřekáží tělu do dané lokální výšky (dveře si bot otevře). */
    private static boolean headClear(BlockTraits head, double clearance) {
        if (head == BlockTraits.UNKNOWN || head.hazard() || head.web()) {
            return false;
        }
        if (head.door()) {
            return true;
        }
        return lowestBoxStart(head) >= clearance - EPS;
    }

    /** Nejnižší začátek kolize v buňce ({@code MAX_VALUE} bez kolize). */
    private static double lowestBoxStart(BlockTraits t) {
        double[] boxes = t.boxes();
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
     * Průchozí prostor pro tělo v letu/pádu: bez hazardu, pavučiny a kolize
     * (nízký profil do 1/16 nevadí), bez „neznáma". Zavřené dveře si bot otevře.
     */
    private boolean transitClear(BlockPos pos) {
        BlockTraits t = world.traitsAt(pos);
        if (t == BlockTraits.UNKNOWN || t.hazard() || t.web()) {
            return false;
        }
        return t.door() || t.lowProfile();
    }

    /** Penalizace terénu: voda, dveře, pomalý povrch, sousedství hazardu. */
    private int terrainPenalty(BlockPos feet) {
        int penalty = 0;
        BlockTraits feetTraits = world.traitsAt(feet);
        if (feetTraits.liquid()) {
            penalty += COST_WATER;
        }
        if (feetTraits.door()) {
            penalty += COST_DOOR;
        }
        // Pomalý povrch pod nohama (soul sand, med) – chůze po něm se vleče.
        BlockTraits support = feetTraits.floorHeight() > 0
                ? feetTraits : world.traitsAt(feet.down());
        if (support.speedFactor() < 0.99) {
            penalty += COST_SLOW_SURFACE;
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
