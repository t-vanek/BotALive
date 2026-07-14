package dev.botalive.core.crafting;

import org.bukkit.Material;

import java.util.HashMap;
import java.util.Map;

/**
 * Čistý plánovač survival crafting progrese bota.
 *
 * <p>Jediný zdroj pravdy pro „co vyrobit dál" – sdílený server-side
 * implementací ({@link CraftingService}, čte živý Bukkit inventář)
 * i paketovou stanicí (čte klientský model inventáře). Vstupem je
 * {@link State} – souhrn inventáře nezávislý na zdroji – takže progrese
 * jde testovat jednotkově.</p>
 */
public final class CraftPlanner {

    /**
     * Jeden plán receptu: co vyrobit a z čeho (3×3 matice, row-major).
     *
     * @param id         české označení výrobku (pro chat/log)
     * @param matrix     rozložení surovin v mřížce 3×3 (row-major, null = prázdné)
     * @param needsTable {@code true} pokud recept přesahuje 2×2 (vyžaduje ponk)
     */
    public record Plan(String id, Material[] matrix, boolean needsTable) {

        /** @return spotřeba materiálů (materiál → počet kusů) */
        public Map<Material, Integer> ingredients() {
            Map<Material, Integer> counts = new HashMap<>();
            for (Material material : matrix) {
                if (material != null) {
                    counts.merge(material, 1, Integer::sum);
                }
            }
            return counts;
        }
    }

    /**
     * Souhrn inventáře pro plánování – nezávislý na zdroji dat.
     *
     * @param logs         počet klád (libovolný druh)
     * @param planks       počet prken
     * @param sticks       počet tyček
     * @param cobble       počet cobblestone + cobbled deepslate
     * @param hasTable     má ponk (v inventáři)
     * @param hasWoodPick  má jakýkoli krumpáč
     * @param hasStonePick má kamenný nebo lepší krumpáč
     * @param hasSword     má jakýkoli meč
     * @param hasStoneSword má kamenný meč
     * @param hasAxe       má sekeru (ne krumpáč)
     * @param logType      konkrétní druh klády (null = žádná)
     * @param plankType    konkrétní druh prken (null = žádná)
     * @param stoneType    druh kamene pro nástroje (cobblestone/deepslate)
     */
    public record State(int logs, int planks, int sticks, int cobble,
                        boolean hasTable, boolean hasWoodPick, boolean hasStonePick,
                        boolean hasSword, boolean hasStoneSword, boolean hasAxe,
                        Material logType, Material plankType, Material stoneType) {
    }

    private CraftPlanner() {
    }

    /**
     * Rozhodne další krok survival progrese: suroviny → ponk → dřevěné →
     * kamenné nástroje.
     *
     * @param s souhrn inventáře
     * @return plán, nebo {@code null} když nic nedává smysl
     */
    public static Plan next(State s) {
        if (s.planks() < 4 && s.logs() >= 1 && s.logType() != null) {
            return new Plan("prkna", matrix(s.logType(), 0), false);
        }
        if (s.sticks() < 4 && s.planks() >= 2 && s.plankType() != null) {
            return new Plan("tyčky", matrix(s.plankType(), 0, s.plankType(), 3), false);
        }
        if (!s.hasTable() && s.planks() >= 4 && s.plankType() != null) {
            return new Plan("ponk", matrix(s.plankType(), 0, s.plankType(), 1,
                    s.plankType(), 3, s.plankType(), 4), false);
        }
        if (s.hasTable() && s.plankType() != null && s.sticks() >= 2 && s.planks() >= 3
                && !s.hasWoodPick()) {
            return new Plan("dřevěný krumpáč", matrix(
                    s.plankType(), 0, s.plankType(), 1, s.plankType(), 2,
                    Material.STICK, 4, Material.STICK, 7), true);
        }
        if (s.hasTable() && s.plankType() != null && s.sticks() >= 1 && s.planks() >= 2
                && !s.hasSword()) {
            return new Plan("dřevěný meč", matrix(
                    s.plankType(), 1, s.plankType(), 4, Material.STICK, 7), true);
        }
        if (s.hasTable() && s.plankType() != null && s.sticks() >= 2 && s.planks() >= 3
                && !s.hasAxe()) {
            return new Plan("dřevěná sekera", matrix(
                    s.plankType(), 0, s.plankType(), 1, s.plankType(), 3,
                    Material.STICK, 4, Material.STICK, 7), true);
        }
        if (s.hasTable() && s.cobble() >= 3 && s.sticks() >= 2 && !s.hasStonePick()) {
            return new Plan("kamenný krumpáč", matrix(
                    s.stoneType(), 0, s.stoneType(), 1, s.stoneType(), 2,
                    Material.STICK, 4, Material.STICK, 7), true);
        }
        if (s.hasTable() && s.cobble() >= 2 && s.sticks() >= 1
                && !s.hasStoneSword() && s.hasSword()) {
            // upgrade meče na kamenný (dřevěný už má)
            return new Plan("kamenný meč", matrix(
                    s.stoneType(), 1, s.stoneType(), 4, Material.STICK, 7), true);
        }
        return null;
    }

    /** Sestaví 3×3 matici z dvojic (materiál, index). */
    private static Material[] matrix(Object... pairs) {
        Material[] matrix = new Material[9];
        for (int i = 0; i < pairs.length; i += 2) {
            matrix[(Integer) pairs[i + 1]] = (Material) pairs[i];
        }
        return matrix;
    }
}
