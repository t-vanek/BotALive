package dev.botalive.core.crafting;

import dev.botalive.core.scheduler.MainThreadBridge;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.Tag;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemCraftResult;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

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
public final class CraftingService implements dev.botalive.core.station.CraftingStation {

    private final MainThreadBridge bridge;

    /**
     * @param bridge most na vlákno entity
     */
    public CraftingService(MainThreadBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public CompletableFuture<String> craftNext(dev.botalive.core.ai.BotContext ctx) {
        return craftNextInProgression(ctx.bot().id());
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
            CraftPlanner.Plan plan = nextPlan(player.getInventory());
            if (plan == null) {
                return null;
            }
            return executePlan(player, plan) ? plan.id() : null;
        });
    }

    /**
     * Rozhodne, co by měl bot vyrobit jako další krok survival progrese –
     * sestaví souhrn z živého inventáře a deleguje na sdílený
     * {@link CraftPlanner}. Běží na vlákně entity.
     *
     * @param inventory inventář bota
     * @return plán, nebo {@code null} když nic nedává smysl
     */
    public static CraftPlanner.Plan nextPlan(PlayerInventory inventory) {
        int cobbleStd = count(inventory, Material.COBBLESTONE);
        return CraftPlanner.next(new CraftPlanner.State(
                countMatching(inventory, m -> Tag.LOGS.isTagged(m)),
                countMatching(inventory, m -> Tag.PLANKS.isTagged(m)),
                count(inventory, Material.STICK),
                cobbleStd + count(inventory, Material.COBBLED_DEEPSLATE),
                inventory.contains(Material.CRAFTING_TABLE),
                containsTool(inventory, "_PICKAXE"),
                inventory.contains(Material.STONE_PICKAXE) || betterPickaxe(inventory),
                containsTool(inventory, "_SWORD"),
                inventory.contains(Material.STONE_SWORD),
                containsAxe(inventory),
                firstMatching(inventory, m -> Tag.LOGS.isTagged(m)),
                firstMatching(inventory, m -> Tag.PLANKS.isTagged(m)),
                cobbleStd >= 3 ? Material.COBBLESTONE : Material.COBBLED_DEEPSLATE));
    }

    /** Provede plán: ověří suroviny (+ ponk), spotřebuje a vloží výsledek. */
    private static boolean executePlan(Player player, CraftPlanner.Plan plan) {
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
