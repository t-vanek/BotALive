package dev.botalive.core.husbandry;

import dev.botalive.core.scheduler.MainThreadBridge;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Ageable;
import org.bukkit.entity.Animals;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * Chov zvířat – server-side část.
 *
 * <p>Samotné rozmnožování jde přes reálné pakety (bot dojde ke dvěma dospělým
 * zvířatům, vezme do ruky jejich krmivo a klikne na ně) a mechaniku vykonává
 * <b>vanilla server</b> – srdíčka, love mode, narození mláděte i pětiminutový
 * cooldown. Tato služba jen autoritativně čte stav {@link Ageable} entity, aby
 * bot krmil dospělé připravené k páření a neplýtval krmivo na mláďata či
 * zvířata na cooldownu (klientská metadata by šla parsovat, ale server-side
 * čtení je konzistentní se zbytkem architektury – stejný vzor jako
 * {@code TameService}).</p>
 */
public final class BreedService {

    /** Semínka pro slepice. */
    private static final Set<Material> SEEDS = Set.of(
            Material.WHEAT_SEEDS, Material.BEETROOT_SEEDS,
            Material.MELON_SEEDS, Material.PUMPKIN_SEEDS,
            Material.TORCHFLOWER_SEEDS, Material.PITCHER_POD);

    /** Stav zvířete pro chov. */
    public record BreedCheck(boolean exists, boolean adultReady) {

        /** Zvíře nedostupné/neexistuje/není hospodářské. */
        public static final BreedCheck MISSING = new BreedCheck(false, false);
    }

    private final MainThreadBridge bridge;

    /**
     * @param bridge most na herní vlákna
     */
    public BreedService(MainThreadBridge bridge) {
        this.bridge = bridge;
    }

    /**
     * Predikát krmiva k rozmnožení daného druhu hospodářského zvířete.
     *
     * @param type druh zvířete
     * @return predikát materiálu, nebo {@code null} pro druhy, které bot nechová
     */
    public static Predicate<Material> breedingFood(EntityType type) {
        return switch (type) {
            case COW, MOOSHROOM, SHEEP, GOAT -> m -> m == Material.WHEAT;
            case PIG -> m -> m == Material.CARROT || m == Material.POTATO
                    || m == Material.BEETROOT;
            case CHICKEN -> SEEDS::contains;
            case RABBIT -> m -> m == Material.CARROT || m == Material.GOLDEN_CARROT
                    || m == Material.DANDELION;
            default -> null;
        };
    }

    /**
     * @param type druh entity
     * @return {@code true} pro hospodářská zvířata, která umí bot chovat
     */
    public static boolean isLivestock(EntityType type) {
        return breedingFood(type) != null;
    }

    /**
     * Autoritativní kontrola: existuje zvíře a je to <b>dospělý jedinec
     * připravený k páření</b> (ne mládě, ne na cooldownu)?
     *
     * <p>{@link Ageable#getAge()}: {@code < 0} = mládě, {@code > 0} = dospělý
     * na cooldownu rozmnožování, {@code == 0} = dospělý připravený.</p>
     *
     * @param botId      UUID bota (region zvířete se hledá u něj)
     * @param animalUuid UUID zvířete
     * @return future se stavem (MISSING při nedostupnosti)
     */
    public CompletableFuture<BreedCheck> check(UUID botId, UUID animalUuid) {
        Player player = Bukkit.getPlayer(botId);
        if (player == null) {
            return CompletableFuture.completedFuture(BreedCheck.MISSING);
        }
        return bridge.<BreedCheck>callForEntity(player, () -> {
            Entity entity = Bukkit.getEntity(animalUuid);
            if (entity == null || !entity.isValid()
                    || entity.getWorld() != player.getWorld()
                    || entity.getLocation().distanceSquared(player.getLocation()) > 32 * 32) {
                return BreedCheck.MISSING;
            }
            if (!(entity instanceof Animals animal)) {
                return BreedCheck.MISSING; // vesničan je Ageable, ale ne Animals
            }
            return new BreedCheck(true, ((Ageable) animal).getAge() == 0);
        }).thenApply(check -> check == null ? BreedCheck.MISSING : check)
          .exceptionally(t -> BreedCheck.MISSING);
    }
}
