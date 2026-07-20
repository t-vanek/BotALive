package dev.botalive.core.build.plan;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.WorldView;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
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
        return new BuildSchedule(mine, fill, ordered, plan.stand(), plan.furnishing());
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
