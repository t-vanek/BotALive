package dev.botalive.core.network;

import dev.botalive.core.physics.MoveInput;
import dev.botalive.core.util.Vec3;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerState;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientTickEndPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundPlayerInputPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerStatusOnlyPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundPlayerCommandPacket;

/**
 * Odesílání pohybových paketů přesně podle chování vanilla klienta.
 *
 * <p>Vanilla posílá Pos/Rot/PosRot podle toho, co se změnilo, idle pozici
 * nejméně jednou za 20 ticků, změny sprintu přes PlayerCommand, stav vstupu
 * přes PlayerInput a na konci každého ticku ClientTickEnd. Odchylky od tohoto
 * vzoru jsou snadno detekovatelné anti-cheatem – proto je replikujeme věrně.</p>
 */
public final class MovementSender {

    private static final double POSITION_EPSILON_SQ = 4.0E-8;
    private static final float ROTATION_EPSILON = 0.01f;
    private static final int IDLE_RESEND_TICKS = 20;

    private final BotConnection connection;
    private final BotClientState state;

    private Vec3 lastSentPos = Vec3.ZERO;
    private float lastSentYaw;
    private float lastSentPitch;
    private boolean lastOnGround;
    private int ticksSincePosition;

    private boolean sprinting;
    private MoveInput lastInput = MoveInput.IDLE;

    /**
     * @param connection spojení bota
     * @param state      protokolový stav bota
     */
    public MovementSender(BotConnection connection, BotClientState state) {
        this.connection = connection;
        this.state = state;
    }

    /**
     * Tvrdá synchronizace po teleportu – další tick neposílá deltu proti
     * předteleportové pozici.
     *
     * @param pos   pozice po teleportu
     * @param yaw   yaw po teleportu
     * @param pitch pitch po teleportu
     */
    public void resetTo(Vec3 pos, float yaw, float pitch) {
        this.lastSentPos = pos;
        this.lastSentYaw = yaw;
        this.lastSentPitch = pitch;
        this.ticksSincePosition = 0;
    }

    /**
     * Odešle pohybové pakety za jeden tick.
     *
     * @param pos                 aktuální pozice (nohy)
     * @param yaw                 aktuální yaw
     * @param pitch               aktuální pitch
     * @param onGround            bot stojí na zemi
     * @param horizontalCollision bot narazil do zdi
     * @param input               pohybový záměr (kvůli sprint/sneak/input paketům)
     */
    public void tick(Vec3 pos, float yaw, float pitch, boolean onGround,
                     boolean horizontalCollision, MoveInput input) {
        sendInputChanges(input);
        sendSprintChanges(input);

        boolean moved = pos.distanceSquared(lastSentPos) > POSITION_EPSILON_SQ;
        boolean rotated = Math.abs(yaw - lastSentYaw) > ROTATION_EPSILON
                || Math.abs(pitch - lastSentPitch) > ROTATION_EPSILON;
        boolean groundChanged = onGround != lastOnGround;
        ticksSincePosition++;

        if (moved || ticksSincePosition >= IDLE_RESEND_TICKS) {
            if (rotated) {
                connection.send(new ServerboundMovePlayerPosRotPacket(
                        onGround, horizontalCollision, pos.x(), pos.y(), pos.z(), yaw, pitch));
                lastSentYaw = yaw;
                lastSentPitch = pitch;
            } else {
                connection.send(new ServerboundMovePlayerPosPacket(
                        onGround, horizontalCollision, pos.x(), pos.y(), pos.z()));
            }
            lastSentPos = pos;
            ticksSincePosition = 0;
        } else if (rotated) {
            connection.send(new ServerboundMovePlayerRotPacket(onGround, horizontalCollision, yaw, pitch));
            lastSentYaw = yaw;
            lastSentPitch = pitch;
        } else if (groundChanged) {
            connection.send(new ServerboundMovePlayerStatusOnlyPacket(onGround, horizontalCollision));
        }
        lastOnGround = onGround;

        // Konec klientského ticku – server podle něj řídí simulaci hráče.
        connection.send(ServerboundClientTickEndPacket.INSTANCE);
    }

    /** Pošle PlayerInput paket při změně „držených kláves". */
    private void sendInputChanges(MoveInput input) {
        boolean forward = input.direction().horizontalLength() > 1.0E-4;
        boolean lastForward = lastInput.direction().horizontalLength() > 1.0E-4;
        if (forward != lastForward
                || input.jump() != lastInput.jump()
                || input.sneak() != lastInput.sneak()
                || input.sprint() != lastInput.sprint()) {
            connection.send(new ServerboundPlayerInputPacket(
                    forward, false, false, false, input.jump(), input.sneak(), input.sprint()));
        }
        lastInput = input;
    }

    /** Pošle PlayerCommand při zapnutí/vypnutí sprintu. */
    private void sendSprintChanges(MoveInput input) {
        if (input.sprint() && !sprinting) {
            connection.send(new ServerboundPlayerCommandPacket(state.entityId(), PlayerState.START_SPRINTING));
            sprinting = true;
        } else if (!input.sprint() && sprinting) {
            connection.send(new ServerboundPlayerCommandPacket(state.entityId(), PlayerState.STOP_SPRINTING));
            sprinting = false;
        }
    }
}
