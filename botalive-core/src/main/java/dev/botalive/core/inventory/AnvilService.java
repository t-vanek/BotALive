package dev.botalive.core.inventory;

import dev.botalive.core.scheduler.MainThreadBridge;
import dev.botalive.core.util.BlockPos;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.Damageable;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Opravy nástrojů u kovadliny (vanilla mechanika: materiál + XP).
 *
 * <p>Server-side simulace po vzoru {@link dev.botalive.core.crafting.CraftingService}:
 * bot musí stát u kovadliny; oprava vezme opotřebený kus, spotřebuje odpovídající
 * surovinu (železo/diamant/prkna dle materiálu kusu; každý kus opraví 25 %
 * maximální výdrže) a strhne XP úrovně jako vanilla. Paketový režim opravy
 * zatím neumí (anvil okno má vlastní toky) – vrací 0.</p>
 */
public final class AnvilService {

    /** Kolik % maximální výdrže opraví jeden kus suroviny. */
    private static final double REPAIR_PER_UNIT = 0.25;
    /** XP úrovně za opravu (vanilla si účtuje 1–4; držíme střed). */
    private static final int XP_COST_LEVELS = 2;
    /** XP úrovně za aplikaci enchantované knihy. */
    private static final int BOOK_XP_COST_LEVELS = 4;

    private final MainThreadBridge bridge;

    /**
     * @param bridge most na vlákna regionů
     */
    public AnvilService(MainThreadBridge bridge) {
        this.bridge = bridge;
    }

    /**
     * Výsledek opravy.
     *
     * @param repaired  opravený materiál (null = nic)
     * @param unitsUsed spotřebované kusy suroviny
     */
    public record RepairReport(Material repaired, int unitsUsed) {
        /** Nic se neopravilo. */
        public static final RepairReport NONE = new RepairReport(null, 0);
    }

    /**
     * Opraví nejopotřebenější opravitelný kus v inventáři bota.
     *
     * @param botId     UUID bota (musí stát u kovadliny)
     * @param worldName svět kovadliny
     * @param anvilPos  pozice kovadliny
     * @return future s výsledkem opravy
     */
    public CompletableFuture<RepairReport> repair(UUID botId, String worldName, BlockPos anvilPos) {
        World world = Bukkit.getWorld(worldName);
        Player player = Bukkit.getPlayer(botId);
        if (world == null || player == null) {
            return CompletableFuture.completedFuture(RepairReport.NONE);
        }
        var log = org.slf4j.LoggerFactory.getLogger(AnvilService.class);
        Location location = new Location(world, anvilPos.x(), anvilPos.y(), anvilPos.z());
        return bridge.callAt(location, () -> {
            if (player.getLocation().distanceSquared(location) > 6 * 6) {
                log.debug("[anvil] NONE: daleko ({})", player.getLocation().distanceSquared(location));
                return RepairReport.NONE;
            }
            if (!world.getBlockAt(anvilPos.x(), anvilPos.y(), anvilPos.z())
                    .getType().name().endsWith("ANVIL")) {
                log.debug("[anvil] NONE: na {} je {}", anvilPos,
                        world.getBlockAt(anvilPos.x(), anvilPos.y(), anvilPos.z()).getType());
                return RepairReport.NONE;
            }
            if (player.getLevel() < XP_COST_LEVELS) {
                log.debug("[anvil] NONE: málo XP ({})", player.getLevel());
                return RepairReport.NONE;
            }
            var inventory = player.getInventory();
            // Najít nejopotřebenější kus s dostupnou surovinou.
            int bestSlot = -1;
            int bestDamage = 0;
            Material bestRepairMat = null;
            for (int slot = 0; slot < 36; slot++) {
                ItemStack item = inventory.getItem(slot);
                if (item == null || item.getType().getMaxDurability() <= 0
                        || !(item.getItemMeta() instanceof Damageable d) || d.getDamage() <= 0) {
                    continue;
                }
                Material repairMat = repairMaterial(item.getType());
                if (repairMat == null || !inventory.contains(repairMat)) {
                    continue;
                }
                if (d.getDamage() > bestDamage) {
                    bestDamage = d.getDamage();
                    bestSlot = slot;
                    bestRepairMat = repairMat;
                }
            }
            if (bestSlot < 0) {
                log.debug("[anvil] NONE: žádný opravitelný kus se surovinou");
                return RepairReport.NONE;
            }
            ItemStack item = inventory.getItem(bestSlot);
            Damageable meta = (Damageable) item.getItemMeta();
            int max = item.getType().getMaxDurability();
            int perUnit = (int) Math.max(1, max * REPAIR_PER_UNIT);
            int unitsNeeded = Math.min(4, (meta.getDamage() + perUnit - 1) / perUnit);
            int unitsAvailable = 0;
            for (ItemStack stack : inventory.getStorageContents()) {
                if (stack != null && stack.getType() == bestRepairMat) {
                    unitsAvailable += stack.getAmount();
                }
            }
            int units = Math.min(unitsNeeded, unitsAvailable);
            if (units <= 0) {
                log.debug("[anvil] NONE: bez suroviny ({})", bestRepairMat);
                return RepairReport.NONE;
            }
            meta.setDamage(Math.max(0, meta.getDamage() - units * perUnit));
            item.setItemMeta(meta);
            inventory.setItem(bestSlot, item);
            inventory.removeItem(new ItemStack(bestRepairMat, units));
            player.setLevel(player.getLevel() - XP_COST_LEVELS);
            world.playSound(location, org.bukkit.Sound.BLOCK_ANVIL_USE, 1.0f, 1.0f);
            log.info("[anvil] opraveno {} ({} kusů {})", item.getType(), units, bestRepairMat);
            return new RepairReport(item.getType(), units);
        }).exceptionally(t -> {
            org.slf4j.LoggerFactory.getLogger(AnvilService.class)
                    .warn("[anvil] výjimka při opravě", t);
            return RepairReport.NONE;
        });
    }

