package dev.botalive.core.crafting;

import dev.botalive.core.scheduler.MainThreadBridge;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemCraftResult;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Server-side simulace craftingu botů.
 *
 * <p><b>Volba implementace:</b> paketová cesta (otevření ponku + container
 * kliky) v protokolu 26.1 vyžaduje „hashed item stacky" a stavový automat nad
 * okny – je extrémně křehká vůči změnám protokolu. Server-side simulace přes
 * {@link org.bukkit.Server#craftItemResult} používá skutečné receptury serveru
 * (včetně custom receptů z jiných pluginů a craft eventů), je atomická a
 * validovaná. Trade-off: ostatní hráči nevidí otevřené okno ponku – to je
 * jediný pozorovatelný rozdíl; lidskost dodává {@code CraftGoal} (bot se
 * zastaví, „přemýšlí", mává rukou).</p>
 *
 * <p>Pravidla realističnosti: recepty s přesahem 2×2 vyžadují, aby bot měl
 * v inventáři ponk ({@code CRAFTING_TABLE}) – stejné omezení jako u hráče,
 * jen bez nutnosti ho pokládat.</p>
 */
public final class CraftingService {

    /** Jeden plán receptu: co vyrobit a z čeho (3×3 matice, row-major). */
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

    private final MainThreadBridge bridge;

    /**
     * @param bridge most na vlákno entity
     */
    public CraftingService(MainThreadBridge bridge) {
        this.bridge = bridge;
    }

    /**
     * Zkusí vyrobit další recept z progrese bota. Vše (kontrola surovin,
     * spotřeba, vložení výsledku) proběhne atomicky na vlákně entity.
     *
     * @param botId UUID bota
     * @return future s id vyrobeného receptu, nebo {@code null} když není co vyrábět
     */
    public CompletableFuture<String> craftNextInProgression(UUID botId) {
        Player player = Bukkit.getPlayer(botId);
        if (player == null) {
            return CompletableFuture.completedFuture(null);
        }
        return bridge.callForEntity(player, () -> {
            Plan plan = nextPlan(player.getInventory());
            if (plan == null) {
                return null;
            }
            return executePlan(player, plan) ? plan.id() : null;
        });
    }

    /**
     * Rozhodne, co by měl bot vyrobit jako další krok survival progrese.
     * Běží na vlákně entity (čte živý inventář).
     *
     * @param inventory inventář bota
     * @return plán, nebo {@code null} když nic nedává smysl
     */
    public static Plan nextPlan(PlayerInventory inventory) {
        int logs = countMatching(inventory, m -> Tag.LOGS.isTagged(m));
        int planks = countMatching(inventory, m -> Tag.PLANKS.isTagged(m));
        int sticks = count(inventory, Material.STICK);
        int cobble = count(inventory, Material.COBBLESTONE)
                + count(inventory, Material.COBBLED_DEEPSLATE);
        boolean hasTable = inventory.contains(Material.CRAFTING_TABLE);

        boolean hasWoodPick = containsTool(inventory, "_PICKAXE");
        boolean hasSword = containsTool(inventory, "_SWORD");
        boolean hasAxe = containsAxe(inventory);
        boolean hasStonePick = inventory.contains(Material.STONE_PICKAXE)
                || betterPickaxe(inventory);

        Material logType = firstMatching(inventory, m -> Tag.LOGS.isTagged(m));
        Material plankType = firstMatching(inventory, m -> Tag.PLANKS.isTagged(m));

        // Progrese od nejnaléhavějšího: suroviny → ponk → nástroje → lepší nástroje.
        if (planks < 4 && logs >= 1 && logType != null) {
            return new Plan("prkna", matrix(logType, 0), false);
        }
        if (sticks < 4 && planks >= 2 && plankType != null) {
            return new Plan("tyčky", matrix(plankType, 0, plankType, 3), false);
        }
        if (!hasTable && planks >= 4 && plankType != null) {
            return new Plan("ponk", matrix(plankType, 0, plankType, 1, plankType, 3, plankType, 4), false);
        }
        if (hasTable && plankType != null && sticks >= 2 && planks >= 3 && !hasWoodPick) {
            return new Plan("dřevěný krumpáč", matrix(
                    plankType, 0, plankType, 1, plankType, 2,
                    Material.STICK, 4, Material.STICK, 7), true);
        }
        if (hasTable && plankType != null && sticks >= 1 && planks >= 2 && !hasSword) {
            return new Plan("dřevěný meč", matrix(
                    plankType, 1, plankType, 4, Material.STICK, 7), true);
        }
        if (hasTable && plankType != null && sticks >= 2 && planks >= 3 && !hasAxe) {
            return new Plan("dřevěná sekera", matrix(
                    plankType, 0, plankType, 1, plankType, 3,
                    Material.STICK, 4, Material.STICK, 7), true);
        }
        Material stone = count(inventory, Material.COBBLESTONE) >= 3
                ? Material.COBBLESTONE : Material.COBBLED_DEEPSLATE;
        if (hasTable && cobble >= 3 && sticks >= 2 && !hasStonePick) {
            return new Plan("kamenný krumpáč", matrix(
                    stone, 0, stone, 1, stone, 2,
                    Material.STICK, 4, Material.STICK, 7), true);
        }
        if (hasTable && cobble >= 2 && sticks >= 1
                && !inventory.contains(Material.STONE_SWORD) && hasSword) {
            // upgrade meče na kamenný (dřevěný už má)
            return new Plan("kamenný meč", matrix(
                    stone, 1, stone, 4, Material.STICK, 7), true);
        }
        return null;
    }

    /** Provede plán: ověří suroviny (+ ponk), spotřebuje a vloží výsledek. */
    private static boolean executePlan(Player player, Plan plan) {
        PlayerInventory inventory = player.getInventory();
        if (plan.needsTable() && !inventory.contains(Material.CRAFTING_TABLE)) {
            return false;
        }
        for (Map.Entry<Material, Integer> entry : plan.ingredients().entrySet()) {
            if (count(inventory, entry.getKey()) < entry.getValue()) {
                return false;
            }
        }

        ItemStack[] matrix = new ItemStack[9];
        for (int i = 0; i < 9; i++) {
            if (plan.matrix()[i] != null) {
                matrix[i] = new ItemStack(plan.matrix()[i], 1);
            }
        }
        ItemCraftResult result = Bukkit.craftItemResult(matrix, player.getWorld(), player);
        ItemStack crafted = result.getResult();
        if (crafted == null || crafted.getType().isAir()) {
            return false; // recept na tomto serveru neexistuje/změněn
        }

        // Atomická spotřeba + vložení výsledku (jsme na vlákně entity).
        for (Map.Entry<Material, Integer> entry : plan.ingredients().entrySet()) {
            inventory.removeItem(new ItemStack(entry.getKey(), entry.getValue()));
        }
        var overflow = inventory.addItem(crafted);
        result.getOverflowItems().forEach(item -> inventory.addItem(item)
                .values().forEach(rest -> player.getWorld()
                        .dropItemNaturally(player.getLocation(), rest)));
        overflow.values().forEach(rest ->
                player.getWorld().dropItemNaturally(player.getLocation(), rest));
        return true;
    }

    // ------------------------------------------------------------- pomocníci

    /** Sestaví 3×3 matici z dvojic (materiál, index). */
    private static Material[] matrix(Object... pairs) {
        Material[] matrix = new Material[9];
        for (int i = 0; i < pairs.length; i += 2) {
            matrix[(Integer) pairs[i + 1]] = (Material) pairs[i];
        }
        return matrix;
    }

    private static int count(PlayerInventory inventory, Material material) {
        int total = 0;
        for (ItemStack item : inventory.getStorageContents()) {
            if (item != null && item.getType() == material) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private static int countMatching(PlayerInventory inventory,
                                     java.util.function.Predicate<Material> predicate) {
        int total = 0;
        for (ItemStack item : inventory.getStorageContents()) {
            if (item != null && predicate.test(item.getType())) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private static Material firstMatching(PlayerInventory inventory,
                                          java.util.function.Predicate<Material> predicate) {
        for (ItemStack item : inventory.getStorageContents()) {
            if (item != null && predicate.test(item.getType())) {
                return item.getType();
            }
        }
        return null;
    }

    private static boolean containsTool(PlayerInventory inventory, String suffix) {
        for (ItemStack item : inventory.getStorageContents()) {
            if (item != null && item.getType().name().endsWith(suffix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsAxe(PlayerInventory inventory) {
        for (ItemStack item : inventory.getStorageContents()) {
            if (item != null && item.getType().name().endsWith("_AXE")
                    && !item.getType().name().endsWith("_PICKAXE")) {
                return true;
            }
        }
        return false;
    }

    private static boolean betterPickaxe(PlayerInventory inventory) {
        return inventory.contains(Material.IRON_PICKAXE)
                || inventory.contains(Material.DIAMOND_PICKAXE)
                || inventory.contains(Material.NETHERITE_PICKAXE);
    }
}
