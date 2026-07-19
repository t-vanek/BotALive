package dev.botalive.core.pathfinding;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
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
 * výstup o 1 blok (skok), seskok až o 3 bloky, skok přes mezeru 1–3 bloků
 * (sprint-skok, i s dopadem o blok níž; přes jednoblokovou díru i parkour
 * výskok na římsu o blok výš; diagonálně přes rohovou mezeru),
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
    /**
     * Přirážka za horizontální plavání PROTI proudu tekoucí vody. Jen
     * přirážka, žádná sleva po proudu – minimální cena kroku zůstává
     * {@link #COST_STRAIGHT} a heuristika přípustná (stejná lekce jako
     * u preference cestiček). Zdrojová voda (jezera, oceány) proud nemá
     * a nic neplatí; postihuje se jen tekoucí voda (řeky, přepady).
     */
    private static final int COST_AGAINST_CURRENT = 15;

    /**
     * Přirážka za šlapání terénu HNED VEDLE cestového povrchu (udusaná
     * cestička, štěrk, prkna). Preference cestiček je schválně dvakrát krotká:
     * (1) cesta nedostává slevu, okolí přirážku – minimální cena kroku zůstává
     * {@link #COST_STRAIGHT} a oktilová heuristika je dál přípustná;
     * (2) platí se jen s cestičkou v okolí 1 bloku – daleko od cest se ceny
     * nemění vůbec, takže vzdálené trasy nezdraží a hledání neexpanduje víc
     * uzlů (globální přirážka rozvolňovala heuristiku o ~10 % všude).
     * Efekt: bot jdoucí podél cestičky na ni uhne, přes louku jde volně.
     */
    private static final int COST_OFF_PATH = 1;
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

    /** Nejširší mezera, kterou bot přeskočí (sprint-skok zvládne 3 bloky prázdna). */
    private static final int MAX_GAP = 3;
    /** Přirážka za skok přes mezeru (rizikovější než chůze, levnější než obcházka). */
    private static final int COST_GAP_JUMP = 18;
    /**
     * Dohledná hloubka dna pod skokem/mostem. Hazard kdekoli v ní znamená
     * zákaz (dřívější sken 8 bloků neviděl lávu na dně hlubší rokle);
     * bez dna v této hloubce se pád bere jako bezedný (void).
     */
    private static final int DEEP_HAZARD_SCAN = 24;
    /** Výsledek {@code dropDepth}: hazard (láva/oheň) v dohledné hloubce. */
    private static final int DROP_HAZARD = -1;
    /** Výsledek {@code dropDepth}: hluboká voda – pád bezpečně tlumí. */
    private static final int DROP_WATER = 0;
    /** Výsledek {@code dropDepth}: bez dna v dohledné hloubce (void, propast). */
    private static final int DROP_BOTTOMLESS = Integer.MAX_VALUE;
    /**
     * Hloubka pádu, která by bota zabila nebo skoro zabila (vanilla poškození
     * = hloubka − 3, tedy 17 při 20). Mělčí pády pod skokem nechávají cenu
     * skoku na odvaze ({@code gapJump}) – riziko nepovedeného rozskoku je
     * malé a dopad bolestivý, ne fatální.
     */
    private static final int DEADLY_DROP = 20;
    /**
     * Přirážka za skok nad pádem smrtící hloubky: nepovedený rozskok
     * (strčení davem, led, boj) znamená smrt. Škáluje se opatrností povahy
     * ({@code hazardMargin}), nad bezednem (void – žádná šance) dvojnásobně
     * – bázlivý bot volí obchůzku, odvážný mezi ostrovy dál skáče.
     */
    private static final int COST_DEEP_GAP = 30;

    /** Výška schodu zvládnutá bez skoku (step-up fyziky). */
    private static final double STEP_UP = 0.6;
    /** Maximální zdvih výskokem (vanilla skok ~1.25, rezerva na doskok). */
    private static final double JUMP_RISE = 1.15;
    private static final double EPS = 1.0E-6;

    /**
     * Cena vykopnutí jednoho bloku (~8 kroků chůze) – kopací hrany jsou
     * poslední možnost, pěší obchůzka vyhrává, kdykoli rozumná existuje.
     */
    private static final int COST_DIG = 80;

    /**
     * Cena položení jednoho bloku (~10 kroků chůze) – bloky jsou vzácnější
     * než čas kopání a most se staví, jen když obchůzka reálně neexistuje.
     */
    private static final int COST_PLACE = 100;
    /** Nejvyšší stěna přelezitelná žebříkem (= {@code LadderTask.MAX_HEIGHT}). */
    private static final int MAX_LADDER = 8;

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
    private final int costDeepGap;

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
        this.costDeepGap = scaled(COST_DEEP_GAP, profile.hazardMargin());
    }

    /** Cena škálovaná profilem (zaokrouhlená, nezáporná). */
    private static int scaled(int base, double multiplier) {
        return Math.max(0, (int) Math.round(base * multiplier));
    }

    /**
     * Chráněné materiály – kopací hrany je nikdy nelámou: nekopatelné či
     * absurdně drahé bloky (bedrock, obsidián), funkční bloky a majetek
     * (truhly, pece, postele, ponky) – bot si tunel nesmí prorazit cizím
     * domem skrz vybavení.
     */
    private static boolean protectedMaterial(org.bukkit.Material material) {
        return switch (material) {
            case BEDROCK, BARRIER, OBSIDIAN, CRYING_OBSIDIAN, REINFORCED_DEEPSLATE,
                 END_PORTAL_FRAME, SPAWNER, ENDER_CHEST, CHEST, TRAPPED_CHEST, BARREL,
                 FURNACE, BLAST_FURNACE, SMOKER, BEACON, ENCHANTING_TABLE,
                 ANVIL, CHIPPED_ANVIL, DAMAGED_ANVIL, CRAFTING_TABLE, LODESTONE,
                 RESPAWN_ANCHOR -> true;
            default -> material.name().endsWith("_BED")
                    || material.name().endsWith("SHULKER_BOX");
        };
    }

    /** Padavé materiály – bez podpory se sesypou (do vykopnuté štoly). */
    private static boolean gravityMaterial(org.bukkit.Material material) {
        return switch (material) {
            case SAND, RED_SAND, SUSPICIOUS_SAND, GRAVEL, SUSPICIOUS_GRAVEL,
                 ANVIL, CHIPPED_ANVIL, DAMAGED_ANVIL, POINTED_DRIPSTONE,
                 DRAGON_EGG -> true;
            default -> material.name().endsWith("_CONCRETE_POWDER");
        };
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
        return findPath(start, goal, nodeBudget, timeBudgetMs, cancelled, PathOptions.WALK_ONLY);
    }

    /**
     * Naplánuje cestu s volbami výpočtu – s {@link PathOptions#digThrough()}
     * smí plán obsahovat hrany „prokopej se" ({@link TerrainAction} na
     * waypointech): tunel 1×2, vylámaný schod vzhůru i dolů. Kopání má
     * tekutinovou pojistku (žádný zásah vedle vody/lávy), deny-list
     * chráněných materiálů (bedrock, obsidián, truhly, postele…) a vysokou
     * cenu – pěší obchůzka vyhrává, kdykoli rozumná existuje.
     *
     * @param start        startovní blok (nohy bota)
     * @param goal         cílový predikát
     * @param nodeBudget   maximum expandovaných uzlů; {@code <= 0} použije default
     * @param timeBudgetMs časový strop výpočtu (ms); {@code <= 0} bez limitu
     * @param cancelled    signál zrušení (smí být {@code null})
     * @param options      volby výpočtu (kopací hrany)
     * @return výsledek s cestou a diagnostikou
     */
    public Result findPath(BlockPos start, PathGoal goal, int nodeBudget,
                           long timeBudgetMs, BooleanSupplier cancelled, PathOptions options) {
        int budget = nodeBudget > 0 ? nodeBudget : DEFAULT_NODE_BUDGET;
        return new Search(budget, timeBudgetMs, cancelled,
                options == null ? PathOptions.WALK_ONLY : options).run(start, goal);
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

    /** Zrekonstruuje cestu od cílového uzlu ke startu (akce podle indexu waypointu). */
    private static List<BlockPos> reconstruct(Node node,
                                              java.util.Map<Integer, TerrainAction> actionsOut) {
        List<BlockPos> path = new ArrayList<>();
        List<TerrainAction> actions = new ArrayList<>();
        for (Node n = node; n != null; n = n.parent) {
            path.add(n.pos);
            actions.add(n.action);
        }
        Collections.reverse(path);
        Collections.reverse(actions);
        if (!path.isEmpty()) {
            path.removeFirst(); // startovní pozici bot už má
            actions.removeFirst();
        }
        for (int i = 0; i < actions.size(); i++) {
            if (actions.get(i) != null) {
                actionsOut.put(i, actions.get(i));
            }
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
        private final PathOptions options;
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

        Search(int nodeBudget, long timeBudgetMs, BooleanSupplier cancelSignal,
               PathOptions options) {
            this.nodeBudget = nodeBudget;
            this.deadlineNanos = timeBudgetMs > 0 ? startNanos + timeBudgetMs * 1_000_000L : 0L;
            this.cancelSignal = cancelSignal;
            this.options = options;
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
                    return result(toPath(current, true));
                }
                long score = partialScore(current);
                if (score < bestScore) {
                    best = current;
                    bestScore = score;
                }
                expandNeighbors(current);
            }
            // Rozpočet/čas vyčerpán či zrušeno – vrátit nejlepší částečnou cestu.
            Path partial = toPath(best, false);
            if (partial.waypoints().size() <= 1 && !cancelled) {
                logDeadStart(start, goal.anchor());
            }
            return result(partial);
        }

        /** Zrekonstruuje cestu včetně mapy zásahů do terénu. */
        private Path toPath(Node node, boolean complete) {
            java.util.Map<Integer, TerrainAction> actions = new java.util.HashMap<>();
            List<BlockPos> waypoints = reconstruct(node, actions);
            return new Path(waypoints, complete,
                    actions.isEmpty() ? java.util.Map.of() : java.util.Map.copyOf(actions));
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

            // Akční hrany (jen na vyžádání – náhrada assist eskalace).
            if (options.digThrough() && Math.abs(curFeet - pos.y()) <= 0.05) {
                tryDigEdges(current);
            }
            if (options.maxPlacements() > 0 && Math.abs(curFeet - pos.y()) <= 0.05) {
                tryPlaceEdges(current);
            }
            if (options.maxLadders() > 0 && Math.abs(curFeet - pos.y()) <= 0.05) {
                tryLadderEdges(current);
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
                    tryAdd(current, target,
                            base + terrainPenalty(target) + currentPenalty(current.pos, target));
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
            int worstDrop = 0;
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
                int drop = dropDepth(gap);
                if (drop == DROP_HAZARD) {
                    return; // láva/oheň na dně v dohledné hloubce – radši obchůzka
                }
                worstDrop = Math.max(worstDrop, drop);
                // Smrtící pád pod letem: nepovedený rozskok (strčení davem,
                // led, boj) = smrt. Příplatek roste s opatrností povahy, nad
                // bezednem (void) je dvojnásobný. Mělčí pády nechávají cenu
                // na samotné přirážce skoku – bolestivé, ne fatální.
                int deepPenalty = worstDrop < DEADLY_DROP ? 0
                        : worstDrop == DROP_BOTTOMLESS ? 2 * costDeepGap : costDeepGap;
                int base = span * (diagonal ? COST_DIAGONAL : COST_STRAIGHT) + costGapJump
                        + (span - 2) * costGapJump / 2   // širší mezera = větší riziko
                        + (diagonal ? costGapJump / 2 : 0) // rohový let je delší a těsnější
                        + deepPenalty;
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
                // Dopad o blok výš (jen kardinálně, jen jednobloková mezera):
                // parkour výskok na vyšší římsu přes díru. Letová dráha vede
                // výš – nad mezerou i nad odrazem musí být volno o buňku navíc.
                if (!diagonal && span == 2) {
                    BlockPos higher = landing.up();
                    double higherFeet = feetHeight(higher);
                    if (flatLanding(higher, higherFeet)
                            && transitClear(pos.offset(0, 3, 0))
                            && transitClear(gap.offset(0, 3, 0))
                            && !traits(higher).liquid()) {
                        int cost = base + costJump + terrainPenalty(higher);
                        tryAdd(current, higher, cost);
                        return;
                    }
                }
            }
        }

        /** Průchozí sloupec letové dráhy: úroveň nohou, hlavy i nad hlavou. */
        private boolean columnClear(BlockPos feet) {
            return flightClear(feet) && flightClear(feet.up())
                    && flightClear(feet.offset(0, 2, 0));
        }

        /**
         * Průchozí bez ruky na klice – jako {@link #transitClear}, ale zavřené
         * dveře překážejí. Pro letovou dráhu skoku (uprostřed letu se dveře
         * neotevřou) a rohy diagonál (při řezání rohu bot dveře neotvírá
         * a odřel by se o jejich kolizi). Otevřené dveře nevadí (bez kolize).
         */
        private boolean flightClear(BlockPos pos) {
            BlockTraits t = traits(pos);
            return t != BlockTraits.UNKNOWN && !t.hazard() && !t.web() && t.lowProfile();
        }

        /**
         * Hloubka pádu pod sloupcem: počet bloků k pevnému dnu
         * (1 = dno hned pod), {@link #DROP_WATER} pro hlubokou vodu (pád
         * bezpečně tlumí, mělčina se bere jako pevné dno),
         * {@link #DROP_HAZARD} při hazardu kdekoli v dohledné hloubce
         * {@link #DEEP_HAZARD_SCAN} a {@link #DROP_BOTTOMLESS} bez dna (void).
         */
        private int dropDepth(BlockPos top) {
            BlockPos pos = top.down();
            for (int depth = 1; depth <= DEEP_HAZARD_SCAN; depth++) {
                BlockTraits t = traits(pos);
                if (t.hazard()) {
                    return DROP_HAZARD;
                }
                if (t.liquid()) {
                    return traits(pos.down()).liquid() ? DROP_WATER : depth;
                }
                if (!t.noCollision()) {
                    return depth;
                }
                pos = pos.down();
            }
            return DROP_BOTTOMLESS;
        }

        /** Diagonála je povolená jen, když jsou oba přiléhající sloupce průchozí (žádné řezání rohů). */
        private boolean canCutCorner(BlockPos pos, int dx, int dz) {
            return flightClear(pos.offset(dx, 0, 0)) && flightClear(pos.offset(dx, 1, 0))
                    && flightClear(pos.offset(0, 0, dz)) && flightClear(pos.offset(0, 1, dz));
        }

        /** Přidá cíl, pokud je pochozí (pomocník pro šplhání/plavání). */
        private void tryAddStandable(Node parent, BlockPos pos, int moveCost) {
            if (!Double.isNaN(feetHeight(pos))) {
                tryAdd(parent, pos, moveCost);
            }
        }

        /**
         * Kopací hrany ve 4 kardinálních směrech: tunel 1×2 na stejné úrovni,
         * vylámaný schod vzhůru a schod dolů (nikdy kolmá šachta). Každý
         * vykopávaný blok musí být pevný, mimo deny-list chráněných materiálů
         * a bez tekutiny v 6-okolí (vykopnutí by zatopilo štolu).
         */
        private void tryDigEdges(Node current) {
            BlockPos pos = current.pos;
            for (int dir = 0; dir < 4; dir++) {
                int dx = dir == 0 ? 1 : dir == 1 ? -1 : 0;
                int dz = dir == 2 ? 1 : dir == 3 ? -1 : 0;
                BlockPos target = pos.offset(dx, 0, dz);

                // Tunel 1×2: podlaha pod cílem musí být plná (nekopat si jámu).
                BlockTraits below = traits(target.down());
                if (below.floorHeight() >= 0.99 && below.floorHeight() <= 1.01) {
                    List<BlockPos> digs = digsFor(target.up(), target);
                    if (digs != null && !digs.isEmpty()) {
                        tryAdd(current, target,
                                COST_STRAIGHT + digs.size() * COST_DIG,
                                new TerrainAction(digs));
                    }
                }

                // Schod vzhůru: cílový blok zůstává jako opora, lámou se buňky
                // nad ním a strop nad hlavou (výskok potřebuje volných +2).
                BlockTraits step = traits(target);
                if (step.solid() && step.floorHeight() >= 0.99 && step.floorHeight() <= 1.01) {
                    List<BlockPos> digs = digsFor(pos.offset(0, 2, 0), target.up(),
                            target.offset(0, 2, 0));
                    if (digs != null && !digs.isEmpty()) {
                        tryAdd(current, target.up(),
                                COST_STRAIGHT + costJump + digs.size() * COST_DIG,
                                new TerrainAction(digs));
                    }
                }

                // Schod dolů: vylámat zářez 1×2 o patro níž a sestoupit do něj.
                BlockTraits deepBelow = traits(target.offset(0, -2, 0));
                if (deepBelow.floorHeight() >= 0.99 && deepBelow.floorHeight() <= 1.01) {
                    List<BlockPos> digs = digsFor(target, target.down());
                    if (digs != null && !digs.isEmpty()) {
                        tryAdd(current, target.down(),
                                COST_STRAIGHT + costFallPerBlock + digs.size() * COST_DIG,
                                new TerrainAction(digs));
                    }
                }
            }
        }

        /**
         * Bloky k vykopání pro průchod danými buňkami (shora dolů), nebo
         * {@code null} když průchod vykopat nejde (chráněný materiál, tekutina
         * v okolí, neznámo). Prázdný seznam = buňky už jsou průchozí a hranu
         * řeší obyčejná chůze.
         */
        private List<BlockPos> digsFor(BlockPos... cells) {
            List<BlockPos> digs = new ArrayList<>(cells.length);
            for (BlockPos cell : cells) {
                if (transitClear(cell)) {
                    continue;
                }
                BlockTraits t = traits(cell);
                if (t.solid() && diggable(cell) && !liquidNear(cell)) {
                    digs.add(cell);
                    continue;
                }
                return null; // tekutina, neznámo, hazard, chráněný blok
            }
            // Gravitační pojistka: padavý blok (písek, štěrk) přímo nad
            // vykopanou buňkou by se sesypal do štoly – waypoint by se po
            // výkopu zase zablokoval a plán rozpadl na smyčku replánů.
            // Padavý blok smí být sám cílem výkopu; vadí jen nekopaný soused
            // nad čerstvou dírou.
            for (BlockPos cell : digs) {
                BlockPos above = cell.up();
                if (!digs.contains(above) && gravityBlock(above)) {
                    return null;
                }
            }
            return digs;
        }

        /** Je v buňce padavý blok (sesypal by se do vykopnuté štoly)? */
        private boolean gravityBlock(BlockPos cell) {
            org.bukkit.Material material = world.materialAt(cell);
            return material != null && gravityMaterial(material);
        }

        /**
         * Pokládací hrany: most přes chybějící podlahu (opěra pod cílovou
         * buňku) a pilíř pod vlastní nohy (uzel o patro výš). Pokládá se jen
         * do čistě prázdných buněk – nikdy do tekutin (lávová jezera řeší
         * reaktivní BridgeTask) a nikdy s lávou přímo pod opěrou (pád
         * z mostku by byl rozsudek). Rozpočet bloků hlídá {@code placeCount}
         * podél celé cesty.
         */
        private void tryPlaceEdges(Node current) {
            BlockPos pos = current.pos;
            if (current.placeCount >= options.maxPlacements()) {
                return;
            }
            // Most: sousední sloupec bez podlahy → položit opěru pod cíl.
            for (int dir = 0; dir < 4; dir++) {
                int dx = dir == 0 ? 1 : dir == 1 ? -1 : 0;
                int dz = dir == 2 ? 1 : dir == 3 ? -1 : 0;
                BlockPos target = pos.offset(dx, 0, dz);
                BlockPos support = target.down();
                // Hloubkový sken pod oporou (jako u skoků): láva kdekoli
                // v dohledné hloubce pod mostkem = žádný most, i o patro výš.
                // Bezedno mostění nebrání – mosty mezi ostrovy (End) jsou
                // bezpečná chůze po položených blocích.
                if (!placeable(support) || dropDepth(support) == DROP_HAZARD) {
                    continue;
                }
                if (!transitClear(target) || !transitClear(target.up())) {
                    continue;
                }
                tryAdd(current, target,
                        COST_STRAIGHT + COST_PLACE + terrainPenalty(target),
                        new TerrainAction(List.of(), List.of(support)));
            }
            // Pilíř: blok pod nohy při výskoku – uzel o patro výš. Potřebuje
            // volný prostor na výskok (+2, +3) a čistě prázdnou vlastní buňku.
            if (placeable(pos)
                    && transitClear(pos.offset(0, 2, 0))
                    && transitClear(pos.offset(0, 3, 0))) {
                tryAdd(current, pos.up(),
                        costJump + COST_PLACE + terrainPenalty(pos.up()),
                        new TerrainAction(List.of(), List.of(pos)));
            }
        }

        /**
         * Žebříkový výstup: stěna výšky 2–{@link #MAX_LADDER} v kardinálním
         * směru, na kterou se z footholdu nalepí sloupec příček (exekuci
         * obstará {@code LadderTask}). Stěna musí mít po celé výšce plné
         * solidní čelo (žebřík se přichytává na plný blok – desky, schody,
         * ploty i truhly vypadnou samy tvarem), botův vlastní sloupec musí
         * být průchozí a suchý až nad hranu a nahoře musí být pochozí dosed.
         * Jednoblokovou stěnu řeší obyčejný výskok, vyšší než strop assist.
         */
        private void tryLadderEdges(Node current) {
            BlockPos pos = current.pos;
            for (int dir = 0; dir < 4; dir++) {
                int dx = dir == 0 ? 1 : dir == 1 ? -1 : 0;
                int dz = dir == 2 ? 1 : dir == 3 ? -1 : 0;
                // Výška stěny: souvislé plné solidní čelo od úrovně nohou.
                int height = 0;
                while (height < MAX_LADDER) {
                    BlockTraits wall = traits(pos.offset(dx, height, dz));
                    if (!wall.solid() || wall.stepFriendly()
                            || Math.abs(wall.floorHeight() - 1.0) > EPS) {
                        break;
                    }
                    height++;
                }
                if (height < 2 || current.ladderCount + height > options.maxLadders()) {
                    continue; // nízká stěna (stačí skok), nebo málo žebříků
                }
                // Vlastní sloupec bota: průchozí a suchý po celé výšce
                // stěny + hlava nad hranou (příčky se lepí do těchto buněk).
                boolean clear = true;
                for (int k = 1; k <= height + 1 && clear; k++) {
                    BlockPos cell = pos.offset(0, k, 0);
                    clear = transitClear(cell) && !traits(cell).liquid();
                }
                if (!clear) {
                    continue;
                }
                // Dosed na vršku stěny.
                BlockPos top = new BlockPos(pos.x() + dx, pos.y() + height, pos.z() + dz);
                if (traits(top).liquid() || Double.isNaN(feetHeight(top))) {
                    continue;
                }
                int cost = height * (COST_PLACE + costClimb) + terrainPenalty(top);
                tryAdd(current, top, cost, TerrainAction.ladderClimb(dx, dz, height));
            }
        }

        /** Čistě prázdná buňka, do které lze položit blok. */
        private boolean placeable(BlockPos cell) {
            BlockTraits t = traits(cell);
            return t != BlockTraits.UNKNOWN && t.noCollision()
                    && !t.liquid() && !t.hazard() && !t.web();
        }

        /** Smí se blok vykopat? (mimo deny-list chráněných materiálů) */
        private boolean diggable(BlockPos cell) {
            org.bukkit.Material material = world.materialAt(cell);
            return material != null && !protectedMaterial(material);
        }

        /** Tekutina v 6-okolí bloku – vykopnutí by zatopilo štolu. */
        private boolean liquidNear(BlockPos cell) {
            return traits(cell.up()).liquid() || traits(cell.down()).liquid()
                    || traits(cell.offset(1, 0, 0)).liquid()
                    || traits(cell.offset(-1, 0, 0)).liquid()
                    || traits(cell.offset(0, 0, 1)).liquid()
                    || traits(cell.offset(0, 0, -1)).liquid();
        }

        /** Přidá uzel do open setu, pokud zlepšuje dosavadní cestu. */
        private void tryAdd(Node parent, BlockPos pos, int moveCost) {
            tryAdd(parent, pos, moveCost, null);
        }

        /** Přidá uzel (případně se zásahem do terénu), pokud zlepšuje cestu. */
        private void tryAdd(Node parent, BlockPos pos, int moveCost, TerrainAction action) {
            int placeCount = parent.placeCount
                    + (action == null ? 0 : action.places().size());
            if (placeCount > options.maxPlacements()) {
                return; // rozpočet bloků na cestu vyčerpán
            }
            int ladderCount = parent.ladderCount
                    + (action == null || action.ladder() == null ? 0 : action.ladder().height());
            if (ladderCount > options.maxLadders()) {
                return; // rozpočet žebříků na cestu vyčerpán
            }
            long key = pos.asLong();
            int g = parent.g + moveCost + dangerPenalty(pos);
            Node existing = visited.get(key);
            if (existing != null && existing.g <= g) {
                return;
            }
            Node node = new Node(pos, parent, g, goal.heuristic(pos), action,
                    placeCount, ladderCount);
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
         * Přirážka za horizontální plavání proti proudu: cíl je tekoucí voda
         * a její proud míří proti směru kroku. Zdrojová voda (hladina 0) se
         * odbaví levně bez výpočtu proudu; gradient se čte z memo cache.
         */
        private int currentPenalty(BlockPos from, BlockPos to) {
            BlockTraits t = traits(to);
            if (t.liquidLevel() <= 0) {
                return 0; // suchý cíl nebo zdrojová voda – bez proudu
            }
            Vec3 flow = dev.botalive.core.world.WaterFlow.at(this::traits, to);
            double dot = flow.x() * (to.x() - from.x()) + flow.z() * (to.z() - from.z());
            return dot < -0.3 ? COST_AGAINST_CURRENT : 0;
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
            // Hazard v okolí 1 bloku → vysoká penalizace (bot se drží dál od
            // lávy). Stejný sken zadarmo sbírá i cestový povrch v okolí pro
            // preferenci cestiček.
            boolean pathNearby = support.pathSurface();
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    BlockTraits at = traits(feet.offset(dx, 0, dz));
                    BlockTraits below = traits(feet.offset(dx, -1, dz));
                    if (at.hazard() || below.hazard()) {
                        return penalty + costNearHazard;
                    }
                    pathNearby |= at.pathSurface() || below.pathSurface();
                }
            }
            // Preference cestiček: šlapat terén HNED VEDLE cestového povrchu
            // nese drobnou přirážku – bot jdoucí podél cestičky na ni uhne.
            // Daleko od cest se ceny nemění (přípustnost i rozpočty hledání).
            if (pathNearby && !support.pathSurface()) {
                penalty += COST_OFF_PATH;
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
        /** Zásah do terénu nutný před vkročením na uzel ({@code null} = chůze). */
        final TerrainAction action;
        /** Bloky položené na cestě od startu k uzlu (rozpočet inventáře). */
        final int placeCount;
        /** Žebříkové příčky spotřebované na cestě od startu (rozpočet inventáře). */
        final int ladderCount;
        boolean closed;

        Node(BlockPos pos, Node parent, int g, int h) {
            this(pos, parent, g, h, null, 0, 0);
        }

        Node(BlockPos pos, Node parent, int g, int h, TerrainAction action,
             int placeCount, int ladderCount) {
            this.pos = pos;
            this.parent = parent;
            this.g = g;
            this.h = h;
            this.action = action;
            this.placeCount = placeCount;
            this.ladderCount = ladderCount;
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