    /**
     * Aplikuje první použitelnou enchantovanou knihu na nejlepší kompatibilní
     * kus výbavy (vanilla mechanika: kniha + kus + XP u kovadliny). Kniha se
     * spotřebuje; kompatibilitu a konflikty hlídá Bukkit API. Zjednodušení
     * proti vanille: pevná cena XP místo rostoucí prior-work penalizace
     * (stejný precedent jako {@link #repair}).
     *
     * @param botId     UUID bota (musí stát u kovadliny)
     * @param worldName svět kovadliny
     * @param anvilPos  pozice kovadliny
     * @return future s výsledkem ({@code repaired} = očarovaný kus)
     */
    public CompletableFuture<RepairReport> applyBook(UUID botId, String worldName,
                                                     BlockPos anvilPos) {
        World world = Bukkit.getWorld(worldName);
        Player player = Bukkit.getPlayer(botId);
        if (world == null || player == null) {
            return CompletableFuture.completedFuture(RepairReport.NONE);
        }
        var log = org.slf4j.LoggerFactory.getLogger(AnvilService.class);
        Location location = new Location(world, anvilPos.x(), anvilPos.y(), anvilPos.z());
        return bridge.callAt(location, () -> {
            if (player.getLocation().distanceSquared(location) > 6 * 6
                    || !world.getBlockAt(anvilPos.x(), anvilPos.y(), anvilPos.z())
                            .getType().name().endsWith("ANVIL")
                    || player.getLevel() < BOOK_XP_COST_LEVELS) {
                return RepairReport.NONE;
            }
            var inventory = player.getInventory();
            for (int bookSlot = 0; bookSlot < 36; bookSlot++) {
                ItemStack book = inventory.getItem(bookSlot);
                if (book == null || book.getType() != Material.ENCHANTED_BOOK
                        || !(book.getItemMeta()
                                instanceof org.bukkit.inventory.meta.EnchantmentStorageMeta meta)) {
                    continue;
                }
                for (var entry : meta.getStoredEnchants().entrySet()) {
                    int target = findBookTarget(inventory, entry.getKey(), entry.getValue());
                    if (target < 0) {
                        continue;
                    }
                    ItemStack item = inventory.getItem(target);
                    item.addUnsafeEnchantment(entry.getKey(), entry.getValue());
                    inventory.setItem(target, item);
                    book.setAmount(book.getAmount() - 1);
                    inventory.setItem(bookSlot, book.getAmount() > 0 ? book : null);
                    player.setLevel(player.getLevel() - BOOK_XP_COST_LEVELS);
                    world.playSound(location, org.bukkit.Sound.BLOCK_ANVIL_USE, 1.0f, 1.0f);
                    log.info("[anvil] {} očarován knihou: {} {}", item.getType(),
                            entry.getKey().getKey().getKey(), entry.getValue());
                    return new RepairReport(item.getType(), 0);
                }
            }
            return RepairReport.NONE;
        }).exceptionally(t -> {
            org.slf4j.LoggerFactory.getLogger(AnvilService.class)
                    .warn("[anvil] výjimka při očarování knihou", t);
            return RepairReport.NONE;
        });
    }

    /** Nejlepší kus pro daný enchant: kompatibilní, bez konfliktu, nižší úroveň. */
    private static int findBookTarget(org.bukkit.inventory.PlayerInventory inventory,
                                      org.bukkit.enchantments.Enchantment enchant, int level) {
        int best = -1;
        int bestTier = -1;
        for (int slot = 0; slot < 36; slot++) {
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType() == Material.ENCHANTED_BOOK
                    || item.getType() == Material.BOOK
                    || !enchant.canEnchantItem(item)
                    || item.getEnchantmentLevel(enchant) >= level) {
                continue;
            }
            boolean conflict = false;
            for (var existing : item.getEnchantments().keySet()) {
                if (!existing.equals(enchant) && existing.conflictsWith(enchant)) {
                    conflict = true;
                    break;
                }
            }
            if (conflict) {
                continue;
            }
            int tier = Math.max(InventoryHelper.toolTier(item.getType()),
                    InventoryHelper.armorTier(item.getType()));
            if (tier > bestTier) {
                bestTier = tier;
                best = slot;
            }
        }
        return best;
    }

    /**
     * Surovina pro opravu daného kusu (vanilla anvil pravidla).
     *
     * @param tool materiál nástroje/zbroje
     * @return surovina, nebo {@code null} pro neopravitelné
     */
    public static Material repairMaterial(Material tool) {
        String name = tool.name();
        if (name.startsWith("IRON_") || tool == Material.SHIELD
                || name.startsWith("CHAINMAIL_")) {
            return Material.IRON_INGOT;
        }
        if (name.startsWith("DIAMOND_")) {
            return Material.DIAMOND;
        }
        if (name.startsWith("STONE_")) {
            return Material.COBBLESTONE;
        }
        if (name.startsWith("WOODEN_")) {
            return Material.OAK_PLANKS;
        }
        if (name.startsWith("GOLDEN_")) {
            return Material.GOLD_INGOT;
        }
        if (name.startsWith("LEATHER_")) {
            return Material.LEATHER;
        }
        return null;
    }
}
