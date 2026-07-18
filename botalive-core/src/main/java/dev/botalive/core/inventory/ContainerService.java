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
public final class ContainerService implements dev.botalive.core.station.ChestStation {

    /** Materiály považované za „přebytek" k uložení do truhly. */
    private static final Set<Material> JUNK = Set.of(
            Material.COBBLESTONE, Material.COBBLED_DEEPSLATE, Material.DIRT,
            Material.GRAVEL, Material.SAND, Material.NETHERRACK, Material.GRANITE,
            Material.DIORITE, Material.ANDESITE, Material.TUFF, Material.ROTTEN_FLESH,
            Material.STONE, Material.DEEPSLATE
    );

    /** Kolik kusů stavebního materiálu si bot nechává na stavění/crafting. */
    private static final int KEEP_BUILDING_BLOCKS = 32;

    /** Stropy nouzového výběru: bot krade jen to, co teď potřebuje. */
    private static final int STEAL_FOOD_LIMIT = 8;
    private static final int STEAL_BLOCK_LIMIT = 16;

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
     * Cennosti strukturálních truhel (pevnosti, bastiony) – to, co stojí za
     * odnesení z Netheru: kovářské šablony (netherite upgrade!), zlato,
     * diamanty, obsidián a hotové netheritové suroviny.
     *
     * @param material materiál
     * @return {@code true} pokud jde o kořist hodnou vyloupení
     */
    public static boolean isValuableLoot(Material material) {
        if (material.name().endsWith("_SMITHING_TEMPLATE")) {
            return true;
        }
        return switch (material) {
            case GOLD_INGOT, GOLD_BLOCK, GOLD_NUGGET, GOLDEN_APPLE, ENCHANTED_GOLDEN_APPLE,
                 GOLDEN_CARROT, DIAMOND, IRON_INGOT, OBSIDIAN, CRYING_OBSIDIAN,
                 ANCIENT_DEBRIS, NETHERITE_SCRAP, NETHERITE_INGOT, ENCHANTED_BOOK,
                 ARROW, SPECTRAL_ARROW, STRING -> true;
            default -> false;
        };
    }

    @Override
    public CompletableFuture<Integer> depositJunk(dev.botalive.core.ai.BotContext ctx,
                                                  String worldName, BlockPos chestPos) {
        return depositJunk(ctx.bot().id(), worldName, chestPos);
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

    @Override
    public CompletableFuture<Integer> withdrawSupplies(dev.botalive.core.ai.BotContext ctx,
                                                       String worldName, BlockPos chestPos,
                                                       boolean includeGear) {
        UUID botId = ctx.bot().id();
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
            int taken = 0;
            int foodTaken = 0;
            boolean toolTaken = false;
            int blocksTaken = 0;

            for (int slot = 0; slot < chest.getSize(); slot++) {
                ItemStack stack = chest.getItem(slot);
                if (stack == null) {
                    continue;
                }
                Material material = stack.getType();
                boolean want = false;
                int wantCount = 0;
                if (InventoryHelper.isFood(material) && foodTaken < STEAL_FOOD_LIMIT) {
                    want = true;
                    wantCount = Math.min(stack.getAmount(), STEAL_FOOD_LIMIT - foodTaken);
                } else if (includeGear && !toolTaken
                        && (InventoryHelper.isTool(material, InventoryHelper.ToolType.PICKAXE)
                        || InventoryHelper.isTool(material, InventoryHelper.ToolType.AXE))) {
                    want = true;
                    wantCount = 1;
                } else if (includeGear && blocksTaken < STEAL_BLOCK_LIMIT
                        && (InventoryHelper.isBuildingBlock(material)
                        || material.name().endsWith("_LOG"))) {
                    want = true;
                    wantCount = Math.min(stack.getAmount(), STEAL_BLOCK_LIMIT - blocksTaken);
                }
                if (!want || wantCount <= 0) {
                    continue;
                }
                ItemStack take = stack.clone();
                take.setAmount(wantCount);
                var leftover = playerInventory.addItem(take);
                int moved = wantCount - leftover.values().stream()
                        .mapToInt(ItemStack::getAmount).sum();
                if (moved <= 0) {
                    break; // plný inventář bota
                }
                if (moved >= stack.getAmount()) {
                    chest.setItem(slot, null);
                } else {
                    stack.setAmount(stack.getAmount() - moved);
                    chest.setItem(slot, stack);
                }
                taken += moved;
                if (InventoryHelper.isFood(material)) {
                    foodTaken += moved;
                } else if (wantCount == 1 && moved >= 1
                        && !InventoryHelper.isBuildingBlock(material)) {
                    toolTaken = true;
                } else {
                    blocksTaken += moved;
                }
            }
            return taken;
        }).exceptionally(t -> 0);
    }

    @Override
    public CompletableFuture<Integer> lootValuables(dev.botalive.core.ai.BotContext ctx,
                                                    String worldName, BlockPos chestPos) {
        UUID botId = ctx.bot().id();
        World world = Bukkit.getWorld(worldName);
        Player player = Bukkit.getPlayer(botId);
        if (world == null || player == null) {
            return CompletableFuture.completedFuture(0);
        }
        Location location = new Location(world, chestPos.x(), chestPos.y(), chestPos.z());
        return bridge.callAt(location, () -> {
            if (player.getLocation().distanceSquared(location) > 6 * 6) {
                return 0;
            }
            if (!(world.getBlockAt(chestPos.x(), chestPos.y(), chestPos.z())
                    .getState() instanceof Container container)) {
                return 0;
            }
            Inventory chest = container.getInventory();
            var playerInventory = player.getInventory();
            int taken = 0;
            for (int slot = 0; slot < chest.getSize(); slot++) {
                ItemStack stack = chest.getItem(slot);
                if (stack == null || !isValuableLoot(stack.getType())) {
                    continue;
                }
                var leftover = playerInventory.addItem(stack.clone());
                int moved = stack.getAmount() - leftover.values().stream()
                        .mapToInt(ItemStack::getAmount).sum();
                if (moved <= 0) {
                    break; // plný inventář bota
                }
                if (moved >= stack.getAmount()) {
                    chest.setItem(slot, null);
                } else {
                    stack.setAmount(stack.getAmount() - moved);
                    chest.setItem(slot, stack);
                }
                taken += moved;
            }
            return taken;
        }).exceptionally(t -> 0);
    }
}
