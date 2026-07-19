package dev.botalive.core.pathfinding;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.BlockTraits;
import dev.botalive.core.world.WorldView;
import it.unimi.dsi.fastutil.longs.Long2DoubleOpenHashMap;
import it.unimi.dsi.fastutil.longs.Long2ObjectOpenHashMap;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.PriorityQueue;
import java.util.function.BooleanSupplier;

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
 * <p>Výpočet má rozpočet expandovaných uzlů a volitelně i časový strop;
 * při vyčerpání vrací částečnou cestu k uzlu nejblíže cíli – bot se přiblíží
 * a doplánuje. Běžící výpočet lze kooperativně zrušit (signál se kontroluje
 * po blocích expanzí). Každý výpočet si drží vlastní memo cache traits
 * a pochozích výšek – jedna buňka se světa ptá jen jednou, sousední expanze
 * ji už čtou zadarmo (a v rámci výpočtu je pohled na svět konzistentní).
 * Pathfinder nemá mutabilní sdílený stav, jedna instance se smí používat
 * z více vláken současně.</p>
 */
public final class AStarPathfinder {

    /** Maximální počet expandovaných uzlů na jeden výpočet. */
    private static final int DEFAULT_NODE_BUDGET = 8_000;

    /** Po kolika expanzích se kontroluje časový strop a zrušení (mocnina 2). */
    private static final int INTERRUPT_CHECK_INTERVAL = 128;

    /**
     * Váha vzdálenosti k cíli při výběru částečné cesty: minimalizuje se
     * {@code h·16 + g}. Blízkost cíli dominuje, ale mezi stejně blízkými
     * přiblíženími vyhrává to levněji dosažené – částečné cesty méně zabíhají
     * do drahých slepých kapes (okolí hazardů, špatných vzpomínek, vody).
     */
    private static final int PARTIAL_H_WEIGHT = 16;

    /** Cena rovného kroku (fixed-point ×10 kvůli int aritmetice). */
    private static final int COST_STRAIGHT = 10;
    private static final int COST_DIAGONAL = 14;
    private static final int COST_JUMP = 8;
    private static final int COST_FALL_PER_BLOCK = 6;
    private static final int COST_WATER = 25;
    /** Přirážka za buňku s hlavou pod hladinou – podvodní tunely stojí dech. */
    private static final int COST_SUBMERGED = 30;
    private static final int COST_CLIMB = 12;
    private static final int COST_DOOR = 15;
    private static final int COST_NEAR_HAZARD = 60;
    /** Přirážka za pomalý povrch (soul sand, med) – vyplatí se obejít. */
    private static final int COST_SLOW_SURFACE = 15;
    /** Přirážka za svislý záběr při plavání (stoupání je dřina). */
    private static final int COST_SWIM_VERTICAL = 6;
    /**
     * Přirážka za portálový blok – průchozí je, ale pobyt v něm teleportuje
     * do jiné dimenze. Cesty ho obcházejí; záměrný vstup (cíl = portál) tím
     * zdraží jen o konstantu a projde.
     */
    private static final int COST_PORTAL = 50;

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
    /** Dosah vlivu špatné vzpomínky (poloměr bounding boxu dangers). */
    private static final int DANGER_RADIUS = 6;

    /** Sentinel „ještě nespočítáno" v memo cache pochozích výšek. */
    private static final double FEET_UNCOMPUTED = Double.MAX_VALUE;

    private final WorldView world;
    private final List<BlockPos> dangers;
    /** Bounding box špatných vzpomínek (± {@link #DANGER_RADIUS}) – levný early-out. */
    private final int dangerMinX, dangerMinY, dangerMinZ;
    private final int dangerMaxX, dangerMaxY, dangerMaxZ;

    /** Ceny škálované osobnostním profilem ({@link PathCosts}). */
    private final int costJump;
    private final int costFallPerBlock;
    private final int costClimb;
    private final int costWater;
    private final int costSubmerged;
    private final int costGapJump;
    private final int costNearHazard;

