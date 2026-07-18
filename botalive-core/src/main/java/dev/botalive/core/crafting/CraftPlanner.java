package dev.botalive.core.crafting;

import org.bukkit.Material;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Predicate;

/**
 * Čistý plánovač survival crafting progrese bota.
 *
 * <p>Jediný zdroj pravdy pro „co vyrobit dál" – sdílený server-side
 * implementací ({@link CraftingService}, čte živý Bukkit inventář)
 * i paketovou stanicí (čte klientský model inventáře). Vstupem je
 * {@link State} – mapa materiálů v inventáři – takže progrese jde
 * testovat jednotkově.</p>
 *
 * <p>Kompletní řetěz: suroviny → ponk → dřevěné → kamenné nástroje →
 * pec → pochodně → železné nástroje a sekera → štít → železné brnění →
 * diamantové nástroje a brnění → luk a šípy → výbava do Netheru
 * (křesadlo, zlaté boty) → truhla → loďka. Diamantová generace nahrazuje
 * železnou automaticky (kontroly „už má lepší" berou vyšší tier v potaz).
 * Netherit uzavírá progresi: starodávné trosky z Netheru se taví na
 * úlomky ({@code FurnaceService}), 4 úlomky + 4 zlato = ingot a povýšení
 * diamantové výbavy provádí kovářský stůl ({@code SmithingService},
 * s šablonou z bastionu).</p>
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
     * @param items     materiál → počet kusů (jen nenulové položky)
     * @param logType   konkrétní druh klády (null = žádná)
     * @param plankType konkrétní druh prken (null = žádná)
     * @param stoneType druh kamene pro nástroje (cobblestone/deepslate)
     * @param woolType  konkrétní druh vlny (null = žádná)
     */
    public record State(Map<Material, Integer> items, Material logType,
                        Material plankType, Material stoneType, Material woolType) {

        /** @return počet kusů materiálu */
        public int count(Material material) {
            return items.getOrDefault(material, 0);
        }

        /** @return {@code true} pokud má aspoň kus */
        public boolean has(Material material) {
            return count(material) > 0;
        }

        /** @return {@code true} pokud má materiál vyhovující predikátu */
        public boolean hasMatching(Predicate<Material> predicate) {
            for (Map.Entry<Material, Integer> entry : items.entrySet()) {
                if (entry.getValue() > 0 && predicate.test(entry.getKey())) {
                    return true;
                }
            }
            return false;
        }

        /** @return součet kusů materiálů vyhovujících predikátu */
        public int countMatching(Predicate<Material> predicate) {
            int total = 0;
            for (Map.Entry<Material, Integer> entry : items.entrySet()) {
                if (predicate.test(entry.getKey())) {
                    total += entry.getValue();
                }
            }
            return total;
        }

        // ------------------------------------------------ odvozené pohledy

        /** @return počet klád */
        public int logs() {
            return countMatching(m -> m.name().endsWith("_LOG") || m.name().endsWith("_STEM"));
        }

        /** @return počet prken */
        public int planks() {
            return countMatching(m -> m.name().endsWith("_PLANKS"));
        }

        /** @return počet tyček */
        public int sticks() {
            return count(Material.STICK);
        }

        /** @return počet cobble (včetně deepslate) */
        public int cobble() {
            return count(Material.COBBLESTONE) + count(Material.COBBLED_DEEPSLATE);
        }

        /** @return počet vlny (libovolná barva) */
        public int wool() {
            return countMatching(m -> m.name().endsWith("_WOOL"));
        }

        /** @return má ponk */
        public boolean hasTable() {
            return has(Material.CRAFTING_TABLE);
        }

        /** @return má nástroj daného typu v tieru {@code tier} nebo lepší */
        public boolean hasToolAtLeast(String suffix, int tier) {
            return hasMatching(m -> m.name().endsWith(suffix)
                    && dev.botalive.core.inventory.InventoryHelper.toolTier(m) >= tier);
        }
    }

    private CraftPlanner() {
    }

    /**
     * Rozhodne další krok survival progrese.
     *
     * @param s souhrn inventáře
     * @return plán, nebo {@code null} když nic nedává smysl
     */
    public static Plan next(State s) {
        Material plank = s.plankType();
        Material stone = s.stoneType();

        // ---- suroviny a základ (zásoba prken kryje i pozdější recepty: štít,
        // truhlu, dveře; 1 kláda = 4 prkna)
        if (s.planks() < 8 && s.logs() >= 1 && s.logType() != null) {
            return new Plan("prkna", matrix(s.logType(), 0), false);
        }
        if (s.sticks() < 4 && s.planks() >= 2 && plank != null) {
            return new Plan("tyčky", matrix(plank, 0, plank, 3), false);
        }
        if (!s.hasTable() && s.planks() >= 4 && plank != null) {
            return new Plan("ponk", matrix(plank, 0, plank, 1, plank, 3, plank, 4), false);
        }

        // ---- dřevěná generace
        boolean pickaxe1 = s.hasToolAtLeast("_PICKAXE", 1);
        if (s.hasTable() && plank != null && s.sticks() >= 2 && s.planks() >= 3 && !pickaxe1) {
            return new Plan("dřevěný krumpáč", matrix(
                    plank, 0, plank, 1, plank, 2, Material.STICK, 4, Material.STICK, 7), true);
        }
        boolean anySword = s.hasMatching(m -> m.name().endsWith("_SWORD"));
        if (s.hasTable() && plank != null && s.sticks() >= 1 && s.planks() >= 2 && !anySword) {
            return new Plan("dřevěný meč", matrix(
                    plank, 1, plank, 4, Material.STICK, 7), true);
        }
        boolean anyAxe = s.hasMatching(m -> m.name().endsWith("_AXE")
                && !m.name().endsWith("_PICKAXE"));
        if (s.hasTable() && plank != null && s.sticks() >= 2 && s.planks() >= 3 && !anyAxe) {
            return new Plan("dřevěná sekera", matrix(
                    plank, 0, plank, 1, plank, 3, Material.STICK, 4, Material.STICK, 7), true);
        }

        // ---- kamenná generace
        if (s.hasTable() && s.cobble() >= 3 && s.sticks() >= 2
                && !s.hasToolAtLeast("_PICKAXE", 3)) {
            return new Plan("kamenný krumpáč", matrix(
                    stone, 0, stone, 1, stone, 2, Material.STICK, 4, Material.STICK, 7), true);
        }
        if (s.hasTable() && s.cobble() >= 2 && s.sticks() >= 1
                && anySword && !s.hasToolAtLeast("_SWORD", 3)) {
            return new Plan("kamenný meč", matrix(
                    stone, 1, stone, 4, Material.STICK, 7), true);
        }
        if (s.hasTable() && s.cobble() >= 3 && s.sticks() >= 2
                && anyAxe && !hasAxeAtLeast(s, 3)) {
            return new Plan("kamenná sekera", matrix(
                    stone, 0, stone, 1, stone, 3, Material.STICK, 4, Material.STICK, 7), true);
        }
        if (s.hasTable() && s.cobble() >= 1 && s.sticks() >= 2
                && !s.hasMatching(m -> m.name().endsWith("_SHOVEL"))) {
            return new Plan("kamenná lopata", matrix(
                    stone, 1, Material.STICK, 4, Material.STICK, 7), true);
        }

        // ---- pec (klíč k železu; rezerva cobble na nástroje)
        if (s.hasTable() && s.cobble() >= 11 && !s.has(Material.FURNACE)) {
            return new Plan("pec", matrix(
                    stone, 0, stone, 1, stone, 2, stone, 3, stone, 5,
                    stone, 6, stone, 7, stone, 8), true);
        }

        // ---- pochodně
        if (s.count(Material.COAL) >= 1 && s.sticks() >= 1
                && s.count(Material.TORCH) < 8) {
            return new Plan("pochodně", matrix(
                    Material.COAL, 1, Material.STICK, 4), false);
        }

        // ---- železná generace
        int iron = s.count(Material.IRON_INGOT);
        if (s.hasTable() && iron >= 3 && s.sticks() >= 2
                && !s.hasToolAtLeast("_PICKAXE", 4)) {
            return new Plan("železný krumpáč", matrix(
                    Material.IRON_INGOT, 0, Material.IRON_INGOT, 1, Material.IRON_INGOT, 2,
                    Material.STICK, 4, Material.STICK, 7), true);
        }
        if (s.hasTable() && iron >= 2 && s.sticks() >= 1
                && s.hasToolAtLeast("_SWORD", 3) && !s.hasToolAtLeast("_SWORD", 4)) {
            return new Plan("železný meč", matrix(
                    Material.IRON_INGOT, 1, Material.IRON_INGOT, 4, Material.STICK, 7), true);
        }
        if (s.hasTable() && iron >= 3 && s.sticks() >= 2
                && hasAxeAtLeast(s, 3) && !hasAxeAtLeast(s, 4)) {
            return new Plan("železná sekera", matrix(
                    Material.IRON_INGOT, 0, Material.IRON_INGOT, 1, Material.IRON_INGOT, 3,
                    Material.STICK, 4, Material.STICK, 7), true);
        }

        // ---- štít (combat ho blokuje z offhandu)
        if (s.hasTable() && iron >= 1 && s.planks() >= 6 && plank != null
                && !s.has(Material.SHIELD)) {
            return new Plan("štít", matrix(
                    plank, 0, Material.IRON_INGOT, 1, plank, 2,
                    plank, 3, plank, 4, plank, 5, plank, 7), true);
        }

        // ---- lepší pece: udírna (jídlo 2× rychleji) a blastová pec (rudy).
        // Spotřebují pec – další si bot vyrobí (krok „pec" se vrátí sám).
        if (s.hasTable() && s.has(Material.FURNACE) && s.logs() >= 4
                && s.logType() != null && !s.has(Material.SMOKER)) {
            return new Plan("udírna", matrix(
                    s.logType(), 1, s.logType(), 3, Material.FURNACE, 4,
                    s.logType(), 5, s.logType(), 7), true);
        }
        if (s.hasTable() && s.has(Material.FURNACE) && iron >= 5
                && s.count(Material.SMOOTH_STONE) >= 3 && !s.has(Material.BLAST_FURNACE)) {
            return new Plan("blastová pec", matrix(
                    Material.IRON_INGOT, 0, Material.IRON_INGOT, 1, Material.IRON_INGOT, 2,
                    Material.IRON_INGOT, 3, Material.FURNACE, 4, Material.IRON_INGOT, 5,
                    Material.SMOOTH_STONE, 6, Material.SMOOTH_STONE, 7,
                    Material.SMOOTH_STONE, 8), true);
        }

        // ---- železné brnění (nejcennější kusy dřív)
        Plan ironArmor = armorPlan(s, Material.IRON_INGOT, iron, "IRON",
                new String[]{"železný prsní plát", "železné kalhoty",
                        "železná helma", "železné boty"});
        if (ironArmor != null) {
            return ironArmor;
        }

        // ---- diamantová generace
        int diamonds = s.count(Material.DIAMOND);
        if (s.hasTable() && diamonds >= 3 && s.sticks() >= 2
                && !s.hasToolAtLeast("_PICKAXE", 5)) {
            return new Plan("diamantový krumpáč", matrix(
                    Material.DIAMOND, 0, Material.DIAMOND, 1, Material.DIAMOND, 2,
                    Material.STICK, 4, Material.STICK, 7), true);
        }
        if (s.hasTable() && diamonds >= 2 && s.sticks() >= 1
                && !s.hasToolAtLeast("_SWORD", 5)) {
            return new Plan("diamantový meč", matrix(
                    Material.DIAMOND, 1, Material.DIAMOND, 4, Material.STICK, 7), true);
        }
        if (s.hasTable() && diamonds >= 3 && s.sticks() >= 2 && !hasAxeAtLeast(s, 5)) {
            return new Plan("diamantová sekera", matrix(
                    Material.DIAMOND, 0, Material.DIAMOND, 1, Material.DIAMOND, 3,
                    Material.STICK, 4, Material.STICK, 7), true);
        }
        Plan diamondArmor = armorPlan(s, Material.DIAMOND, diamonds, "DIAMOND",
                new String[]{"diamantový prsní plát", "diamantové kalhoty",
                        "diamantová helma", "diamantové boty"});
        if (diamondArmor != null) {
            return diamondArmor;
        }

        // ---- křesadlo (klíč do Netheru; PŘED šípy – jinak by opakovaná
        // výroba šípů spolykala každý pazourek a křesadlo by nikdy nevzniklo)
        boolean netherPrep = s.hasToolAtLeast("_PICKAXE", 5);
        if (netherPrep && iron >= 1 && s.has(Material.FLINT)
                && !s.has(Material.FLINT_AND_STEEL)) {
            return new Plan("křesadlo", matrix(
                    Material.IRON_INGOT, 0, Material.FLINT, 1), false);
        }

        // ---- luk a šípy (dálkový boj)
        if (s.hasTable() && s.count(Material.STRING) >= 3 && s.sticks() >= 3
                && !s.has(Material.BOW) && !s.has(Material.CROSSBOW)) {
            return new Plan("luk", matrix(
                    Material.STICK, 1, Material.STRING, 2,
                    Material.STICK, 3, Material.STRING, 5,
                    Material.STICK, 7, Material.STRING, 8), true);
        }
        if (s.hasTable() && (s.has(Material.BOW) || s.has(Material.CROSSBOW))
                && s.has(Material.FLINT) && s.has(Material.FEATHER) && s.sticks() >= 1
                && s.count(Material.ARROW) < 16) {
            return new Plan("šípy", matrix(
                    Material.FLINT, 1, Material.STICK, 4, Material.FEATHER, 7), true);
        }

        // ---- výbava do Netheru (má smysl až s diamantovým krumpáčem –
        // obsidián se jiným nevytěží; křesadlo viz výše před šípy)
        if (netherPrep && s.hasTable() && s.count(Material.GOLD_INGOT) >= 6
                && !s.has(Material.GOLDEN_BOOTS)) {
            // Zlaté boty piglini respektují; 2 ingoty navíc zůstávají na barter.
            return new Plan("zlaté boty", matrix(
                    Material.GOLD_INGOT, 0, Material.GOLD_INGOT, 2,
                    Material.GOLD_INGOT, 6, Material.GOLD_INGOT, 8), true);
        }

        // ---- netherit (kořist z Netheru: trosky → úlomky → ingot; povýšení
        // výbavy dělá kovářský stůl přes SmithingService)
        if (s.hasTable() && s.count(Material.NETHERITE_SCRAP) >= 4
                && s.count(Material.GOLD_INGOT) >= 4 && !s.has(Material.NETHERITE_INGOT)) {
            return new Plan("netheritový ingot", matrix(
                    Material.NETHERITE_SCRAP, 0, Material.NETHERITE_SCRAP, 1,
                    Material.NETHERITE_SCRAP, 2, Material.NETHERITE_SCRAP, 3,
                    Material.GOLD_INGOT, 4, Material.GOLD_INGOT, 5,
                    Material.GOLD_INGOT, 6, Material.GOLD_INGOT, 7), true);
        }
        if (s.hasTable() && !s.has(Material.SMITHING_TABLE) && iron >= 2
                && s.planks() >= 4 && plank != null
                && (s.has(Material.NETHERITE_INGOT) || s.has(Material.NETHERITE_SCRAP)
                        || s.has(Material.ANCIENT_DEBRIS))) {
            return new Plan("kovářský stůl", matrix(
                    Material.IRON_INGOT, 0, Material.IRON_INGOT, 1,
                    plank, 3, plank, 4, plank, 6, plank, 7), true);
        }

        // ---- truhla a loďka (zázemí a cestování; rezerva prken)
        if (s.hasTable() && s.planks() >= 12 && plank != null
                && !s.has(Material.CHEST)) {
            return new Plan("truhla", matrix(
                    plank, 0, plank, 1, plank, 2, plank, 3, plank, 5,
                    plank, 6, plank, 7, plank, 8), true);
        }
        if (s.hasTable() && s.planks() >= 9 && plank != null
                && !s.hasMatching(m -> m.name().endsWith("_BOAT")
                        && !m.name().endsWith("_CHEST_BOAT"))) {
            return new Plan("loďka", matrix(
                    plank, 3, plank, 5, plank, 6, plank, 7, plank, 8), true);
        }

        // ---- kovadlina (opravy nástrojů; drahá – až při přebytku železa)
        if (s.hasTable() && !s.has(Material.ANVIL)
                && s.count(Material.IRON_BLOCK) < 3 && iron >= 13) {
            return new Plan("blok železa", matrix(
                    Material.IRON_INGOT, 0, Material.IRON_INGOT, 1, Material.IRON_INGOT, 2,
                    Material.IRON_INGOT, 3, Material.IRON_INGOT, 4, Material.IRON_INGOT, 5,
                    Material.IRON_INGOT, 6, Material.IRON_INGOT, 7,
                    Material.IRON_INGOT, 8), true);
        }
        if (s.hasTable() && !s.has(Material.ANVIL)
                && s.count(Material.IRON_BLOCK) >= 3 && iron >= 4) {
            return new Plan("kovadlina", matrix(
                    Material.IRON_BLOCK, 0, Material.IRON_BLOCK, 1, Material.IRON_BLOCK, 2,
                    Material.IRON_INGOT, 4, Material.IRON_INGOT, 6,
                    Material.IRON_INGOT, 7, Material.IRON_INGOT, 8), true);
        }

        // ---- composter (hnojivo ze semínek; farmářský okruh)
        if (s.hasTable() && plank != null && !s.has(Material.COMPOSTER)
                && s.countMatching(m -> m.name().endsWith("_SLAB")) < 7 && s.planks() >= 11) {
            return new Plan("dřevěné půlky", matrix(
                    plank, 0, plank, 1, plank, 2), true);
        }
        if (s.hasTable() && !s.has(Material.COMPOSTER)
                && s.countMatching(m -> m.name().endsWith("_SLAB")) >= 7) {
            Material slab = firstSlab(s);
            return new Plan("composter", matrix(
                    slab, 0, slab, 2, slab, 3, slab, 5,
                    slab, 6, slab, 7, slab, 8), true);
        }

        // ---- vybavení domova
        if (s.hasTable() && s.planks() >= 6 && plank != null
                && !s.hasMatching(m -> m.name().endsWith("_DOOR"))) {
            return new Plan("dveře", matrix(
                    plank, 0, plank, 1, plank, 3, plank, 4, plank, 6, plank, 7), true);
        }
        if (s.hasTable() && s.wool() >= 3 && s.woolType() != null
                && s.planks() >= 3 && plank != null
                && !s.hasMatching(m -> m.name().endsWith("_BED"))) {
            return new Plan("postel", matrix(
                    s.woolType(), 0, s.woolType(), 1, s.woolType(), 2,
                    plank, 3, plank, 4, plank, 5), true);
        }
        return null;
    }

    /** První druh dřevěné půlky v inventáři. */
    private static Material firstSlab(State s) {
        for (Material material : s.items().keySet()) {
            if (material.name().endsWith("_SLAB") && s.count(material) >= 7) {
                return material;
            }
        }
        for (Material material : s.items().keySet()) {
            if (material.name().endsWith("_SLAB")) {
                return material;
            }
        }
        return Material.OAK_SLAB;
    }

    /** Má sekeru v tieru {@code tier} nebo lepší (bez krumpáčů). */
    private static boolean hasAxeAtLeast(State s, int tier) {
        return s.hasMatching(m -> m.name().endsWith("_AXE") && !m.name().endsWith("_PICKAXE")
                && dev.botalive.core.inventory.InventoryHelper.toolTier(m) >= tier);
    }

    /** Další chybějící kus brnění dané suroviny (prsník → kalhoty → helma → boty). */
    private static Plan armorPlan(State s, Material ingot, int available,
                                  String tierPrefix, String[] labels) {
        if (!s.hasTable()) {
            return null;
        }
        if (available >= 8 && !hasArmor(s, tierPrefix, "_CHESTPLATE")) {
            return new Plan(labels[0], matrix(
                    ingot, 0, ingot, 2, ingot, 3, ingot, 4, ingot, 5,
                    ingot, 6, ingot, 7, ingot, 8), true);
        }
        if (available >= 7 && !hasArmor(s, tierPrefix, "_LEGGINGS")) {
            return new Plan(labels[1], matrix(
                    ingot, 0, ingot, 1, ingot, 2, ingot, 3, ingot, 5,
                    ingot, 6, ingot, 8), true);
        }
        if (available >= 5 && !hasArmor(s, tierPrefix, "_HELMET")) {
            return new Plan(labels[2], matrix(
                    ingot, 0, ingot, 1, ingot, 2, ingot, 3, ingot, 5), true);
        }
        if (available >= 4 && !hasArmor(s, tierPrefix, "_BOOTS")) {
            return new Plan(labels[3], matrix(
                    ingot, 0, ingot, 2, ingot, 6, ingot, 8), true);
        }
        return null;
    }

    /** Má kus brnění daného typu v tomto tieru nebo lepším. */
    private static boolean hasArmor(State s, String tierPrefix, String suffix) {
        int wanted = tierPrefix.equals("DIAMOND") ? 5 : 4;
        return s.hasMatching(m -> m.name().endsWith(suffix)
                && dev.botalive.core.inventory.InventoryHelper.armorTier(m) >= wanted);
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
