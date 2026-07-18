package dev.botalive.core.world;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.state.BlockStateMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.ChunkSection;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Klientský world model – {@link WorldView} rekonstruovaný čistě z paketů.
 *
 * <p>Implementace pro boty hrající na <b>cizím serveru</b>: geometrii světa
 * nelze číst z Bukkit API, takže se parsují chunk pakety
 * ({@code ClientboundLevelChunkWithLightPacket} přes
 * {@link MinecraftTypes#readChunkSection}) a průběžně aplikují blokové změny
 * (BlockUpdate/SectionBlocksUpdate/ForgetLevelChunk). Číselné block states
 * překládá {@link BlockStateMapper}.</p>
 *
 * <p><b>Thread-safety:</b> zápisy přicházejí ze síťového vlákna bota
 * (per-bot jednovláknový executor), čtení z tick a pathfinding vláken.
 * Mapa chunků je concurrent; přístup k sekcím jednoho chunku je serializovaný
 * krátkým zámkem chunku (kolize čtení/zápis jsou vzácné – zápisy jen při
 * načtení chunku a blokových změnách).</p>
 */
public final class PacketWorldView implements WorldView {

    private static final Logger LOG = LoggerFactory.getLogger(PacketWorldView.class);

    private final String worldKey;
    private final int minY;
    private final int height;
    private final int blockBits;
    private final int biomeBits;
    private final BlockStateMapper mapper;

    private final Map<Long, ChunkColumn> chunks = new ConcurrentHashMap<>();

    /** Sloupec chunk sekcí se zámkem pro konzistentní čtení/zápis. */
    private static final class ChunkColumn {
        private final ChunkSection[] sections;

        ChunkColumn(ChunkSection[] sections) {
            this.sections = sections;
        }

        synchronized int state(int localX, int relativeY, int localZ) {
            ChunkSection section = sections[relativeY >> 4];
            return section == null ? 0 : section.getBlock(localX, relativeY & 15, localZ);
        }

        synchronized void setState(int localX, int relativeY, int localZ, int stateId) {
            ChunkSection section = sections[relativeY >> 4];
            if (section != null) {
                section.setBlock(localX, relativeY & 15, localZ, stateId);
            }
        }
    }

    /**
     * @param worldKey  protokolový klíč světa (např. {@code minecraft:overworld})
     * @param dimension rozměry dimenze (min_y, height)
     * @param mapper    překlad block states
     * @param biomeBits bity biome palety (z registru biomů)
     */
    public PacketWorldView(String worldKey, DimensionRegistry.DimensionInfo dimension,
                           BlockStateMapper mapper, int biomeBits) {
        this.worldKey = worldKey;
        this.minY = dimension.minY();
        this.height = dimension.height();
        this.mapper = mapper;
        this.blockBits = DimensionRegistry.ceilLog2(mapper.stateCount());
        this.biomeBits = biomeBits;
    }

    private static long chunkKey(int chunkX, int chunkZ) {
        return ((long) chunkX << 32) ^ (chunkZ & 0xFFFFFFFFL);
    }

    // ------------------------------------------------------- zápis z paketů

    /**
     * Načte chunk z payload dat paketu (síťové vlákno).
     *
     * @param chunkX X chunku
     * @param chunkZ Z chunku
     * @param data   payload {@code ClientboundLevelChunkWithLightPacket}
     */
    public void loadChunk(int chunkX, int chunkZ, byte[] data) {
        int sectionCount = height >> 4;
        ChunkSection[] sections = new ChunkSection[sectionCount];
        ByteBuf buf = Unpooled.wrappedBuffer(data);
        try {
            for (int i = 0; i < sectionCount; i++) {
                sections[i] = MinecraftTypes.readChunkSection(buf, blockBits, biomeBits);
            }
        } catch (Throwable t) {
            LOG.warn("Chunk [{}, {}] světa {} se nepodařilo naparsovat: {}",
                    chunkX, chunkZ, worldKey, t.toString());
            return;
        } finally {
            buf.release();
        }
        chunks.put(chunkKey(chunkX, chunkZ), new ChunkColumn(sections));
    }

    /**
     * Bodová změna bloku (BlockUpdate / SectionBlocksUpdate).
     *
     * @param x       blok X
     * @param y       blok Y
     * @param z       blok Z
     * @param stateId nový block state
     */
    public void blockUpdate(int x, int y, int z, int stateId) {
        int relativeY = y - minY;
        if (relativeY < 0 || relativeY >= height) {
            return;
        }
        ChunkColumn column = chunks.get(chunkKey(x >> 4, z >> 4));
        if (column != null) {
            column.setState(x & 15, relativeY, z & 15, stateId);
        }
    }

    /**
     * Zapomenutí chunku (ForgetLevelChunk).
     *
     * @param chunkX X chunku
     * @param chunkZ Z chunku
     */
    public void forgetChunk(int chunkX, int chunkZ) {
        chunks.remove(chunkKey(chunkX, chunkZ));
    }

    /** @return počet aktuálně načtených chunků (diagnostika) */
    public int loadedChunks() {
        return chunks.size();
    }

    // --------------------------------------------------------- čtení (AI)

    /** @return block state na pozici, nebo -1 pokud není k dispozici */
    private int stateAt(BlockPos pos) {
        int relativeY = pos.y() - minY;
        if (relativeY < 0 || relativeY >= height) {
            return -1;
        }
        ChunkColumn column = chunks.get(chunkKey(pos.chunkX(), pos.chunkZ()));
        if (column == null) {
            return -1;
        }
        try {
            return column.state(pos.x() & 15, relativeY, pos.z() & 15);
        } catch (RuntimeException e) {
            return -1; // poškozená sekce – chovat se jako nenačtený chunk
        }
    }

    @Override
    public Material materialAt(BlockPos pos) {
        int state = stateAt(pos);
        return state < 0 ? null : mapper.materialOf(state);
    }

    @Override
    public BlockData blockDataAt(BlockPos pos) {
        int state = stateAt(pos);
        return state < 0 ? null : mapper.blockDataOf(state);
    }

    @Override
    public BlockTraits traitsAt(BlockPos pos) {
        if (pos.y() >= minY + height) {
            return BlockTraits.AIR; // nad světem
        }
        if (pos.y() < minY) {
            return BlockTraits.UNKNOWN; // pod světem – propast
        }
        int state = stateAt(pos);
        return state < 0 ? BlockTraits.UNKNOWN : mapper.traitsOf(state);
    }

    @Override
    public boolean isAvailable(BlockPos pos) {
        return chunks.containsKey(chunkKey(pos.chunkX(), pos.chunkZ()));
    }

    @Override
    public void prefetch(BlockPos center, int radiusChunks) {
        // Chunky posílá server sám podle view distance – není co přednačítat.
    }

    @Override
    public String worldName() {
        return worldKey;
    }

    @Override
    public WorldDimension dimension() {
        // Packet režim nemá Bukkit Environment – odhad z klíče světa.
        return WorldDimension.fromWorldKey(worldKey);
    }
}