    /**
     * @param world pohled na svět (thread-safe)
     */
    public AStarPathfinder(WorldView world) {
        this(world, List.of(), PathCosts.DEFAULT);
    }

    /**
     * @param world   pohled na svět (thread-safe)
     * @param dangers místa špatných vzpomínek (smrti, nebezpečí) – cesta se
     *                jim vyhýbá, existuje-li rozumná obchůzka; průchod se
     *                zdražuje, nezakazuje (bot nesmí uvíznout)
     */
    public AStarPathfinder(WorldView world, List<BlockPos> dangers) {
        this(world, dangers, PathCosts.DEFAULT);
    }

    /**
     * @param world   pohled na svět (thread-safe)
     * @param dangers místa špatných vzpomínek (smrti, nebezpečí)
     * @param costs   osobnostní profil cen (styl cesty podle povahy)
     */
    public AStarPathfinder(WorldView world, List<BlockPos> dangers, PathCosts costs) {
        this.world = world;
        this.dangers = dangers == null ? List.of() : dangers;
        int minX = Integer.MAX_VALUE, minY = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE;
        int maxX = Integer.MIN_VALUE, maxY = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        for (BlockPos danger : this.dangers) {
            minX = Math.min(minX, danger.x() - DANGER_RADIUS);
            minY = Math.min(minY, danger.y() - DANGER_RADIUS);
            minZ = Math.min(minZ, danger.z() - DANGER_RADIUS);
            maxX = Math.max(maxX, danger.x() + DANGER_RADIUS);
            maxY = Math.max(maxY, danger.y() + DANGER_RADIUS);
            maxZ = Math.max(maxZ, danger.z() + DANGER_RADIUS);
        }
        this.dangerMinX = minX;
        this.dangerMinY = minY;
        this.dangerMinZ = minZ;
        this.dangerMaxX = maxX;
        this.dangerMaxY = maxY;
        this.dangerMaxZ = maxZ;
        PathCosts profile = costs == null ? PathCosts.DEFAULT : costs;
        this.costJump = scaled(COST_JUMP, profile.climb());
        this.costFallPerBlock = scaled(COST_FALL_PER_BLOCK, profile.drop());
        this.costClimb = scaled(COST_CLIMB, profile.climb());
        this.costWater = scaled(COST_WATER, profile.water());
        this.costSubmerged = scaled(COST_SUBMERGED, profile.water());
        this.costGapJump = scaled(COST_GAP_JUMP, profile.gapJump());
        this.costNearHazard = scaled(COST_NEAR_HAZARD, profile.hazardMargin());
    }

    /** Cena škálovaná profilem (zaokrouhlená, nezáporná). */
    private static int scaled(int base, double multiplier) {
        return Math.max(0, (int) Math.round(base * multiplier));
    }

    /**
     * Výsledek výpočtu s diagnostikou pro metriky.
     *
     * @param path          cesta (může být částečná či prázdná)
     * @param expandedNodes počet expandovaných uzlů
     * @param elapsedNanos  čistý čas výpočtu
     * @param timedOut      {@code true} když výpočet ukončil časový strop
     * @param cancelled     {@code true} když výpočet ukončilo zrušení
     */
    public record Result(Path path, int expandedNodes, long elapsedNanos,
                         boolean timedOut, boolean cancelled) {
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
        return findPath(start, goal, nodeBudget, 0L, null).path();
    }

    /**
     * Naplánuje cestu s časovým stropem a možností zrušení.
     *
     * @param start        startovní blok (nohy bota)
     * @param goal         cílový blok
     * @param nodeBudget   maximum expandovaných uzlů; {@code <= 0} použije default
     * @param timeBudgetMs časový strop výpočtu (ms); {@code <= 0} bez limitu
     * @param cancelled    signál zrušení (smí být {@code null}); kontroluje se
     *                     po blocích expanzí, zrušený výpočet vrací dosavadní
     *                     nejlepší částečnou cestu
     * @return výsledek s cestou a diagnostikou
     */
    public Result findPath(BlockPos start, BlockPos goal, int nodeBudget,
                           long timeBudgetMs, BooleanSupplier cancelled) {
        return findPath(start, PathGoal.block(goal), nodeBudget, timeBudgetMs, cancelled);
    }

