package dev.botalive.core.inventory;

import dev.botalive.core.ai.BotContext;
import dev.botalive.core.scheduler.MainThreadBridge;
import dev.botalive.core.station.SmithingStation;
import dev.botalive.core.util.BlockPos;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;

/**
 * Server-side povýšení výbavy na netherit u kovářského stolu.
 *
 * <p>Stejný precedent jako {@link AnvilService}: bot musí fyzicky stát
 * u kovářského stolu, úprava inventáře běží autoritativně na vlákně regionu.
 * Vanilla pravidla: spotřebuje se jedna kovářská šablona (netherite upgrade)
 * a jeden netheritový ingot; enchanty i poškození kusu zůstávají.</p>
 */
public final class SmithingService implements SmithingStation {

    private static final Logger LOG = LoggerFactory.getLogger(SmithingService.class);

    private final MainThreadBridge bridge;

    /**
     * @param bridge most na vlákna regionů
     */
    public SmithingService(MainThreadBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public CompletableFuture<UpgradeReport> upgrade(BotContext ctx, String worldName,
                                                    BlockPos pos) {
        World world = Bukkit.getWorld(worldName);
        Player player = Bukkit.getPlayer(ctx.bot().id());
        if (world == null || player == null) {
            return CompletableFuture.completedFuture(UpgradeReport.NONE);
        }
        Location location = new Location(world, pos.x(), pos.y(), pos.z());
        return bridge.callAt(location, () -> {
            if (player.getLocation().distanceSquared(location) > 6 * 6) {
                return UpgradeReport.NONE;
            }
            if (world.getBlockAt(pos.x(), pos.y(), pos.z()).getType()
                    != Material.SMITHING_TABLE) {
                return UpgradeReport.NONE;
            }
            var inventory = player.getInventory();
            if (!inventory.contains(Material.NETHERITE_INGOT)
                    || !inventory.contains(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE)) {
                return UpgradeReport.NONE;
            }
            // Najít nejcennější diamantový kus k povýšení.
            int slot = -1;
            Material base = null;
            for (String candidate : UPGRADE_ORDER) {
                Material material = Material.valueOf(candidate);
                int found = inventory.first(material);
                if (found >= 0) {
                    slot = found;
                    base = material;
                    break;
                }
            }
            Material result = SmithingStation.netheriteOf(base);
            if (slot < 0 || result == null) {
                return UpgradeReport.NONE;
            }
            ItemStack item = inventory.getItem(slot);
            if (item == null || item.getType() != base) {
                return UpgradeReport.NONE;
            }
            // withType zachová komponenty kusu (enchanty, poškození, jméno).
            inventory.setItem(slot, item.withType(result));
            inventory.removeItem(new ItemStack(Material.NETHERITE_INGOT, 1));
            inventory.removeItem(new ItemStack(Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 1));
            world.playSound(location, org.bukkit.Sound.BLOCK_SMITHING_TABLE_USE, 1.0f, 1.0f);
            LOG.info("[smithing] {} povýšen: {} -> {}", player.getName(), base, result);
            return new UpgradeReport(base, result);
        }).exceptionally(t -> {
            LOG.warn("[smithing] výjimka při povyšování", t);
            return UpgradeReport.NONE;
        });
    }
}
