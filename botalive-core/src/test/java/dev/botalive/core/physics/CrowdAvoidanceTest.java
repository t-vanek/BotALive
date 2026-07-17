package dev.botalive.core.physics;

import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.util.Vec3;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy separačního steeringu (vyhýbání davu).
 */
class CrowdAvoidanceTest {

    private static TrackedEntity playerAt(int id, double x, double z) {
        return new TrackedEntity(id, UUID.randomUUID(), EntityType.PLAYER, new Vec3(x, 64, z));
    }

    @Test
    void bezSouseduVraciPuvodniSmer() {
        Vec3 desired = new Vec3(1, 0, 0);
        Vec3 out = CrowdAvoidance.steer(new Vec3(0, 64, 0), 1, List.of(), desired);
        assertEquals(desired, out, "beze sousedů se směr nemá měnit");
    }

    @Test
    void odpuzujeOdBlizkehoSouseda() {
        // Soused kousek na východ (+x); stojící bot se má rozejít na západ (−x).
        Vec3 out = CrowdAvoidance.steer(new Vec3(0, 64, 0), 1,
                List.of(playerAt(2, 0.5, 0)), Vec3.ZERO);
        assertTrue(out.x() < -0.5, "má se odpuzovat na západ, bylo: " + out);
    }

    @Test
    void vzdalenehoSousedaIgnoruje() {
        Vec3 desired = new Vec3(1, 0, 0);
        Vec3 out = CrowdAvoidance.steer(new Vec3(0, 64, 0), 1,
                List.of(playerAt(2, 10, 0)), desired);
        assertEquals(desired, out, "soused mimo poloměr nemá mít vliv");
    }

    @Test
    void celniBlokUhneDoStrany() {
        // Soused stojí přesně v cestě (bot jde na východ, soused na východě).
        // Prosté odpuzování by se se směrem vyrušilo – čeká se úkrok do strany.
        Vec3 desired = new Vec3(1, 0, 0);
        Vec3 out = CrowdAvoidance.steer(new Vec3(0, 64, 0), 2,
                List.of(playerAt(7, 1.0, 0)), desired);
        assertTrue(out.horizontalLength() > 0.5, "úkrok nesmí bota zastavit: " + out);
        assertTrue(Math.abs(out.z()) > 0.5, "čekal se boční úkrok (z-složka): " + out);
        // Deterministicky: liché id uhne na druhou stranu než sudé.
        Vec3 other = CrowdAvoidance.steer(new Vec3(0, 64, 0), 3,
                List.of(playerAt(7, 1.0, 0)), desired);
        assertTrue(out.z() * other.z() < 0, "různá parita id má uhnout opačně");
    }

    @Test
    void presnyPrekryvRozbijeSymetrii() {
        // Dva boti na identické pozici musí dostat nenulový (a různý) směr úniku.
        Vec3 self = new Vec3(0, 64, 0);
        Vec3 a = CrowdAvoidance.steer(self, 1, List.of(playerAt(2, 0, 0)), Vec3.ZERO);
        Vec3 b = CrowdAvoidance.steer(self, 2, List.of(playerAt(1, 0, 0)), Vec3.ZERO);
        assertTrue(a.horizontalLength() > 0.5, "překryv má dát únikový směr, bylo: " + a);
        assertTrue(b.horizontalLength() > 0.5, "překryv má dát únikový směr, bylo: " + b);
    }
}
