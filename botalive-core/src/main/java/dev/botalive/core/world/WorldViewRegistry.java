package dev.botalive.core.world;

import dev.botalive.core.config.BotAliveConfig;
import dev.botalive.core.scheduler.MainThreadBridge;
import org.bukkit.Bukkit;
import org.bukkit.World;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registr {@link WorldView} instancí – jeden pohled na svět, sdílený všemi boty.
 *
 * <p>Sdílení šetří paměť i hlavní vlákno: chunk snapshot pořízený pro jednoho
 * bota poslouží všem ostatním ve stejné oblasti.</p>
 */
public final class WorldViewRegistry {

    private final MainThreadBridge bridge;
    private final BotAliveConfig.Performance performance;
    private final Map<String, SnapshotWorldView> views = new ConcurrentHashMap<>();

    /**
     * @param bridge      most na region vlákna
     * @param performance výkonnostní konfigurace
     */
    public WorldViewRegistry(MainThreadBridge bridge, BotAliveConfig.Performance performance) {
        this.bridge = bridge;
        this.performance = performance;
    }

    /**
     * @param worldName název Bukkit světa
     * @return sdílený pohled na svět
     * @throws IllegalArgumentException pokud svět neexistuje
     */
    public SnapshotWorldView view(String worldName) {
        return views.computeIfAbsent(worldName, name -> {
            World world = Bukkit.getWorld(name);
            if (world == null) {
                throw new IllegalArgumentException("Svět neexistuje: " + name);
            }
            return new SnapshotWorldView(world, bridge, performance);
        });
    }

    /**
     * Bodová invalidace cache po změně bloku.
     *
     * @param worldName svět
     * @param chunkX    X chunku
     * @param chunkZ    Z chunku
     */
    public void invalidate(String worldName, int chunkX, int chunkZ) {
        SnapshotWorldView view = views.get(worldName);
        if (view != null) {
            view.invalidate(chunkX, chunkZ);
        }
    }
}
