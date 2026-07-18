package dev.botalive.core.inventory;

import dev.botalive.core.scheduler.MainThreadBridge;
import dev.botalive.core.util.BlockPos;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Furnace;
import org.bukkit.entity.Player;
import org.bukkit.inventory.FurnaceInventory;
import org.bukkit.inventory.ItemStack;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Práce s pecemi (vanilla tavení) – řemeslo kováře.
 *
 * <p>Stejný vzor jako {@link ContainerService}: bot k peci dojde a klikne na
 * ni (reálný interact paket, okolí vidí otevření), přesun surovin/paliva
 * a výběr výsledku běží autoritativně na vlákně regionu pece. Tavení pak
 * probíhá skutečnou vanilla mechanikou – pec hoří, okolí to vidí, výsledky
 * jsou po ~10 s/kus připravené k vyzvednutí.</p>
 */
public final class FurnaceService implements dev.botalive.core.station.FurnaceStation {

    /** Suroviny, které má smysl tavit/péct (rudy + syrové jídlo + trosky). */
    private static final Set<Material> SMELTABLE = Set.of(
            Material.RAW_IRON, Material.RAW_GOLD, Material.RAW_COPPER,
            Material.IRON_ORE, Material.GOLD_ORE, Material.COPPER_ORE,
            Material.ANCIENT_DEBRIS,
            Material.BEEF, Material.PORKCHOP, Material.CHICKEN,
            Material.MUTTON, Material.RABBIT, Material.COD, Material.SALMON,
            Material.POTATO, Material.KELP
    );

    /** Paliva, která bot do pece ochotně obětuje. */
    // Blaze rod tu záměrně chybí: je to surovina (blaze powder → oči
    // Enderu, lektvary), spálit ji pod železem je zločin proti progresi.
    private static final Set<Material> FUEL = Set.of(
            Material.COAL, Material.CHARCOAL, Material.COAL_BLOCK,
            Material.OAK_PLANKS, Material.SPRUCE_PLANKS, Material.BIRCH_PLANKS,
            Material.JUNGLE_PLANKS, Material.ACACIA_PLANKS, Material.DARK_OAK_PLANKS,
            Material.STICK
    );

    private final MainThreadBridge bridge;

    /**
     * @param bridge most na region vlákna
     */
    public FurnaceService(MainThreadBridge bridge) {
        this.bridge = bridge;
    }

    /**
     * @param material materiál
     * @return {@code true} pokud jde o tavitelnou surovinu
     */
    public static boolean isSmeltable(Material material) {
        return SMELTABLE.contains(material);
    }

    /**
     * @param material materiál
     * @return {@code true} pokud jde o použitelné palivo
     */
    public static boolean isFuel(Material material) {
        return FUEL.contains(material);
    }

    @Override
    public CompletableFuture<InsertReport> insert(dev.botalive.core.ai.BotContext ctx,
                                                  String worldName, BlockPos pos) {
        return insert(ctx.bot().id(), worldName, pos);
    }

    @Override
    public CompletableFuture<Integer> collect(dev.botalive.core.ai.BotContext ctx,
                                              String worldName, BlockPos pos) {
        return collect(ctx.bot().id(), worldName, pos);
    }

    /**
     * Vloží suroviny a palivo z inventáře bota do pece.
     *
     * @param botId     UUID bota (musí stát u pece)
     * @param worldName svět pece
     * @param pos       pozice pece
     * @return future s počty vložených kusů
     */
    public CompletableFuture<InsertReport> insert(UUID botId, String worldName, BlockPos pos) {
        return withFurnace(botId, worldName, pos, (player, furnace) -> {
            FurnaceInventory inventory = furnace.getInventory();
            // Tavicí řetězy nad rámec základu: kámen na blastovou pec a dřevěné
            // uhlí při nouzi o palivo – jen když je bot skutečně potřebuje,
            // aby pec nespalovala zásoby bezúčelně.
            boolean stoneChain = needsSmoothStone(player);
            boolean charcoalChain = needsCharcoal(player);
            int inserted = moveMatching(player, inventory.getSmelting(),
                    m -> isSmeltable(m)
                            || (stoneChain && (m == Material.COBBLESTONE || m == Material.STONE))
                            || (charcoalChain && m.name().endsWith("_LOG")),
                    inventory::setSmelting);
            // Palivo podle priority: uhlí → prkna/tyčky → klády až jako nouzovka.
            int fueled = 0;
            for (java.util.function.Predicate<Material> tier : FUEL_PRIORITY) {
                fueled = moveMatching(player, inventory.getFuel(), tier, inventory::setFuel);
                if (fueled > 0) {
                    break;
                }
            }
            return new InsertReport(inserted, fueled);
        }, InsertReport.EMPTY);
    }

