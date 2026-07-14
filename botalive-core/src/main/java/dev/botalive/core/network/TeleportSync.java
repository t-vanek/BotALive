package dev.botalive.core.network;

import dev.botalive.core.util.Vec3;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;

import java.util.List;

/**
 * Teleport od serveru čekající na aplikaci na tick vlákně bota.
 *
 * <p>Potvrzení (accept) se posílá okamžitě ze síťového vlákna; aplikace na
 * fyziku bota proběhne v nejbližším ticku, aby stav bota měnilo jen jeho vlákno.</p>
 *
 * @param teleportId id teleportu (pro accept)
 * @param position   cílová pozice (může být relativní)
 * @param deltaMovement rychlost po teleportu (může být relativní)
 * @param yaw        cílový yaw
 * @param pitch      cílový pitch
 * @param relatives  které složky jsou relativní vůči aktuálnímu stavu
 */
public record TeleportSync(
        int teleportId,
        Vec3 position,
        Vec3 deltaMovement,
        float yaw,
        float pitch,
        List<PositionElement> relatives
) {
}
