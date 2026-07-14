package dev.botalive.core.crafting;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Testy plánování kliků do crafting mřížky.
 */
class GridPlacerTest {

    private static Material[] matrix(Object... pairs) {
        Material[] matrix = new Material[9];
        for (int i = 0; i < pairs.length; i += 2) {
            matrix[(Integer) pairs[i + 1]] = (Material) pairs[i];
        }
        return matrix;
    }

    @Test
    void prknaJedenZdvihJednoPolozeni() {
        List<GridPlacer.Step> steps = GridPlacer.plan(
                matrix(Material.OAK_LOG, 0), GridPlacer.PLAYER_GRID,
                Map.of(36, Material.OAK_LOG), Map.of(36, 10));
        assertEquals(List.of(
                new GridPlacer.Step(GridPlacer.Kind.LEFT, 36),
                new GridPlacer.Step(GridPlacer.Kind.RIGHT, 1),
                new GridPlacer.Step(GridPlacer.Kind.LEFT, 36)), steps);
    }

    @Test
    void tyckyDvaKusyPodSebou() {
        List<GridPlacer.Step> steps = GridPlacer.plan(
                matrix(Material.OAK_PLANKS, 0, Material.OAK_PLANKS, 3), GridPlacer.PLAYER_GRID,
                Map.of(12, Material.OAK_PLANKS), Map.of(12, 6));
        // Buňka 0 → slot 1, buňka 3 → slot 3 (mřížka 2×2 vlastního inventáře).
        assertEquals(List.of(
                new GridPlacer.Step(GridPlacer.Kind.LEFT, 12),
                new GridPlacer.Step(GridPlacer.Kind.RIGHT, 1),
                new GridPlacer.Step(GridPlacer.Kind.RIGHT, 3),
                new GridPlacer.Step(GridPlacer.Kind.LEFT, 12)), steps);
    }

    @Test
    void krumpacVMrizcePonku() {
        List<GridPlacer.Step> steps = GridPlacer.plan(
                matrix(Material.OAK_PLANKS, 0, Material.OAK_PLANKS, 1, Material.OAK_PLANKS, 2,
                        Material.STICK, 4, Material.STICK, 7),
                GridPlacer.TABLE_GRID,
                Map.of(10, Material.OAK_PLANKS, 11, Material.STICK),
                Map.of(10, 8, 11, 4));
        // 2 materiály: 2 zdvihy + 2 vrácení + 5 položení = 9 kroků.
        assertEquals(9, steps.size());
        long rights = steps.stream().filter(s -> s.kind() == GridPlacer.Kind.RIGHT).count();
        assertEquals(5, rights);
        // Tyčky patří do slotů 5 a 8 (buňky 4 a 7 mřížky ponku).
        assertEquals(true, steps.contains(new GridPlacer.Step(GridPlacer.Kind.RIGHT, 5)));
        assertEquals(true, steps.contains(new GridPlacer.Step(GridPlacer.Kind.RIGHT, 8)));
    }

    @Test
    void receptPresahujici2x2SeDoInventareNevejde() {
        assertNull(GridPlacer.plan(
                matrix(Material.OAK_PLANKS, 0, Material.OAK_PLANKS, 1, Material.OAK_PLANKS, 2),
                GridPlacer.PLAYER_GRID,
                Map.of(36, Material.OAK_PLANKS), Map.of(36, 64)));
    }

    @Test
    void nedostatekSurovinVraciNull() {
        assertNull(GridPlacer.plan(
                matrix(Material.OAK_PLANKS, 0, Material.OAK_PLANKS, 1,
                        Material.OAK_PLANKS, 3, Material.OAK_PLANKS, 4),
                GridPlacer.PLAYER_GRID,
                Map.of(36, Material.OAK_PLANKS), Map.of(36, 2)));
    }
}
