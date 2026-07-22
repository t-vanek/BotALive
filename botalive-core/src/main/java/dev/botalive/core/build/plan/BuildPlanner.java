package dev.botalive.core.build.plan;

import dev.botalive.core.tasks.PillarUpTask;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.WorldView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Čistá funkce: z {@link BuildPlan plánu} a stavu světa spočítá
 * {@link BuildSchedule proveditelný rozvrh}. Zobecňuje ruční „pořadí se
 * zaručenou oporou" z {@code PortalBlueprint} na libovolný blueprint –
 * pokládku setřídí tak, aby každý blok měl v okamžiku položení pevného
 * souseda (podlaha nebo dřív položený blok), a naplánuje úpravy terénu
 * po vzoru {@code BuildHouseGoal.planTerraform}.
 *
 * <p>Bez závislosti na bukkitu ani navigaci – jen geometrie nad
 * {@link WorldView}; bezpečné volat na tick vlákně (spočítá se jednou
 * a cachuje).</p>
 */
public final class BuildPlanner {

    /**
     * Bezpečný dosah na střed bloku pro přiřazení ke stanovišti (konzervativně
     * pod vanilla 4,5 – rezerva na chybu zamíření a pozici). Co je dál, dostane
     * bližší stanoviště.
     */
    private static final double REACH_ASSIGN = 4.3;
    /** Výška očí nad nohama (jako {@code PlaceBlockTask}). */
    private static final double EYE_HEIGHT = 1.62;

    private BuildPlanner() {
    }

    /**
     * Setřídí bloky do pořadí, v němž má každý při pokládce oporu. Oporu dává
     * podlaha ({@code groundColumns}) nebo kterýkoli už položený blok – shodně
     * s {@code PlaceBlockTask.findSupport} (pevný soused v 6-okolí).
     *
     * @param cells         bloky stavby (nezávazné pořadí)
     * @param groundColumns podlaha pod stavbou (výchozí opora)
     * @return bloky v pořadí pokládky
     * @throws UnsupportableBlueprintException když stavba obsahuje visící blok
     */
    public static List<PlacementCell> order(List<PlacementCell> cells,
                                            Collection<BlockPos> groundColumns) {
        Set<Long> solid = new HashSet<>();
        for (BlockPos ground : groundColumns) {
            solid.add(ground.asLong());
        }
        List<PlacementCell> remaining = new ArrayList<>(cells);
        List<PlacementCell> ordered = new ArrayList<>(cells.size());
        boolean progress = true;
        while (!remaining.isEmpty() && progress) {
            progress = false;
            Iterator<PlacementCell> it = remaining.iterator();
            while (it.hasNext()) {
                PlacementCell cell = it.next();
                if (hasSupport(cell.pos(), solid)) {
                    ordered.add(cell);
                    solid.add(cell.pos().asLong());
                    it.remove();
                    progress = true;
                }
            }
        }
        if (!remaining.isEmpty()) {
            throw new UnsupportableBlueprintException(remaining);
        }
        return ordered;
    }

    /**
     * Sestaví rozvrh: úpravy terénu podle aktuálního světa a pokládku
     * v pořadí s oporou.
     *
     * @param plan  rozvinutý plán stavby
     * @param world stav světa (načtené okolí staveniště)
     * @return proveditelný rozvrh
     */
    public static BuildSchedule schedule(BuildPlan plan, WorldView world) {
        Set<Long> structure = new HashSet<>();
        for (PlacementCell cell : plan.cells()) {
            structure.add(cell.pos().asLong());
        }
        // Výkopy: pevné bloky v objemu stavby, které nepatří stavbě samotné.
        List<BlockPos> mine = new ArrayList<>();
        for (BlockPos space : plan.clearVolume()) {
            if (!structure.contains(space.asLong()) && world.traitsAt(space).solid()) {
                mine.add(space);
            }
        }
        // Zásypy: díry v podlaze pod stavbou.
        List<BlockPos> fill = new ArrayList<>();
        for (BlockPos ground : plan.groundColumns()) {
            if (!world.traitsAt(ground).solid()) {
                fill.add(ground);
            }
        }
        List<PlacementCell> ordered = order(plan.cells(), plan.groundColumns());
        List<WorkUnit> units = segment(ordered, plan);
        List<BlockPos> scaffold = scaffoldFor(units, plan.stand().y());
        return new BuildSchedule(mine, fill, units, plan.stand(),
                plan.standExact(), plan.furnishing(), scaffold);
    }

