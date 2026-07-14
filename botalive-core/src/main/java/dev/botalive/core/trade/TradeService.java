package dev.botalive.core.trade;

import dev.botalive.core.scheduler.MainThreadBridge;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.AbstractVillager;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.MerchantRecipe;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Obchodování s vesničany.
 *
 * <p><b>Volba implementace:</b> villager UI v protokolu vyžaduje container
 * kliky s hashed item stacky (křehké) – proto bot obchod otevře skutečným
 * interact paketem (vesničan se otočí, UI se serverově otevře, okolí to vidí)
 * a samotná výměna proběhne autoritativně přes Bukkit
 * {@link org.bukkit.inventory.Merchant} API: skutečné receptury vesničana
 * včetně cen, limitů použití ({@code maxUses}) a doplňování zásob. Stejný
 * princip jako u craftingu a truhel (viz ARCHITECTURE.md §9).</p>
 *
 * <p>Strategie: bot přednostně <i>prodává</i> (recepty s výsledkem smaragd –
 * plodiny, uhlí...), a pokud má hlad a smaragdy, <i>nakupuje</i> jídlo.</p>
 */
public final class TradeService {

    /**
     * Výsledek obchodování.
     *
     * @param trades         počet provedených obchodů
     * @param emeraldsGained získané smaragdy (prodej)
     * @param foodBought     nakoupené jídlo (kusy)
     */
    public record TradeReport(int trades, int emeraldsGained, int foodBought) {

        /** Prázdný výsledek. */
        public static final TradeReport EMPTY = new TradeReport(0, 0, 0);
    }

    private final MainThreadBridge bridge;

    /**
     * @param bridge most na herní vlákna
     */
    public TradeService(MainThreadBridge bridge) {
        this.bridge = bridge;
    }

    /**
     * Provede až {@code maxTrades} obchodů s vesničanem. Bot musí stát vedle
     * něj (≤ 5 bloků) – hráč i vesničan pak sdílejí region i na Folii.
     *
     * @param botId       UUID bota
     * @param villagerId  UUID vesničana
     * @param maxTrades   strop počtu obchodů v jedné návštěvě
     * @return future s výsledkem (EMPTY při nedostupnosti)
     */
    public CompletableFuture<TradeReport> trade(UUID botId, UUID villagerId, int maxTrades) {
        Player player = Bukkit.getPlayer(botId);
        if (player == null) {
            return CompletableFuture.completedFuture(TradeReport.EMPTY);
        }
        return bridge.<TradeReport>callForEntity(player, () -> {
            if (!(Bukkit.getEntity(villagerId) instanceof AbstractVillager villager)) {
                return TradeReport.EMPTY;
            }
            if (villager.getWorld() != player.getWorld()
                    || villager.getLocation().distanceSquared(player.getLocation()) > 5 * 5) {
                return TradeReport.EMPTY;
            }
            return executeTrades(player, villager, maxTrades);
        }).thenApply(report -> report == null ? TradeReport.EMPTY : report)
          .exceptionally(t -> TradeReport.EMPTY);
    }

    /** Vlastní výměna – běží na vlákně entity hráče (vedle vesničana). */
    private static TradeReport executeTrades(Player player, AbstractVillager villager, int maxTrades) {
        int trades = 0;
        int emeralds = 0;
        int food = 0;
        boolean hungry = player.getFoodLevel() < 14;

        List<MerchantRecipe> recipes = villager.getRecipes();
        for (int i = 0; i < recipes.size() && trades < maxTrades; i++) {
            MerchantRecipe recipe = recipes.get(i);
            boolean selling = recipe.getResult().getType() == Material.EMERALD;
            boolean buyingFood = hungry && recipe.getResult().getType().isEdible();
            if (!selling && !buyingFood) {
                continue;
            }
            while (trades < maxTrades
                    && recipe.getUses() < recipe.getMaxUses()
                    && hasIngredients(player, recipe)) {
                for (ItemStack ingredient : recipe.getIngredients()) {
                    if (ingredient != null && !ingredient.getType().isAir()) {
                        player.getInventory().removeItem(ingredient.clone());
                    }
                }
                var overflow = player.getInventory().addItem(recipe.getResult().clone());
                overflow.values().forEach(rest ->
                        player.getWorld().dropItemNaturally(player.getLocation(), rest));

                recipe.setUses(recipe.getUses() + 1);
                villager.setRecipe(i, recipe);
                trades++;
                if (selling) {
                    emeralds += recipe.getResult().getAmount();
                } else {
                    food += recipe.getResult().getAmount();
                }
            }
        }
        return new TradeReport(trades, emeralds, food);
    }

    /** Má bot všechny suroviny receptu? */
    private static boolean hasIngredients(Player player, MerchantRecipe recipe) {
        for (ItemStack ingredient : recipe.getIngredients()) {
            if (ingredient == null || ingredient.getType().isAir()) {
                continue;
            }
            if (!player.getInventory().containsAtLeast(ingredient, ingredient.getAmount())) {
                return false;
            }
        }
        return true;
    }

    /**
     * @param material materiál
     * @return {@code true} pokud jde o typickou prodejní komoditu vesničanům
     */
    public static boolean isSellable(Material material) {
        return switch (material) {
            case WHEAT, CARROT, POTATO, BEETROOT, PUMPKIN, MELON_SLICE,
                 COAL, IRON_INGOT, GOLD_INGOT, ROTTEN_FLESH, STRING,
                 PAPER, STICK -> true;
            default -> false;
        };
    }
}
