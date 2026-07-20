package dev.botalive.core.inventory;

import dev.botalive.core.crafting.BrewPlanner;
import dev.botalive.core.scheduler.MainThreadBridge;
import dev.botalive.core.util.BlockPos;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.BrewingStand;
import org.bukkit.entity.Player;
import org.bukkit.inventory.BrewerInventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.PotionMeta;
import org.bukkit.potion.PotionType;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Server-side práce s varným stojanem (vanilla vaření lektvarů).
 *
 * <p>Stejný vzor jako {@link FurnaceService}: bot ke stojanu dojde a klikne
 * na něj (reálný interact paket), naložení lahví/přísady/paliva běží
 * autoritativně na vlákně regionu stojanu a vaření pak probíhá skutečnou
 * vanilla mechanikou (bublinky, 20 s na vsázku). Lahve se vybírají podle
 * {@link PotionMeta#getBasePotionType()} – voda pro awkward základ, awkward
 * pro efekty, hotový jed pro splash konverzi.</p>
 */
public final class BrewingService implements dev.botalive.core.station.BrewingStation {

    private final MainThreadBridge bridge;

    /**
     * @param bridge most na region vlákna
     */
    public BrewingService(MainThreadBridge bridge) {
        this.bridge = bridge;
    }

    @Override
    public CompletableFuture<LoadReport> load(dev.botalive.core.ai.BotContext ctx,
                                              String worldName, BlockPos pos,
                                              Material ingredient, BrewPlanner.Base base) {
        return load(ctx.bot().id(), worldName, pos, ingredient, base);
    }

    @Override
    public CompletableFuture<Integer> collect(dev.botalive.core.ai.BotContext ctx,
                                              String worldName, BlockPos pos, boolean force) {
        return collect(ctx.bot().id(), worldName, pos, force);
    }

    /**
     * Naloží do stojanu lahve, přísadu a případně palivo.
     *
     * @param botId      UUID bota (musí stát u stojanu)
     * @param worldName  svět stojanu
     * @param pos        pozice stojanu
     * @param ingredient přísada vsázky
     * @param base       jaké lahve se nakládají
     * @return future s výsledkem naložení
     */
    public CompletableFuture<LoadReport> load(UUID botId, String worldName, BlockPos pos,
                                              Material ingredient, BrewPlanner.Base base) {
        return withStand(botId, worldName, pos, (player, stand) -> {
            BrewerInventory inventory = stand.getInventory();
            var playerInventory = player.getInventory();

            // Palivo: prázdný palivoměr dostane jeden blaze prach (20 vsázek).
            if (stand.getFuelLevel() < 1
                    && (inventory.getFuel() == null || inventory.getFuel().getType().isAir())) {
                takeOne(playerInventory, m -> m == Material.BLAZE_POWDER)
                        .ifPresent(inventory::setFuel);
            }

            // Lahve odpovídající základu do volných slotů 0–2.
            int bottles = 0;
            for (int slot = 0; slot <= 2; slot++) {
                ItemStack current = inventory.getItem(slot);
                if (current != null && !current.getType().isAir()) {
                    bottles++; // slot už obsazený (rozvařená vsázka)
                    continue;
                }
                ItemStack bottle = takeBottle(playerInventory, base);
                if (bottle == null) {
                    continue;
                }
                inventory.setItem(slot, bottle);
                bottles++;
            }

            // Přísada (1 kus) – jen do prázdného slotu.
            boolean ingredientLoaded = inventory.getIngredient() != null
                    && !inventory.getIngredient().getType().isAir();
            if (!ingredientLoaded && bottles > 0) {
                var taken = takeOne(playerInventory, m -> m == ingredient);
                if (taken.isPresent()) {
                    inventory.setIngredient(taken.get());
                    ingredientLoaded = true;
                }
            }
            return new LoadReport(bottles, ingredientLoaded);
        }, LoadReport.EMPTY);
    }

    /**
     * Vyzvedne dovařené lahve (0, dokud vaření běží; {@code force} vybere
     * stojan celý včetně přísady – návrat nevalidní vsázky).
     *
     * @param botId     UUID bota (musí stát u stojanu)
     * @param worldName svět stojanu
     * @param pos       pozice stojanu
     * @param force     vybrat i rozvařený/nevalidní obsah
     * @return future s počtem vyzvednutých lahví
     */
    public CompletableFuture<Integer> collect(UUID botId, String worldName, BlockPos pos,
                                              boolean force) {
        return withStand(botId, worldName, pos, (player, stand) -> {
            BrewerInventory inventory = stand.getInventory();
            boolean ingredientWaiting = inventory.getIngredient() != null
                    && !inventory.getIngredient().getType().isAir();
            if (!force && (stand.getBrewingTime() > 0 || ingredientWaiting)) {
                return 0; // vsázka se ještě vaří
            }
            int taken = 0;
            for (int slot = 0; slot <= 2; slot++) {
                ItemStack bottle = inventory.getItem(slot);
                if (bottle == null || bottle.getType().isAir()) {
                    continue;
                }
                if (player.getInventory().addItem(bottle.clone()).isEmpty()) {
                    inventory.setItem(slot, null);
                    taken++;
                }
            }
            if (force && ingredientWaiting
                    && player.getInventory().addItem(inventory.getIngredient().clone())
                            .isEmpty()) {
                inventory.setIngredient(null);
            }
            return taken;
        }, 0);
    }

    /** Vybere z inventáře 1 kus vyhovujícího materiálu. */
    private static java.util.Optional<ItemStack> takeOne(
            org.bukkit.inventory.PlayerInventory inventory,
            java.util.function.Predicate<Material> filter) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || !filter.test(stack.getType())) {
                continue;
            }
            ItemStack one = stack.clone();
            one.setAmount(1);
            if (stack.getAmount() <= 1) {
                inventory.setItem(slot, null);
            } else {
                stack.setAmount(stack.getAmount() - 1);
                inventory.setItem(slot, stack);
            }
            return java.util.Optional.of(one);
        }
        return java.util.Optional.empty();
    }

    /** Vybere z inventáře jednu láhev odpovídající základu vsázky. */
    private static ItemStack takeBottle(org.bukkit.inventory.PlayerInventory inventory,
                                        BrewPlanner.Base base) {
        for (int slot = 0; slot < 36; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null || !matchesBase(stack, base)) {
                continue;
            }
            ItemStack one = stack.clone();
            one.setAmount(1);
            if (stack.getAmount() <= 1) {
                inventory.setItem(slot, null);
            } else {
                stack.setAmount(stack.getAmount() - 1);
                inventory.setItem(slot, stack);
            }
            return one;
        }
        return null;
    }

    /** Odpovídá láhev základu vsázky (dle typu lektvaru z metadat)? */
    private static boolean matchesBase(ItemStack stack, BrewPlanner.Base base) {
        if (stack.getType() != Material.POTION
                || !(stack.getItemMeta() instanceof PotionMeta meta)) {
            return false;
        }
        PotionType type = meta.getBasePotionType();
        if (type == null) {
            return false;
        }
        return switch (base) {
            case WATER -> type == PotionType.WATER;
            case AWKWARD -> type == PotionType.AWKWARD;
            case POISON -> type == PotionType.POISON || type == PotionType.LONG_POISON
                    || type == PotionType.STRONG_POISON;
        };
    }

    /** Společný rámec: ověření vzdálenosti + BrewingStand state na region vlákně. */
    private <T> CompletableFuture<T> withStand(UUID botId, String worldName, BlockPos pos,
                                               java.util.function.BiFunction<Player, BrewingStand, T> action,
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
            if (!(world.getBlockAt(pos.x(), pos.y(), pos.z())
                    .getState() instanceof BrewingStand stand)) {
                return fallback;
            }
            return action.apply(player, stand);
        }).exceptionally(t -> fallback);
    }
}
