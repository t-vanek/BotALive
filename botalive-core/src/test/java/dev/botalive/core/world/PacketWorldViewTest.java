package dev.botalive.core.world;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.state.FallbackBlockStateMapper;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftTypes;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.ChunkSection;
import org.geysermc.mcprotocollib.protocol.data.game.chunk.DataPalette;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy klientského world modelu.
 *
 * <p>Chunk data se serializují {@code MinecraftTypes.writeChunkSection} –
 * tedy přesně formátem, který posílá server – a parsují zpátky přes
 * {@link PacketWorldView#loadChunk}. Round-trip přes samotnou knihovnu
 * ověřuje kompatibilitu s protokolem bez závislosti na běžícím serveru.</p>
 */
class PacketWorldViewTest {

    private static final int MIN_Y = -64;
    private static final int HEIGHT = 384;
    private static final int SECTIONS = HEIGHT >> 4;
    private static final int STONE_STATE = 1; // fallback mapper: nenulové = pevné

    private static final DimensionRegistry.DimensionInfo DIMENSION =
            new DimensionRegistry.DimensionInfo(MIN_Y, HEIGHT);

    /** Sestaví payload chunku: vzduch všude, pevná „podlaha" na y=64. */
    private static byte[] chunkDataWithFloor(int floorY) {
        ByteBuf buf = Unpooled.buffer();
        try {
            for (int i = 0; i < SECTIONS; i++) {
                int sectionMinY = MIN_Y + (i << 4);
                // createFor*(výchozí hodnota singleton palety, globální bity)
                ChunkSection section = new ChunkSection(0, 0,
                        DataPalette.createForBlockState(0, 15),
                        DataPalette.createForBiome(0, 6));
                if (floorY >= sectionMinY && floorY < sectionMinY + 16) {
                    for (int x = 0; x < 16; x++) {
                        for (int z = 0; z < 16; z++) {
                            section.setBlock(x, floorY - sectionMinY, z, STONE_STATE);
                        }
                    }
                }
                MinecraftTypes.writeChunkSection(buf, section);
            }
            byte[] data = new byte[buf.readableBytes()];
            buf.readBytes(data);
            return data;
        } finally {
            buf.release();
        }
    }

    private static PacketWorldView viewWithFloorChunk() {
        PacketWorldView view = new PacketWorldView("minecraft:overworld", DIMENSION,
                new FallbackBlockStateMapper(), 6);
        view.loadChunk(0, 0, chunkDataWithFloor(64));
        return view;
    }

    @Test
    void roundTripChunkuDaSpravneBloky() {
        PacketWorldView view = viewWithFloorChunk();

        assertTrue(view.traitsAt(new BlockPos(5, 64, 5)).solid(), "podlaha má být pevná");
        assertTrue(view.traitsAt(new BlockPos(5, 65, 5)).passable(), "nad podlahou je vzduch");
        assertTrue(view.traitsAt(new BlockPos(5, -30, 5)).passable(), "hluboko je vzduch");
        assertEquals(1, view.loadedChunks());
    }

    @Test
    void nenactenyChunkJeNeznamy() {
        PacketWorldView view = viewWithFloorChunk();

        assertEquals(BlockTraits.UNKNOWN, view.traitsAt(new BlockPos(100, 64, 100)));
        assertNull(view.materialAt(new BlockPos(100, 64, 100)));
        assertFalse(view.isAvailable(new BlockPos(100, 64, 100)));
    }

    @Test
    void blockUpdateMeniStav() {
        PacketWorldView view = viewWithFloorChunk();
        BlockPos pos = new BlockPos(3, 70, 3);
        assertTrue(view.traitsAt(pos).passable());

        view.blockUpdate(3, 70, 3, STONE_STATE);
        assertTrue(view.traitsAt(pos).solid(), "po block update má být blok pevný");

        view.blockUpdate(3, 70, 3, 0);
        assertTrue(view.traitsAt(pos).passable(), "po rozbití je zase vzduch");
    }

    @Test
    void forgetChunkOdstraniData() {
        PacketWorldView view = viewWithFloorChunk();
        view.forgetChunk(0, 0);

        assertEquals(0, view.loadedChunks());
        assertEquals(BlockTraits.UNKNOWN, view.traitsAt(new BlockPos(5, 64, 5)));
    }

    @Test
    void hraniceSvetaJsouOsetrene() {
        PacketWorldView view = viewWithFloorChunk();

        assertEquals(BlockTraits.AIR, view.traitsAt(new BlockPos(5, MIN_Y + HEIGHT + 10, 5)),
                "nad světem je vzduch");
        assertEquals(BlockTraits.UNKNOWN, view.traitsAt(new BlockPos(5, MIN_Y - 1, 5)),
                "pod světem je propast (neznámo)");
    }
}