    /**
     * Naplánuje cestu k obecnému cíli ({@link PathGoal} – okruh, útěk,
     * hladina, nejbližší z kandidátů…). Hledání končí v první buňce, která
     * splní predikát; u multi-target cíle tak vyhrává nejlevněji dosažitelný
     * kandidát bez předběžného výběru vzdušnou čarou.
     *
     * @param start        startovní blok (nohy bota)
     * @param goal         cílový predikát
     * @param nodeBudget   maximum expandovaných uzlů; {@code <= 0} použije default
     * @param timeBudgetMs časový strop výpočtu (ms); {@code <= 0} bez limitu
     * @param cancelled    signál zrušení (smí být {@code null})
     * @return výsledek s cestou a diagnostikou
     */
    public Result findPath(BlockPos start, PathGoal goal, int nodeBudget,
                           long timeBudgetMs, BooleanSupplier cancelled) {
        int budget = nodeBudget > 0 ? nodeBudget : DEFAULT_NODE_BUDGET;
        return new Search(budget, timeBudgetMs, cancelled).run(start, goal);
    }

    /** Přirážka za krok poblíž špatné vzpomínky (smrt, nebezpečí). */
    private int dangerPenalty(BlockPos pos) {
        if (dangers.isEmpty()
                || pos.x() < dangerMinX || pos.x() > dangerMaxX
                || pos.y() < dangerMinY || pos.y() > dangerMaxY
                || pos.z() < dangerMinZ || pos.z() > dangerMaxZ) {
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

    /** Buňka hlavy nepřekáží tělu do dané lokální výšky (dveře si bot otevře). */
    private static boolean headClear(BlockTraits head, double clearance) {
        if (head == BlockTraits.UNKNOWN || head.hazard() || head.web()) {
            return false;
        }
        if (head.door()) {
            return true;
        }
        return head.lowestCollisionStart() >= clearance - EPS;
    }

    /** Dopadová plocha skoku: pochozí v celé výšce buňky (ne hrana desky). */
    private static boolean flatLanding(BlockPos cell, double feet) {
        return !Double.isNaN(feet) && Math.abs(feet - cell.y()) < 0.05;
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

    /**
     * Jeden výpočet cesty: open/visited set, memo cache traits a pochozích
     * výšek, rozpočty a signál zrušení. Instance žije jen po dobu výpočtu –
     * sdílené {@link AStarPathfinder} pole zůstává nemutabilní.
     */
    private final class Search {

        private final int nodeBudget;
        private final long deadlineNanos;
        private final BooleanSupplier cancelSignal;
        private final long startNanos = System.nanoTime();

        private final PriorityQueue<Node> open = new PriorityQueue<>();
        private final Long2ObjectOpenHashMap<Node> visited = new Long2ObjectOpenHashMap<>();
        /** Memo traits – jedna buňka se světa ptá jednou za výpočet. */
        private final Long2ObjectOpenHashMap<BlockTraits> traitsMemo =
                new Long2ObjectOpenHashMap<>(1024);
        /** Memo pochozí výšky ({@code NaN} = nestojí se tu). */
        private final Long2DoubleOpenHashMap feetMemo = new Long2DoubleOpenHashMap(512);

        private PathGoal goal;
        private int expanded;
        private boolean timedOut;
        private boolean cancelled;

        Search(int nodeBudget, long timeBudgetMs, BooleanSupplier cancelSignal) {
            this.nodeBudget = nodeBudget;
            this.deadlineNanos = timeBudgetMs > 0 ? startNanos + timeBudgetMs * 1_000_000L : 0L;
            this.cancelSignal = cancelSignal;
            feetMemo.defaultReturnValue(FEET_UNCOMPUTED);
        }

        Result run(BlockPos start, PathGoal rawGoal) {
            goal = rawGoal.exactBlock() ? normalizeBlockGoal(rawGoal) : rawGoal;

            Node startNode = new Node(start, null, 0, goal.heuristic(start));
            open.add(startNode);
            visited.put(start.asLong(), startNode);

            Node best = startNode;
            long bestScore = partialScore(startNode);

            while (!open.isEmpty() && expanded < nodeBudget) {
                if ((expanded & (INTERRUPT_CHECK_INTERVAL - 1)) == 0 && interrupted()) {
                    break;
                }
                Node current = open.poll();
                if (current.closed) {
                    continue;
                }
                current.closed = true;
                expanded++;

                if (goal.reached(current.pos)) {
                    return result(new Path(reconstruct(current), true));
                }
                long score = partialScore(current);
                if (score < bestScore) {
                    best = current;
                    bestScore = score;
                }
                expandNeighbors(current);
            }
            // Rozpočet/čas vyčerpán či zrušeno – vrátit nejlepší částečnou cestu.
            List<BlockPos> partial = reconstruct(best);
            if (partial.size() <= 1 && !cancelled) {
                logDeadStart(start, goal.anchor());
            }
            return result(new Path(partial, false));
        }

        private Result result(Path path) {
            return new Result(path, expanded, System.nanoTime() - startNanos,
                    timedOut, cancelled);
        }

        /** Skóre výběru částečné cesty: blízkost cíli především, cena mírně. */
        private long partialScore(Node node) {
            return (long) node.h * PARTIAL_H_WEIGHT + node.g;
        }

        /** Zrušení nebo časový strop – kontroluje se po blocích expanzí. */
        private boolean interrupted() {
            if (cancelSignal != null && cancelSignal.getAsBoolean()) {
                cancelled = true;
                return true;
            }
            if (deadlineNanos != 0 && System.nanoTime() > deadlineNanos) {
                timedOut = true;
                return true;
            }
            return false;
        }

        /** Vlastnosti bloku s memo cache výpočtu. */
        private BlockTraits traits(BlockPos pos) {
            long key = pos.asLong();
            BlockTraits t = traitsMemo.get(key);
            if (t == null) {
                t = world.traitsAt(pos);
                traitsMemo.put(key, t);
            }
            return t;
        }

        /**
         * Blokový cíl mířící „do vzduchu" nad částečným blokem (deska, sníh)
         * se přesune o buňku níž – tam, kde bot skutečně skončí nohama.
         * Obecné predikáty se nenormalizují (definují dosažení samy).
         */
        private PathGoal normalizeBlockGoal(PathGoal blockGoal) {
            BlockPos pos = blockGoal.anchor();
            if (Double.isNaN(feetHeight(pos)) && !Double.isNaN(feetHeight(pos.down()))) {
                return PathGoal.block(pos.down());
            }
            return blockGoal;
        }

        /** Diagnostika mrtvého startu: mapa traits okolí (S=solid W=liquid .=passable ?=unknown). */
        private void logDeadStart(BlockPos start, BlockPos goal) {
            StringBuilder sb = new StringBuilder();
            for (int dy = 2; dy >= -1; dy--) {
                sb.append("y").append(dy >= 0 ? "+" : "").append(dy).append("[");
                for (int dz = -1; dz <= 1; dz++) {
                    for (int dx = -1; dx <= 1; dx++) {
                        BlockTraits t = traits(start.offset(dx, dy, dz));
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
        private void expandNeighbors(Node current) {
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
                    stepTowards(current, curFeet, pos.offset(dx, 0, dz), diagonal);
                    tryGapJump(current, curFeet, dx, dz);
                }
            }

            // Šplhání: žebřík/liána v aktuální pozici → pohyb nahoru/dolů.
            if (traits(pos).climbable()) {
                tryAddStandable(current, pos.up(), costClimb);
                tryAddStandable(current, pos.down(), costClimb);
            }

            // Plavání svisle: ve vodním sloupci se bot může vynořit i potopit.
            BlockTraits here = traits(pos);
            if (here.liquid() && !here.hazard()) {
                tryAddStandable(current, pos.up(), costWater + COST_SWIM_VERTICAL);
                tryAddStandable(current, pos.down(), costWater);
            }
        }

        /**
         * Krok na sousední sloupec: rovně (i výstup na desku/schod v rámci
         * step-up výšky), výskok o blok, nebo seskok až o {@link #MAX_DROP}.
         */
        private void stepTowards(Node current, double curFeet, BlockPos target, boolean diagonal) {
            int base = diagonal ? COST_DIAGONAL : COST_STRAIGHT;

            // Stejné patro: chůze, mini-schody (desky, schody), mini-seskoky.
            double targetFeet = feetHeight(target);
            if (!Double.isNaN(targetFeet)) {
                double rise = targetFeet - curFeet;
                if (rise <= STEP_UP + EPS) {
                    tryAdd(current, target, base + terrainPenalty(target));
                    return;
                }
                // Vyšší částečný blok ve stejné buňce (6–7 vrstev sněhu) – výskok.
                if (!diagonal && rise <= JUMP_RISE + EPS
                        && transitClear(current.pos.offset(0, 2, 0))) {
                    tryAdd(current, target, base + costJump + terrainPenalty(target));
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
                    boolean stairStep = traits(target).stepFriendly();
                    int cost = base + (stairStep ? 2 : costJump) + terrainPenalty(jumpCell);
                    tryAdd(current, jumpCell, cost);
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
                        BlockTraits landing = traits(drop);
                        boolean deepWater = landing.liquid() && !landing.hazard()
                                && traits(drop.down()).liquid();
                        if (fall > MAX_DROP + 0.51 && !deepWater) {
                            return; // vysoký pád bez vodní jistoty – nejdeme
                        }
                        int fallBlocks = (int) Math.ceil(fall - 0.01);
                        int cost = base + Math.min(fallBlocks, MAX_DROP + 2) * costFallPerBlock
                                + terrainPenalty(drop);
                        tryAdd(current, drop, cost);
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
        private void tryGapJump(Node current, double curFeet, int dx, int dz) {
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
                int base = span * (diagonal ? COST_DIAGONAL : COST_STRAIGHT) + costGapJump
                        + (span - 2) * costGapJump / 2   // širší mezera = větší riziko
                        + (diagonal ? costGapJump / 2 : 0); // rohový let je delší a těsnější
                BlockPos landing = pos.offset(dx * span, 0, dz * span);
                double landFeet = feetHeight(landing);
                if (flatLanding(landing, landFeet) && transitClear(landing.offset(0, 2, 0))
                        && !traits(landing).liquid()) {
                    tryAdd(current, landing, base + terrainPenalty(landing));
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
                            && !traits(lower).liquid()) {
                        int cost = base + costFallPerBlock + terrainPenalty(lower);
                        tryAdd(current, lower, cost);
                        return;
                    }
                }
            }
        }

        /** Průchozí sloupec letové dráhy: úroveň nohou, hlavy i nad hlavou. */
        private boolean columnClear(BlockPos feet) {
            return transitClear(feet) && transitClear(feet.up())
                    && transitClear(feet.offset(0, 2, 0));
        }

        /** Je na dně sloupce (do {@link #GAP_HAZARD_SCAN} bloků) hazard – láva, oheň? */
        private boolean hazardBelow(BlockPos top) {
            BlockPos pos = top.down();
            for (int depth = 1; depth <= GAP_HAZARD_SCAN; depth++) {
                BlockTraits t = traits(pos);
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
        private void tryAddStandable(Node parent, BlockPos pos, int moveCost) {
            if (!Double.isNaN(feetHeight(pos))) {
                tryAdd(parent, pos, moveCost);
            }
        }

        /** Přidá uzel do open setu, pokud zlepšuje dosavadní cestu. */
        private void tryAdd(Node parent, BlockPos pos, int moveCost) {
            long key = pos.asLong();
            int g = parent.g + moveCost + dangerPenalty(pos);
            Node existing = visited.get(key);
            if (existing != null && existing.g <= g) {
                return;
            }
            Node node = new Node(pos, parent, g, goal.heuristic(pos));
            visited.put(key, node);
            open.add(node);
        }

        /**
         * Absolutní výška chodidel bota stojícího v dané buňce, nebo {@code NaN}
         * pokud v ní stát nelze. Memoizováno per výpočet.
         *
         * <p>Bot stojí: na částečném bloku v buňce (deska, sníh – chodidla na jeho
         * stropě), na plném bloku pod buňkou, ve vodě (plavání), na žebříku, nebo
         * v buňce dveří (otevře si je). Ploty a zídky (výška 1,5) oporou nejsou –
         * nejde na ně vystoupit ani je překročit. Nad hlavou musí zbýt místo na
         * tělo (1,8) – kontroluje se i buňka +2 při zvednutých chodidlech.</p>
         */
        private double feetHeight(BlockPos feet) {
            long key = feet.asLong();
            double cached = feetMemo.get(key);
            if (cached != FEET_UNCOMPUTED) {
                return cached;
            }
            double computed = computeFeetHeight(feet);
            feetMemo.put(key, computed);
            return computed;
        }

        private double computeFeetHeight(BlockPos feet) {
            BlockTraits t = traits(feet);
            BlockTraits head = traits(feet.up());
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
                    BlockTraits below = traits(feet.down());
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
            if (fh > 0.2 && !headClear(traits(feet.offset(0, 2, 0)), fh - 0.2)) {
                return Double.NaN;
            }
            return feet.y() + fh;
        }

        /**
         * Průchozí prostor pro tělo v letu/pádu: bez hazardu, pavučiny a kolize
         * (nízký profil do 1/16 nevadí), bez „neznáma". Zavřené dveře si bot otevře.
         */
        private boolean transitClear(BlockPos pos) {
            BlockTraits t = traits(pos);
            if (t == BlockTraits.UNKNOWN || t.hazard() || t.web()) {
                return false;
            }
            return t.door() || t.lowProfile();
        }

        /** Penalizace terénu: voda (potápění zvlášť), dveře, pomalý povrch, hazard v okolí. */
        private int terrainPenalty(BlockPos feet) {
            int penalty = 0;
            BlockTraits feetTraits = traits(feet);
            if (feetTraits.liquid()) {
                penalty += costWater;
                // Hlava taky pod vodou → bot se tu nenadechne. Dlouhé podvodní
                // úseky se prodraží a cesta drží hladinu, kde to jde.
                if (traits(feet.up()).liquid()) {
                    penalty += costSubmerged;
                }
            }
            if (feetTraits.door()) {
                penalty += COST_DOOR;
            }
            // Portálový blok – neprocházet omylem (změna dimenze); záměrnému
            // vstupu (portál je cílem cesty) konstanta nebrání.
            if (feetTraits.portal() || traits(feet.up()).portal()) {
                penalty += COST_PORTAL;
            }
            // Pomalý povrch pod nohama (soul sand, med) – chůze po něm se vleče.
            BlockTraits support = feetTraits.floorHeight() > 0
                    ? feetTraits : traits(feet.down());
            if (support.speedFactor() < 0.99) {
                penalty += COST_SLOW_SURFACE;
            }
            // Hazard v okolí 1 bloku → vysoká penalizace (bot se drží dál od lávy).
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (traits(feet.offset(dx, 0, dz)).hazard()
                            || traits(feet.offset(dx, -1, dz)).hazard()) {
                        return penalty + costNearHazard;
                    }
                }
            }
            return penalty;
        }
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
            int byF = Integer.compare(g + h, o.g + o.h);
            if (byF != 0) {
                return byF;
            }
            // Tie-break: při shodném f dřív uzel s vyšším g (hlouběji po své
            // cestě, blíž cíli) – na otevřených rovinách řeže plata expanzí.
            return Integer.compare(o.g, g);
        }
    }
}
