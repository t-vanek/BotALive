package dev.botalive.core.crafting;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Čistý plánovač kliků pro rozmístění receptu do crafting mřížky.
 *
 * <p>Vstup: 3×3 matice receptu, mapování buněk matice na sloty okna a obsah
 * hráčovy sekce okna (slot → materiál + počet). Výstup: posloupnost kliků
 * ve stylu hráče – levý klik zvedne zdrojový stack, pravé kliky pokládají
 * po jednom kusu do buněk, levý klik vrátí zbytek do zdrojového slotu.</p>
 *
 * <p>Mřížka 2×2 vlastního inventáře používá buňky matice {0,1,3,4} → sloty
 * okna 1–4; ponk mapuje všech 9 buněk na sloty 1–9.</p>
 */
public final class GridPlacer {

    /** Druh kliku v plánu. */
    public enum Kind {
        /** Levý klik (zvednutí/vrácení stacku). */
        LEFT,
        /** Pravý klik (položení jednoho kusu). */
        RIGHT
    }

    /**
     * Jeden krok plánu.
     *
     * @param kind druh kliku
     * @param slot slot okna
     */
    public record Step(Kind kind, int slot) {
    }

    /** Mapování buněk 3×3 matice na sloty 2×2 mřížky vlastního inventáře. */
    public static final int[] PLAYER_GRID = {1, 2, -1, 3, 4, -1, -1, -1, -1};

    /** Mapování buněk 3×3 matice na sloty mřížky ponku. */
    public static final int[] TABLE_GRID = {1, 2, 3, 4, 5, 6, 7, 8, 9};

    private GridPlacer() {
    }

    /**
     * Naplánuje kliky pro rozmístění receptu.
     *
     * @param matrix      3×3 matice receptu (row-major, null = prázdná buňka)
     * @param gridMapping mapování buňky matice → slot okna ({@link #PLAYER_GRID}
     *                    / {@link #TABLE_GRID}); -1 = buňka mimo mřížku
     * @param playerSlots obsah hráčovy sekce okna: slot okna → materiál
     * @param playerCounts počty kusů ve slotech hráčovy sekce
     * @return kroky kliků, nebo {@code null} když suroviny nestačí nebo se
     *         recept do mřížky nevejde
     */
    public static List<Step> plan(Material[] matrix, int[] gridMapping,
                                  Map<Integer, Material> playerSlots,
                                  Map<Integer, Integer> playerCounts) {
        // Buňky podle materiálu (jeden zdvih stacku pokryje všechny jeho buňky).
        Map<Material, List<Integer>> cellsByMaterial = new HashMap<>();
        for (int cell = 0; cell < matrix.length; cell++) {
            if (matrix[cell] == null) {
                continue;
            }
            if (gridMapping[cell] < 0) {
                return null; // recept se do této mřížky nevejde
            }
            cellsByMaterial.computeIfAbsent(matrix[cell], m -> new ArrayList<>())
                    .add(gridMapping[cell]);
        }

        List<Step> steps = new ArrayList<>();
        Map<Integer, Integer> remaining = new HashMap<>(playerCounts);
        for (Map.Entry<Material, List<Integer>> entry : cellsByMaterial.entrySet()) {
            int source = findSource(entry.getKey(), entry.getValue().size(),
                    playerSlots, remaining);
            if (source < 0) {
                return null; // nedostatek surovin v jednom stacku
            }
            remaining.merge(source, -entry.getValue().size(), Integer::sum);
            steps.add(new Step(Kind.LEFT, source));           // zvednout stack
            for (int gridSlot : entry.getValue()) {
                steps.add(new Step(Kind.RIGHT, gridSlot));    // položit 1 kus
            }
            steps.add(new Step(Kind.LEFT, source));           // vrátit zbytek
        }
        return steps;
    }

    /** Najde slot hráčovy sekce s dostatkem daného materiálu. */
    private static int findSource(Material material, int needed,
                                  Map<Integer, Material> playerSlots,
                                  Map<Integer, Integer> remaining) {
        for (Map.Entry<Integer, Material> entry : playerSlots.entrySet()) {
            if (entry.getValue() == material
                    && remaining.getOrDefault(entry.getKey(), 0) >= needed) {
                return entry.getKey();
            }
        }
        return -1;
    }
}
