package dev.botalive.core.bootstrap;

import dev.botalive.api.memory.MemoryKind;
import dev.botalive.core.bot.BotManagerImpl;
import dev.botalive.core.world.WorldViewRegistry;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExplodeEvent;

import java.util.Map;

/**
 * Server-side posluchač událostí, které boti potřebují vnímat.
 *
 * <p>Dvě odpovědnosti:</p>
 * <ul>
 *   <li><b>Invalidace world cache</b> – změny bloků okamžitě zneplatní chunk
 *       snapshot, aby boti neplánovali cesty přes už neexistující bloky.</li>
 *   <li><b>Atribuce útoků</b> – když někdo uhodí bota, bot si útočníka
 *       zapamatuje jako nepřítele (podklad pro pomstu v {@code CombatGoal}).</li>
 * </ul>
 */
public final class ServerEventListener implements Listener {

    private final WorldViewRegistry worldViews;
    private final BotManagerImpl botManager;
    private final dev.botalive.core.pvp.PvpCoordinator pvp;

    /**
     * @param worldViews registr pohledů na světy
     * @param botManager manager botů
     * @param pvp        PvP koordinátor (hrozby, volání o pomoc)
     */
    public ServerEventListener(WorldViewRegistry worldViews, BotManagerImpl botManager,
                               dev.botalive.core.pvp.PvpCoordinator pvp) {
        this.worldViews = worldViews;
        this.botManager = botManager;
        this.pvp = pvp;
    }

    // ------------------------------------------------------ invalidace bloků

    /** Zneplatní chunk po rozbití bloku. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBreak(BlockBreakEvent event) {
        invalidate(event.getBlock().getLocation());
    }

    /** Zneplatní chunk po položení bloku. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        invalidate(event.getBlock().getLocation());
    }

    /** Zneplatní chunk po shoření bloku. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockBurn(BlockBurnEvent event) {
        invalidate(event.getBlock().getLocation());
    }

    /** Zneplatní chunky po výbuchu bloku. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBlockExplode(BlockExplodeEvent event) {
        event.blockList().forEach(block -> invalidate(block.getLocation()));
    }

    /** Zneplatní chunky po výbuchu entity (creeper, TNT). */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onEntityExplode(EntityExplodeEvent event) {
        event.blockList().forEach(block -> invalidate(block.getLocation()));
    }

    private void invalidate(Location location) {
        worldViews.invalidate(location.getWorld().getName(),
                location.getBlockX() >> 4, location.getBlockZ() >> 4);
    }

    // --------------------------------------------------------- útoky na boty

    /** Zapamatuje útočníka jako nepřítele napadeného bota. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onBotDamaged(EntityDamageByEntityEvent event) {
        if (!(event.getEntity() instanceof Player victim)) {
            return;
        }
        botManager.byId(victim.getUniqueId()).ifPresent(bot -> {
            Entity damager = event.getDamager();
            // Střelec za projektilem.
            if (damager instanceof Projectile projectile
                    && projectile.getShooter() instanceof Entity shooter) {
                damager = shooter;
            }
            Location where = victim.getLocation();
            double severity = Math.min(1.0, 0.4 + event.getFinalDamage() / 20.0);
            bot.memory().remember(MemoryKind.ENEMY, where.getWorld().getName(),
                    where.getBlockX(), where.getBlockY(), where.getBlockZ(),
                    damager.getUniqueId(),
                    Map.of("type", damager.getType().name()), severity);

            // PvP: útoky hráčů/botů evidovat jako hrozbu + svolat spojence.
            if (damager instanceof Player) {
                pvp.onBotAttacked(bot, damager.getUniqueId(), damager.getEntityId());
            }
        });
    }
}
