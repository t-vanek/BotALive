package dev.botalive.core.network;

import dev.botalive.core.util.BlockPos;
import org.cloudburstmc.math.vector.Vector3i;
import org.geysermc.mcprotocollib.protocol.data.game.ClientCommand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.Hand;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerAction;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundAttackPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerActionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSetCarriedItemPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundSwingPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemOnPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundUseItemPacket;

import java.util.BitSet;

/**
 * Akční primitivy bota – jednotlivé „stisky tlačítek" skutečného klienta.
 *
 * <p>Všechny akce jdou přes protokol (bot je klient, ne NPC); server je
 * validuje stejně jako u lidského hráče. Vyšší vrstvy (tasky, cíle) tyto
 * primitivy skládají do smysluplného chování.</p>
 */
public final class BotActions {

    private final BotConnection connection;
    private final BotClientState state;

    /**
     * @param connection spojení bota
     * @param state      protokolový stav (sequence čísla, held slot)
     */
    public BotActions(BotConnection connection, BotClientState state) {
        this.connection = connection;
        this.state = state;
    }

    /** Máchne hlavní rukou (animace). */
    public void swing() {
        connection.send(new ServerboundSwingPacket(Hand.MAIN_HAND));
    }

    /**
     * Zaútočí na entitu (musí být v dosahu, jinak to server zahodí).
     *
     * @param entityId síťové id cíle
     */
    public void attack(int entityId) {
        connection.send(new ServerboundAttackPacket(entityId));
        swing();
    }

    /**
     * Vybere hotbar slot.
     *
     * @param hotbarIndex 0–8
     */
    public void selectHotbar(int hotbarIndex) {
        int slot = Math.floorMod(hotbarIndex, 9);
        if (state.heldSlot() != slot) {
            connection.send(new ServerboundSetCarriedItemPacket(slot));
            state.heldSlot(slot);
        }
    }

    /**
     * Začne kopat blok.
     *
     * @param pos  pozice bloku
     * @param face strana, ze které bot kope
     */
    public void startDigging(BlockPos pos, Direction face) {
        connection.send(new ServerboundPlayerActionPacket(PlayerAction.START_DIGGING,
                Vector3i.from(pos.x(), pos.y(), pos.z()), face, state.nextSequence()));
        swing();
    }

    /**
     * Dokončí kopání bloku (po uplynutí doby těžby).
     *
     * @param pos  pozice bloku
     * @param face strana
     */
    public void finishDigging(BlockPos pos, Direction face) {
        connection.send(new ServerboundPlayerActionPacket(PlayerAction.FINISH_DIGGING,
                Vector3i.from(pos.x(), pos.y(), pos.z()), face, state.nextSequence()));
    }

    /**
     * Zruší rozkopaný blok.
     *
     * @param pos  pozice bloku
     * @param face strana
     */
    public void cancelDigging(BlockPos pos, Direction face) {
        connection.send(new ServerboundPlayerActionPacket(PlayerAction.CANCEL_DIGGING,
                Vector3i.from(pos.x(), pos.y(), pos.z()), face, state.nextSequence()));
    }

    /**
     * Použije držený item na blok (položení bloku, otevření dveří, tlačítko...).
     *
     * @param pos  cílový blok
     * @param face strana bloku
     */
    public void useItemOn(BlockPos pos, Direction face) {
        connection.send(new ServerboundUseItemOnPacket(
                Vector3i.from(pos.x(), pos.y(), pos.z()), face, Hand.MAIN_HAND,
                0.5f, 0.5f, 0.5f, false, false, state.nextSequence()));
        swing();
    }

    /**
     * Použije držený item „do vzduchu" (jídlo, štít, luk...).
     *
     * @param yaw   aktuální yaw bota
     * @param pitch aktuální pitch bota
     */
    public void useItem(float yaw, float pitch) {
        connection.send(new ServerboundUseItemPacket(Hand.MAIN_HAND, state.nextSequence(), yaw, pitch));
    }

    /**
     * Pustí používaný item (štít dolů, přestat natahovat luk).
     */
    public void releaseUseItem() {
        connection.send(new ServerboundPlayerActionPacket(PlayerAction.RELEASE_USE_ITEM,
                Vector3i.from(0, 0, 0), Direction.DOWN, state.nextSequence()));
    }

    /**
     * Vyhodí jeden kus drženého itemu.
     */
    public void dropItem() {
        connection.send(new ServerboundPlayerActionPacket(PlayerAction.DROP_ITEM,
                Vector3i.from(0, 0, 0), Direction.DOWN, state.nextSequence()));
    }

    /**
     * Požádá o respawn po smrti.
     */
    public void respawn() {
        connection.send(new ServerboundClientCommandPacket(ClientCommand.PERFORM_RESPAWN));
    }

    /**
     * Odešle chat zprávu (nepodepsanou – offline-mode identita nemá chat klíče).
     *
     * @param message text zprávy
     */
    public void chat(String message) {
        connection.send(new ServerboundChatPacket(message, System.currentTimeMillis(), 0L,
                null, 0, new BitSet(), 0));
    }
}
