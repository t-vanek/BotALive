package dev.botalive.core.world;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import dev.botalive.core.config.BotAliveConfig;
import dev.botalive.core.scheduler.MainThreadBridge;
import dev.botalive.core.util.BlockPos;
import org.bukkit.ChunkSnapshot;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link WorldView} nad Bukkit {@link ChunkSnapshot}y s Caffeine cache.
 *
 * <p>Snapshoty se pořizují na vlákně vlastnícím region chunku (Folia-safe) a
 * jsou nemutabilní, takže se dají bezpečně číst z AI vláken botů. Cache má TTL,
 * aby boti viděli změny světa (rozbité/postavené bloky) s malým zpožděním;
 * bodové změny navíc okamžitě invaliduje {@code BlockChangeListener}.</p>
 */
public final class SnapshotWorldView implements WorldView {

    private final World world;
    private final Dimension dimension;
    private final MainThreadBridge bridge;

    /** Cache snapshotů: klíč = kompaktní (chunkX, chunkZ). */
    private final Cache<Long, ChunkSnapshot> snapshots;

    /** Chunky, jejichž načtení už bylo vyžádáno (ochrana proti dublování požadavků). */
    private final Map<Long, Boolean> pending = new ConcurrentHashMap<>();

    /**
     * @param world  Bukkit svět
     * @param bridge most na region vlákna
     * @param perf   výkonnostní konfigurace (velikost cache, TTL)
     */
    public SnapshotWorldView(World world, MainThreadBridge bridge, BotAliveConfig.Performance perf) {
        this.world = Objects.requireNonNull(world, "world");
        this.dimension = Dimension.fromBukkit(world.getEnvironment());
        this.bridge = bridge;
        this.snapshots = Caffeine.newBuilder()
                .maximumSize(perf.chunkCacheSize())
                .expireAfterWrite(Duration.ofMillis(perf.chunkCacheTtlMs()))
                .build();
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    @Override
    public Material materialAt(BlockPos pos) {
        if (pos.y() < world.getMinHeight() || pos.y() >= world.getMaxHeight()) {
            return null;
        }
        ChunkSnapshot snapshot = snapshots.getIfPresent(chunkKey(pos.chunkX(), pos.chunkZ()));
        if (snapshot == null) {
            requestChunk(pos.chunkX(), pos.chunkZ());
            return null;
        }
        return snapshot.getBlockType(pos.x() & 15, pos.y(), pos.z() & 15);
    }

    @Override
    public org.bukkit.block.data.BlockData blockDataAt(BlockPos pos) {
        if (pos.y() < world.getMinHeight() || pos.y() >= world.getMaxHeight()) {
            return null;
        }
        ChunkSnapshot snapshot = snapshots.getIfPresent(chunkKey(pos.chunkX(), pos.chunkZ()));
        if (snapshot == null) {
            requestChunk(pos.chunkX(), pos.chunkZ());
            return null;
        }
        return snapshot.getBlockData(pos.x() & 15, pos.y(), pos.z() & 15);
    }

    @Override
    public BlockTraits traitsAt(BlockPos pos) {
        if (pos.y() < world.getMinHeight()) {
            return BlockTraits.UNKNOWN; // pod světem – propast
        }
        if (pos.y() >= world.getMaxHeight()) {
            return BlockTraits.AIR;
        }
        ChunkSnapshot snapshot = snapshots.getIfPresent(chunkKey(pos.chunkX(), pos.chunkZ()));
        if (snapshot == null) {
            requestChunk(pos.chunkX(), pos.chunkZ());
            return BlockTraits.UNKNOWN;
        }
        Material material = snapshot.getBlockType(pos.x() & 15, pos.y(), pos.z() & 15);
        if (material == null) {
            return BlockTraits.UNKNOWN;
        }
        // Stavově citlivé bloky (desky, schody, dveře, sníh…) čteme z plných
        // block dat – per-state cache v BlockTraits drží náklady na jednom
        // přečtení pro každý unikátní stav.
        if (BlockTraits.stateSensitive(material)) {
            return BlockTraits.of(snapshot.getBlockData(pos.x() & 15, pos.y(), pos.z() & 15));
        }
        return BlockTraits.of(material);
    }

    @Override
    public boolean isAvailable(BlockPos pos) {
        return snapshots.getIfPresent(chunkKey(pos.chunkX(), pos.chunkZ())) != null;
    }

    @Override
    public void prefetch(BlockPos center, int radiusChunks) {
        int cx = center.chunkX();
        int cz = center.chunkZ();
        for (int dx = -radiusChunks; dx <= radiusChunks; dx++) {
            for (int dz = -radiusChunks; dz <= radiusChunks; dz++) {
                if (snapshots.getIfPresent(chunkKey(cx + dx, cz + dz)) == null) {
                    requestChunk(cx + dx, cz + dz);
                }
            }
        }
    }

    @Override
    public String worldName() {
        return world.getName();
    }

    @Override
    public Dimension dimension() {
        return dimension;
    }

    /**
     * Bodová invalidace po změně bloku (volá {@code BlockChangeListener}).
     *
     * @param chunkX X chunku
     * @param chunkZ Z chunku
     */
    public void invalidate(int chunkX, int chunkZ) {
        snapshots.invalidate(chunkKey(chunkX, chunkZ));
    }

    /**
     * Vyžádá pořízení snapshotu na vlákně regionu. Nenačtené chunky se
     * <b>neforsují</b> – bot prostě „nevidí“ za hranici načteného světa,
     * stejně jako skutečný hráč.
     */
    private void requestChunk(int chunkX, int chunkZ) {
        long key = chunkKey(chunkX, chunkZ);
        if (pending.putIfAbsent(key, Boolean.TRUE) != null) {
            return;
        }
        Location loc = new Location(world, (chunkX << 4) + 8, world.getMinHeight(), (chunkZ << 4) + 8);
        bridge.runAt(loc, () -> {
            try {
                if (world.isChunkLoaded(chunkX, chunkZ)) {
                    ChunkSnapshot snapshot = world.getChunkAt(chunkX, chunkZ).getChunkSnapshot(false, false, false);
                    snapshots.put(key, snapshot);
                }
            } finally {
                pending.remove(key);
            }
        });
    }
}
