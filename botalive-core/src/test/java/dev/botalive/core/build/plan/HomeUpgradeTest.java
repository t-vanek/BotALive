package dev.botalive.core.build.plan;

import dev.botalive.core.util.BlockPos;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Výběr bloků k povýšení domu na vyšší tier – čistá funkce. Povyšuje se po
 * celých rolích (nejnižší nedokončená první), jen stojící bloky z nižšího
 * materiálu; díry a okna sem nepatří.
 */
class HomeUpgradeTest {

    private static final Palette TARGET = new Palette(Map.of(
            PaletteRole.FOUNDATION, List.of(Material.STONE_BRICKS),
            PaletteRole.WALL, List.of(Material.BRICKS),
            PaletteRole.WINDOW, List.of(Material.GLASS)));

    private static final BlockPos FOUND = new BlockPos(0, 64, 0);
    private static final BlockPos WALL_A = new BlockPos(0, 65, 0);
    private static final BlockPos WALL_B = new BlockPos(1, 65, 0);
    private static final BlockPos WINDOW = new BlockPos(2, 65, 0);

    private static final List<PlacementCell> CELLS = List.of(
            new PlacementCell(FOUND, BlockSpec.of(PaletteRole.FOUNDATION)),
            new PlacementCell(WALL_A, BlockSpec.of(PaletteRole.WALL)),
            new PlacementCell(WALL_B, BlockSpec.of(PaletteRole.WALL)),
            new PlacementCell(WINDOW, BlockSpec.of(PaletteRole.WINDOW)));

    @Test
    void upgradesLowestRoleFirstAndOnlyMismatchedSolids() {
        Map<BlockPos, Material> world = new HashMap<>();
        world.put(FOUND, Material.COBBLESTONE);   // nižší materiál → povýšit
        world.put(WALL_A, Material.OAK_PLANKS);   // nižší materiál → povýšit
        world.put(WALL_B, Material.BRICKS);        // už cílový → nechat
        world.put(WINDOW, Material.AIR);           // otvor → řeší oprava, ne upgrade

        var plan = HomeUpgrade.next(CELLS, TARGET, world::get, 10).orElseThrow();
        assertEquals(PaletteRole.FOUNDATION, plan.role(), "nejnižší nedokončená role první");
        assertEquals(List.of(FOUND), positions(plan), "jen ne-cílový základ");
    }

    @Test
    void advancesToNextRoleWhenLowerRoleDone() {
        Map<BlockPos, Material> world = new HashMap<>();
        world.put(FOUND, Material.STONE_BRICKS);   // základ hotový
        world.put(WALL_A, Material.OAK_PLANKS);
        world.put(WALL_B, Material.SPRUCE_PLANKS);
        world.put(WINDOW, Material.AIR);

        var plan = HomeUpgrade.next(CELLS, TARGET, world::get, 10).orElseThrow();
        assertEquals(PaletteRole.WALL, plan.role(), "po základu přijde zeď");
        assertEquals(List.of(WALL_A, WALL_B), positions(plan), "obě ne-cílové zdi");
    }

    @Test
    void limitCapsCellsPerSession() {
        Map<BlockPos, Material> world = new HashMap<>();
        world.put(FOUND, Material.STONE_BRICKS);
        world.put(WALL_A, Material.OAK_PLANKS);
        world.put(WALL_B, Material.OAK_PLANKS);
        world.put(WINDOW, Material.AIR);

        var plan = HomeUpgrade.next(CELLS, TARGET, world::get, 1).orElseThrow();
        assertEquals(1, plan.cells().size(), "strop na seanci drží tempo střídmé");
    }

    @Test
    void nothingToUpgradeWhenAllTargetMaterial() {
        Map<BlockPos, Material> world = new HashMap<>();
        world.put(FOUND, Material.STONE_BRICKS);
        world.put(WALL_A, Material.BRICKS);
        world.put(WALL_B, Material.BRICKS);
        world.put(WINDOW, Material.AIR); // okno bez skla není důvod k upgradu (řeší oprava)

        assertEquals(Optional.empty(), HomeUpgrade.next(CELLS, TARGET, world::get, 10),
                "dům je celý na cílovém tieru");
    }

    @Test
    void skipsRolesWithoutTargetMaterial() {
        // GENERIC role (legacy dům) nemá cílový materiál → nikdy se nepovyšuje.
        List<PlacementCell> generic = List.of(
                new PlacementCell(FOUND, BlockSpec.GENERIC),
                new PlacementCell(WALL_A, BlockSpec.GENERIC));
        Map<BlockPos, Material> world = new HashMap<>();
        world.put(FOUND, Material.COBBLESTONE);
        world.put(WALL_A, Material.DIRT);
        assertTrue(HomeUpgrade.next(generic, Palette.GENERIC, world::get, 10).isEmpty(),
                "legacy dům se nepovyšuje");
    }

    private static List<BlockPos> positions(HomeUpgrade.Plan plan) {
        return plan.cells().stream().map(PlacementCell::pos).toList();
    }
}
