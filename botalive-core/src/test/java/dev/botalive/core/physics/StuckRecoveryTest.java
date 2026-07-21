package dev.botalive.core.physics;

import dev.botalive.core.util.BotRandom;
import dev.botalive.core.util.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ověřuje repertoár vyproštění: neaktivní v klidu, první manévr couvá,
 * respektuje průchodnost stran, a po vyčerpání repertoáru epizoda skončí.
 */
class StuckRecoveryTest {

    private static final Vec3 FORWARD = new Vec3(1, 0, 0);

    private static double dot(Vec3 a, Vec3 b) {
        return a.x() * b.x() + a.z() * b.z();
    }

    @Test
    void inactiveByDefault() {
        StuckRecovery recovery = new StuckRecovery();
        assertFalse(recovery.active());
        assertTrue(recovery.tick(FORWARD, true, true, true, true, new BotRandom(1))
                .equals(MoveInput.IDLE));
    }

    @Test
    void firstManeuverBacksAwayFromObstacle() {
        StuckRecovery recovery = new StuckRecovery();
        recovery.begin();
        assertTrue(recovery.active());

        MoveInput first = recovery.tick(FORWARD, true, true, true, true, new BotRandom(1));
        assertTrue(dot(first.direction(), FORWARD) < 0, "couvá pryč od překážky, kam tlačil");
    }

    @Test
    void backUpPicksAnOpenSideWhenBackIsBlocked() {
        StuckRecovery recovery = new StuckRecovery();
        recovery.begin();
        // Za zády zeď, vlevo volno → couvání zvolí levý bok (kolmý na forward).
        MoveInput move = recovery.tick(FORWARD, false, true, false, true, new BotRandom(1));
        assertTrue(Math.abs(dot(move.direction(), FORWARD)) < 1e-9,
                "když je za zády zeď, uhne kolmo do volného boku");
    }

    @Test
    void repertoireExhaustsAndEpisodeEnds() {
        StuckRecovery recovery = new StuckRecovery();
        recovery.begin();
        BotRandom rng = new BotRandom(7);
        // Repertoár má 4 manévry po 8 ticích; po jejich odtikání epizoda skončí.
        boolean sawMovement = false;
        for (int tick = 0; tick < 4 * 8; tick++) {
            MoveInput move = recovery.tick(FORWARD, true, true, true, true, rng);
            sawMovement |= !move.equals(MoveInput.IDLE);
        }
        assertTrue(sawMovement, "během repertoáru bot manévruje");
        // Další tick už repertoár vyčerpá a epizodu ukončí.
        recovery.tick(FORWARD, true, true, true, true, rng);
        assertFalse(recovery.active(), "po vyčerpání repertoáru epizoda skončí");
    }
}
