package dev.botalive.core.inventory;

import dev.botalive.core.scheduler.MainThreadBridge;
import dev.botalive.core.util.BlockPos;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Container;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Server-side práce s kontejnery (truhly, barely).
 *
 * <p><b>Volba implementace:</b> container kliky v protokolu 26.1 vyžadují
 * „hashed item stacky" – autoritativní přesun na serveru je robustní a
 * z pohledu ostatních hráčů nerozeznatelný (bot k truhle dojde, klikne na ni –
 * animace otevření proběhne – a obsah se přesune). Přesun běží na vlákně
 * regionu truhly; bot u ní musí stát (&lt; 4 bloky), takže hráč i truhla
 * sdílejí region i na Folii.</p>
 */
public final class ContainerService {

    /** Materiály považované za „přebytek" k uložení do truhly. */
    private static final Set<Material> JUNK = Set.of(
            Material.COBBLESTONE, Material.COBBLED_DEEPSLATE, Material.DIRT,
            Material.GRAVEL, Material.SAND, Material.NETHERRACK, Material.GRANITE,
            Material.DIORITE, Material.ANDESITE, Material.TUFF, Material.ROTTEN_FLESH,
            Material.STONE, Material.DEEPSLATE
    );

    /** Kolik kusů stavebního materiálu si bot nechává na stavění/crafting. */
    private static final int KEEP_BUILDING_BLOCKS = 32;

    private final MainThreadBridge bridge;

    /**
     * @param bridge most na region vlákna
     */
    public ContainerService(MainThreadBridge bridge) {
        this.bridge = bridge;
    }

    /**
     * @param material materiál
     * @return {@code true} pokud jde o přebytek vhodný do truhly
     */
    public static boolean isJunk(Material material) {
        return JUNK.contains(material);
    }

    /**
     * Přesune přebytky z inventáře bota do kontejneru.
     *
     * @param botId     UUID bota (musí stát u kontejneru)
     * @param worldName svět kontejneru
     * @param chestPos  pozice kontejneru
     * @return future s počtem přesunutých kusů (0 = nic/chyba)
     */
    public CompletableFuture<Integer> depositJunk(UUID botId, String worldName, BlockPos chestPos) {
        World world = Bukkit.getWorld(worldName);
        Player player = Bukkit.getPlayer(botId);
        if (world == null || player == null) {
            return CompletableFuture.completedFuture(0);
        }
        Location location = new Location(world, chestPos.x(), chestPos.y(), chestPos.z());
        return bridge.callAt(location, () -> {
            if (player.getLocation().distanceSquared(location) > 6 * 6) {
                return 0; // bot mezitím odešel – nesahat na cizí region
            }
            if (!(world.getBlockAt(chestPos.x(), chestPos.y(), chestPos.z())
                    .getState() instanceof Container container)) {
                return 0;
            }
            Inventory chest = container.getInventory();
            var playerInventory = player.getInventory();
            int moved = 0;
            int keptBuilding = 0;

            for (int slot = 0; slot < 36; slot++) {
                ItemStack stack = playerInventory.getItem(slot);
                if (stack == null || !isJunk(stack.getType())) {
                    continue;
                }
                // Stavební bloky si částečně nechat.
                if (InventoryHelper.isBuildingBlock(stack.getType())
                        && keptBuilding < KEEP_BUILDING_BLOCKS) {
                    keptBuilding += stack.getAmount();
                    continue;
                }
                int amount = stack.getAmount();
                var leftover = chest.addItem(stack.clone());
                if (leftover.isEmpty()) {
                    playerInventory.setItem(slot, null);
                    moved += amount;
                } else {
                    ItemStack rest = leftover.values().iterator().next();
                    playerInventory.setItem(slot, rest);
                    moved += amount - rest.getAmount();
                    break; // truhla je plná
                }
            }
            return moved;
        }).exceptionally(t -> 0);
    }
}
