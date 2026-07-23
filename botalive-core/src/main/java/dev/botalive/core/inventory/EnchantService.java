package dev.botalive.core.inventory;

import dev.botalive.core.scheduler.MainThreadBridge;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Enchantování výbavy – řemeslo enchantera (vanilla mechanika stolu, XP a lapisu).
 *
 * <p>Bot musí stát u enchantovacího stolu (klikne na něj – reálný interact
 * paket) a mít XP levely a lapis. Samotné očarování běží autoritativně na
 * vlákně entity: náhodný vhodný enchant podle typu předmětu, spotřeba levelů
 * (bot je získává těžbou a bojem – skutečné vanilla XP) a lapisu. Paketová
 * cesta (enchant UI s náhodnými nabídkami) je záměrně vynechaná – viz
 * ARCHITECTURE.md §9.</p>
 */
public final class EnchantService implements dev.botalive.core.station.EnchantStation {

    private final MainThreadBridge bridge;

    /**
     * @param bridge most na herní vlákna
     */
    public EnchantService(MainThreadBridge bridge) {
        this.bridge = bridge;
    }

    /**
     * @param material materiál
     * @return {@code true} pokud jde o výbavu, kterou umíme očarovat
     */
    public static boolean isEnchantable(Material material) {
        // Politika = katalogová klasifikace + záměrné výjimky: očarováváme meče,
        // krumpáče/sekery/lopaty, luk/kuši a brnění – NE motyku, trojzubec,
        // nůžky, prut… (ty katalog zná, ale bot je neočarovává).
        return Items.isSword(material) || Items.isPickaxe(material) || Items.isAxe(material)
                || Items.isShovel(material) || material == Material.BOW
                || material == Material.CROSSBOW || Items.isArmor(material);
    }

    @Override
    public CompletableFuture<EnchantReport> enchantBest(dev.botalive.core.ai.BotContext ctx) {
        return enchantBest(ctx.bot().id());
    }

    /**
     * Očaruje první neočarovaný kus výbavy v inventáři bota.
     *
     * @param botId UUID bota (u enchantovacího stolu, s levely a lapisem)
     * @return future s výsledkem (EMPTY pokud není co/za co očarovat)
     */
    public CompletableFuture<EnchantReport> enchantBest(UUID botId) {
        Player player = Bukkit.getPlayer(botId);
        if (player == null) {
            return CompletableFuture.completedFuture(EnchantReport.EMPTY);
        }
        return bridge.<EnchantReport>callForEntity(player, () -> doEnchant(player))
                .thenApply(report -> report == null ? EnchantReport.EMPTY : report)
                .exceptionally(t -> EnchantReport.EMPTY);
    }

    /** Vlastní očarování – běží na vlákně entity. */
    private static EnchantReport doEnchant(Player player) {
        var inventory = player.getInventory();

        // Cena: 1–3 levely podle dostupných levelů, stejně lapisu.
        int cost = Math.min(3, Math.max(1, player.getLevel() / 5));
        if (player.getLevel() < cost
                || !inventory.containsAtLeast(new ItemStack(Material.LAPIS_LAZULI), cost)) {
            return EnchantReport.EMPTY;
        }

        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || !isEnchantable(item.getType())
                    || !item.getEnchantments().isEmpty()) {
                continue;
            }
            List<Enchantment> candidates = enchantsFor(item.getType());
            if (candidates.isEmpty()) {
                continue;
            }
            Enchantment enchantment = candidates.get(
                    ThreadLocalRandom.current().nextInt(candidates.size()));
            int level = Math.min(enchantment.getMaxLevel(), cost);
            try {
                item.addEnchantment(enchantment, level);
            } catch (IllegalArgumentException e) {
                continue; // nekompatibilní kombinace – zkusit další předmět
            }
            inventory.setItem(slot, item);
            inventory.removeItem(new ItemStack(Material.LAPIS_LAZULI, cost));
            player.giveExpLevels(-cost);
            return new EnchantReport(
                    item.getType().name().toLowerCase() + " (" + enchantment.getKey().getKey()
                            + " " + level + ")", cost);
        }
        return EnchantReport.EMPTY;
    }

    /** Vhodné enchanty podle typu předmětu. */
    private static List<Enchantment> enchantsFor(Material material) {
        if (Items.isSword(material)) {
            return List.of(Enchantment.SHARPNESS, Enchantment.UNBREAKING, Enchantment.KNOCKBACK);
        }
        if (Items.isPickaxe(material)) {
            return List.of(Enchantment.EFFICIENCY, Enchantment.UNBREAKING, Enchantment.FORTUNE);
        }
        if (Items.isAxe(material) || Items.isShovel(material)) {
            return List.of(Enchantment.EFFICIENCY, Enchantment.UNBREAKING);
        }
        if (material == Material.BOW) {
            return List.of(Enchantment.POWER, Enchantment.UNBREAKING);
        }
        if (material == Material.CROSSBOW) {
            return List.of(Enchantment.QUICK_CHARGE, Enchantment.UNBREAKING);
        }
        if (Items.isArmor(material)) {
            return List.of(Enchantment.PROTECTION, Enchantment.UNBREAKING);
        }
        return List.of();
    }
}