    /**
     * Dočasné bloky lešení: pod každým vyvýšeným stanovištěm (nad úrovní
     * podlahy) stojí pilíř od podlahy po blok pod nohama stavitele – ten si ho
     * cestou nahoru sám vypilíruje ({@code PillarUpTask}) a {@code BuildSession}
     * ho po dostavbě vytěží. Stanoviště na úrovni podlahy pilíř nepotřebují.
     */
    private static List<BlockPos> scaffoldFor(List<WorkUnit> units, int floorY) {
        Set<BlockPos> scaffold = new LinkedHashSet<>();
        for (WorkUnit unit : units) {
            BlockPos stand = unit.stand();
            for (int y = floorY; y < stand.y(); y++) {
                scaffold.add(new BlockPos(stand.x(), y, stand.z()));
            }
        }
        return new ArrayList<>(scaffold);
    }

    /**
     * Rozdělí pořadí pokládky do dávek po stanovištích. Malá stavba, na kterou
     * bot dosáhne celou z deklarovaného stanoviště, zůstane jedinou jednotkou
     * (parita s dřívějším chováním). Velká se rozpadne: bot přechází po vnitřní
     * podlaze a z každého stanoviště klade, na co pohodlně dosáhne – pořadí
     * bloků se zachová, takže opora drží i napříč jednotkami.
     */
    private static List<WorkUnit> segment(List<PlacementCell> ordered, BuildPlan plan) {
        if (ordered.isEmpty()) {
            return List.of(new WorkUnit(plan.stand(), List.of()));
        }
        if (allReachable(ordered, plan.stand())) {
            return List.of(new WorkUnit(plan.stand(), ordered));
        }
        List<BlockPos> stands = candidateStands(plan);
        List<WorkUnit> units = new ArrayList<>();
        BlockPos current = null;
        List<PlacementCell> batch = new ArrayList<>();
        for (int i = 0; i < ordered.size(); i++) {
            PlacementCell cell = ordered.get(i);
            if (current != null && reaches(current, cell.pos())) {
                batch.add(cell);
                continue;
            }
            if (current != null) {
                units.add(new WorkUnit(current, batch));
                batch = new ArrayList<>();
            }
            current = pickStand(stands, ordered, i);
            batch.add(cell);
        }
        units.add(new WorkUnit(current, batch));
        return units;
    }

    /** Stanoviště pokrývající nejdelší souvislý úsek pokládky od {@code from}. */
    private static BlockPos pickStand(List<BlockPos> stands, List<PlacementCell> ordered,
                                      int from) {
        BlockPos best = stands.isEmpty() ? ordered.get(from).pos() : stands.get(0);
        int bestRun = -1;
        for (BlockPos stand : stands) {
            if (!reaches(stand, ordered.get(from).pos())) {
                continue;
            }
            int run = 0;
            while (from + run < ordered.size() && reaches(stand, ordered.get(from + run).pos())) {
                run++;
            }
            if (run > bestRun) {
                bestRun = run;
                best = stand;
            }
        }
        return best;
    }

