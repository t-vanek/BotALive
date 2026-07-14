package dev.botalive.core.tame;

import dev.botalive.core.scheduler.MainThreadBridge;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Tameable;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * Ochočování zvířat – server-side část.
 *
 * <p>Samotné ochočování jde přes reálné pakety (interact se zvířetem s kostí,
 * rybou, semínky, nebo opakované nasedání na koně) a mechaniku vykonává
 * <b>vanilla server</b> – šance na ochočení, srdíčka, vzpínání koně. Tato
 * služba jen autoritativně čte stav {@link Tameable} entity (klientská
 * metadata by šla parsovat, ale server-side čtení je konzistentní se zbytkem
 * architektury, viz ARCHITECTURE.md §2).</p>
 */
public final class TameService {

    /** Stav ochočitelné entity. */
    public record TameCheck(boolean exists, boolean tameable, boolean tamedBySomeone,
                            boolean tamedByBot) {

        /** Entita nedostupná/neexistuje. */
        public static final TameCheck MISSING = new TameCheck(false, false, false, false);
    }

    /** Semínka pro papoušky. */
    private static final Set<Material> SEEDS = Set.of(
            Material.WHEAT_SEEDS, Material.MELON_SEEDS,
            Material.PUMPKIN_SEEDS, Material.BEETROOT_SEEDS);

    private final MainThreadBridge bridge;

    /**
     * @param bridge most na herní vlákna
     */
    public TameService(MainThreadBridge bridge) {
        this.bridge = bridge;
    }

    /**
     * Predikát itemu potřebného k ochočení daného druhu; {@code null} pro
     * druhy ochočované nasedáním (koně, osli, muly, lamy).
     *
     * @param type druh zvířete
     * @return predikát materiálu, nebo {@code null} pro mount-based taming
     */
    public static Predicate<Material> tamingItem(EntityType type) {
        return switch (type) {
            case WOLF -> m -> m == Material.BONE;
            case CAT -> m -> m == Material.COD || m == Material.SALMON;
            case PARROT -> SEEDS::contains;
            default -> null; // HORSE/DONKEY/MULE/LLAMA – opakované nasedání
        };
    }

    /**
     * @param type druh zvířete
     * @return {@code true} pokud se druh ochočuje nasedáním
     */
    public static boolean tamedByMounting(EntityType type) {
        return switch (type) {
            case HORSE, DONKEY, MULE, LLAMA -> true;
            default -> false;
        };
    }

    /**
     * Autoritativně ověří stav ochočení zvířete. Bot musí být poblíž
     * (entita se hledá na vlákně regionu bota).
     *
     * @param botId      UUID bota
     * @param animalUuid UUID zvířete
     * @return future se stavem (MISSING při nedostupnosti)
     */
    public CompletableFuture<TameCheck> check(UUID botId, UUID animalUuid) {
        Player player = Bukkit.getPlayer(botId);
        if (player == null) {
            return CompletableFuture.completedFuture(TameCheck.MISSING);
        }
        return bridge.<TameCheck>callForEntity(player, () -> {
            Entity entity = Bukkit.getEntity(animalUuid);
            if (entity == null || !entity.isValid()
                    || entity.getWorld() != player.getWorld()
                    || entity.getLocation().distanceSquared(player.getLocation()) > 32 * 32) {
                return TameCheck.MISSING;
            }
            if (!(entity instanceof Tameable tameable)) {
                return new TameCheck(true, false, false, false);
            }
            boolean tamed = tameable.isTamed();
            boolean mine = tamed && botId.equals(tameable.getOwnerUniqueId());
            return new TameCheck(true, true, tamed, mine);
        }).thenApply(check -> check == null ? TameCheck.MISSING : check)
          .exceptionally(t -> TameCheck.MISSING);
    }
}