    /** Pořadí obětování paliva – cenné suroviny až jako poslední. */
    private static final java.util.List<java.util.function.Predicate<Material>> FUEL_PRIORITY =
            java.util.List.of(
                    m -> m == Material.COAL || m == Material.CHARCOAL
                            || m == Material.COAL_BLOCK,
                    // Blaze rody z Netheru: bez brewingu jsou to jen výborná
                    // paliva (12 vsázek/kus) – pálí se před prkny.
                    m -> m.name().endsWith("_PLANKS") || m == Material.STICK,
                    m -> m.name().endsWith("_LOG"));

    /** Chybí smooth stone na blastovou pec (a bot na ni jinak má)? */
    private static boolean needsSmoothStone(org.bukkit.entity.Player player) {
        var inv = player.getInventory();
        return !inv.contains(Material.BLAST_FURNACE)
                && inv.contains(Material.FURNACE)
                && count(inv, Material.IRON_INGOT) >= 5
                && count(inv, Material.SMOOTH_STONE) < 3;
    }

    /** Došlo uhlí a je z čeho pálit dřevěné uhlí? */
    private static boolean needsCharcoal(org.bukkit.entity.Player player) {
        var inv = player.getInventory();
        return !inv.contains(Material.COAL) && !inv.contains(Material.CHARCOAL)
                && countMatching(inv, m -> m.name().endsWith("_LOG")) >= 4;
    }

    private static int count(org.bukkit.inventory.PlayerInventory inv, Material material) {
        int total = 0;
        for (ItemStack item : inv.getStorageContents()) {
            if (item != null && item.getType() == material) {
                total += item.getAmount();
            }
        }
        return total;
    }

    private static int countMatching(org.bukkit.inventory.PlayerInventory inv,
                                     java.util.function.Predicate<Material> predicate) {
        int total = 0;
        for (ItemStack item : inv.getStorageContents()) {
            if (item != null && predicate.test(item.getType())) {
                total += item.getAmount();
            }
        }
        return total;
    }

    /**
     * Vyzvedne hotové výsledky tavení.
     *
     * @param botId     UUID bota (musí stát u pece)
     * @param worldName svět pece
     * @param pos       pozice pece
     * @return future s počtem vyzvednutých kusů
     */
    public CompletableFuture<Integer> collect(UUID botId, String worldName, BlockPos pos) {
        return withFurnace(botId, worldName, pos, (player, furnace) -> {
            FurnaceInventory inventory = furnace.getInventory();
            ItemStack result = inventory.getResult();
            if (result == null || result.getType().isAir()) {
                return 0;
            }
            int amount = result.getAmount();
            var overflow = player.getInventory().addItem(result.clone());
            int taken = amount;
            if (!overflow.isEmpty()) {
                ItemStack rest = overflow.values().iterator().next();
                taken = amount - rest.getAmount();
                result.setAmount(rest.getAmount());
                inventory.setResult(result);
            } else {
                inventory.setResult(null);
            }
            return taken;
        }, 0);
    }

    /** Společný rámec: ověření vzdálenosti + Furnace block state na region vlákně. */
    private <T> CompletableFuture<T> withFurnace(UUID botId, String worldName, BlockPos pos,
                                                 java.util.function.BiFunction<Player, Furnace, T> action,
                                                 T fallback) {
        World world = Bukkit.getWorld(worldName);
        Player player = Bukkit.getPlayer(botId);
        if (world == null || player == null) {
            return CompletableFuture.completedFuture(fallback);
        }
        Location location = new Location(world, pos.x(), pos.y(), pos.z());
        return bridge.callAt(location, () -> {
            if (player.getLocation().distanceSquared(location) > 6 * 6) {
                return fallback;
            }
            if (!(world.getBlockAt(pos.x(), pos.y(), pos.z()).getState() instanceof Furnace furnace)) {
                return fallback;
            }
            return action.apply(player, furnace);
        }).exceptionally(t -> fallback);
    }

    /** Přesune vyhovující stack z inventáře hráče do slotu pece. */
    private static int moveMatching(Player player, ItemStack currentSlot,
                                    java.util.function.Predicate<Material> filter,
                                    java.util.function.Consumer<ItemStack> setter) {
        var inventory = player.getInventory();
        Material slotType = currentSlot == null || currentSlot.getType().isAir()
                ? null : currentSlot.getType();
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || !filter.test(stack.getType())) {
                continue;
            }
            // Slot pece musí být prázdný, nebo stejného typu s místem.
            if (slotType == null) {
                inventory.setItem(slot, null);
                setter.accept(stack.clone());
                return stack.getAmount();
            }
            if (stack.getType() == slotType && currentSlot.getAmount() < currentSlot.getMaxStackSize()) {
                int space = currentSlot.getMaxStackSize() - currentSlot.getAmount();
                int moved = Math.min(space, stack.getAmount());
                currentSlot.setAmount(currentSlot.getAmount() + moved);
                setter.accept(currentSlot);
                if (moved >= stack.getAmount()) {
                    inventory.setItem(slot, null);
                } else {
                    stack.setAmount(stack.getAmount() - moved);
                    inventory.setItem(slot, stack);
                }
                return moved;
            }
        }
        return 0;
    }
}
