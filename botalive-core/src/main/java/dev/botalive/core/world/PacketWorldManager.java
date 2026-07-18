package dev.botalive.core.world;

import dev.botalive.core.world.state.BlockStateMapper;
import net.kyori.adventure.key.Key;
import org.geysermc.mcprotocollib.protocol.data.game.RegistryEntry;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundBlockUpdatePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundForgetLevelChunkPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundSectionBlocksUpdatePacket;
import org.geysermc.mcprotocollib.protocol.data.game.level.block.BlockChangeEntry;

import java.util.List;

/**
 * Správa klientského world modelu jednoho bota (režim {@code packet}).
 *
 * <p>Drží registry dimenzí z konfigurační fáze, aktuální dimenzi (login/
 * respawn) a aktivní {@link PacketWorldView}. Při přepnutí světa se starý
 * pohled zahazuje – server po respawnu chunky posílá znovu, stejně jako
 * vanilla klientovi.</p>
 *
 * <p>Zápisové metody volá výhradně síťové vlákno bota; {@link #currentView()}
 * čtou AI vlákna (volatile).</p>
 */
public final class PacketWorldManager {

    private final DimensionRegistry registry = new DimensionRegistry();
    private final BlockStateMapper mapper;

    private volatile PacketWorldView current;
    private volatile int dimensionIndex;

    /**
     * @param mapper překlad block states (sdílený všemi boty)
     */
    public PacketWorldManager(BlockStateMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * Registry data z konfigurační fáze.
     *
     * @param registryKey klíč registru
     * @param entries     položky
     */
    public void onRegistryData(Key registryKey, List<RegistryEntry> entries) {
        registry.accept(registryKey, entries);
    }

    /**
     * Nastaví aktuální dimenzi (login/respawn).
     *
     * @param dimensionIndex index do dimension_type registru
     */
    public void dimension(int dimensionIndex) {
        this.dimensionIndex = dimensionIndex;
    }

    /**
     * Přepne na (nový) svět – vytvoří čerstvý pohled pro danou dimenzi.
     *
     * @param worldKey protokolový klíč světa
     * @return aktivní pohled na svět
     */
    public PacketWorldView switchTo(String worldKey) {
        PacketWorldView existing = current;
        if (existing != null && existing.worldName().equals(worldKey)) {
            return existing;
        }
        PacketWorldView view = new PacketWorldView(worldKey,
                registry.dimensionInfo(dimensionIndex), mapper, registry.biomeBits());
        current = view;
        return view;
    }

    /** @return aktivní pohled, nebo {@code null} před prvním loginem */
    /**
     * @param id síťové ID enchantu
     * @return klíč enchantu z registru konfigurační fáze, nebo {@code null}
     */
    public String enchantmentKey(int id) {
        return registry.enchantmentKey(id);
    }

    public PacketWorldView currentView() {
        return current;
    }

    // ------------------------------------------------- routing chunk paketů

    /** Načtení chunku. */
    public void onChunk(ClientboundLevelChunkWithLightPacket packet) {
        PacketWorldView view = current;
        if (view != null) {
            view.loadChunk(packet.getX(), packet.getZ(), packet.getChunkData());
        }
    }

    /** Bodová změna bloku. */
    public void onBlockUpdate(ClientboundBlockUpdatePacket packet) {
        PacketWorldView view = current;
        if (view != null) {
            var entry = packet.getEntry();
            view.blockUpdate(entry.getPosition().getX(), entry.getPosition().getY(),
                    entry.getPosition().getZ(), entry.getBlock());
        }
    }

    /** Hromadná změna bloků v sekci. */
    public void onSectionUpdate(ClientboundSectionBlocksUpdatePacket packet) {
        PacketWorldView view = current;
        if (view == null) {
            return;
        }
        for (BlockChangeEntry entry : packet.getEntries()) {
            view.blockUpdate(entry.getPosition().getX(), entry.getPosition().getY(),
                    entry.getPosition().getZ(), entry.getBlock());
        }
    }

    /** Zapomenutí chunku. */
    public void onForgetChunk(ClientboundForgetLevelChunkPacket packet) {
        PacketWorldView view = current;
        if (view != null) {
            view.forgetChunk(packet.getX(), packet.getZ());
        }
    }
}