    /**
     * Kandidátní stanoviště: volná místa vnitřní podlahy (nad podlahou, mimo
     * stavbu) a nad nimi <b>vyvýšená pilířová stanoviště</b> pro bloky, na které
     * ze země není dosah. Pilíř roste volným vnitřním sloupcem; stavitel na něj
     * vyleze pilířováním ({@code PillarUpTask}) a po dostavbě ho session odklidí.
     * Stoupá se, dokud je sloupec (nohy i hlava) volný a nepřesáhne strop
     * jednoho pilíře ({@code PillarUpTask.MAX_HEIGHT}) ani vršek stavby.
     */
    private static List<BlockPos> candidateStands(BuildPlan plan) {
        Set<Long> structure = new HashSet<>();
        int topY = Integer.MIN_VALUE;
        for (PlacementCell cell : plan.cells()) {
            structure.add(cell.pos().asLong());
            topY = Math.max(topY, cell.pos().y());
        }
        // Stanoviště na podlaze: deklarované + volná vnitřní podlaha.
        Set<BlockPos> ground = new LinkedHashSet<>();
        ground.add(plan.stand());
        for (BlockPos g : plan.groundColumns()) {
            BlockPos floor = g.up();
            if (!structure.contains(floor.asLong())) {
                ground.add(floor); // sloupec je zastavěný (zeď) → přeskočí se
            }
        }
        // Lešení přidáme jen když na některý blok ze země nedosáhne žádné
        // stanoviště – jinak se staví po podlaze (víc stanovišť, ale bez pilířů,
        // což je levnější i čistší; velké sály takhle staví dál jako dřív).
        if (reachableFromSome(plan.cells(), ground)) {
            return new ArrayList<>(ground);
        }
        // Vyvýšená pilířová stanoviště nad volnými vnitřními sloupci.
        Set<BlockPos> stands = new LinkedHashSet<>(ground);
        for (BlockPos floor : ground) {
            int maxY = Math.min(topY, floor.y() + PillarUpTask.MAX_HEIGHT);
            for (int y = floor.y() + 1; y <= maxY; y++) {
                BlockPos stand = new BlockPos(floor.x(), y, floor.z());
                if (structure.contains(stand.asLong()) || structure.contains(stand.up().asLong())) {
                    break; // tělo bota (nohy/hlava) by kolidovalo se stavbou
                }
                stands.add(stand);
            }
        }
        return new ArrayList<>(stands);
    }

    /** Dosáhne na každý blok aspoň jedno ze stanovišť? */
    private static boolean reachableFromSome(List<PlacementCell> cells,
                                             Collection<BlockPos> stands) {
        for (PlacementCell cell : cells) {
            boolean any = false;
            for (BlockPos stand : stands) {
                if (reaches(stand, cell.pos())) {
                    any = true;
                    break;
                }
            }
            if (!any) {
                return false;
            }
        }
        return true;
    }

    private static boolean allReachable(List<PlacementCell> cells, BlockPos stand) {
        for (PlacementCell cell : cells) {
            if (!reaches(stand, cell.pos())) {
                return false;
            }
        }
        return true;
    }

    /** Dosáhne stavitel ze stanoviště na střed bloku s rezervou? */
    private static boolean reaches(BlockPos stand, BlockPos cell) {
        Vec3 eye = new Vec3(stand.x() + 0.5, stand.y() + EYE_HEIGHT, stand.z() + 0.5);
        Vec3 center = new Vec3(cell.x() + 0.5, cell.y() + 0.5, cell.z() + 0.5);
        return eye.distanceSquared(center) <= REACH_ASSIGN * REACH_ASSIGN;
    }

    /** Má pozice pevného souseda v 6-okolí? (parita s {@code PlaceBlockTask}). */
    private static boolean hasSupport(BlockPos pos, Set<Long> solid) {
        return solid.contains(pos.down().asLong())
                || solid.contains(pos.up().asLong())
                || solid.contains(pos.offset(1, 0, 0).asLong())
                || solid.contains(pos.offset(-1, 0, 0).asLong())
                || solid.contains(pos.offset(0, 0, 1).asLong())
                || solid.contains(pos.offset(0, 0, -1).asLong());
    }
}
