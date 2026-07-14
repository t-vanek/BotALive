package dev.botalive.core.pvp;

import dev.botalive.core.config.BotAliveConfig;
import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy PvP koordinátoru (férovostní strop, expirace hrozeb a asistencí).
 */
class PvpCoordinatorTest {

    private static BotAliveConfig.Pvp config(int maxAttackers) {
        return new BotAliveConfig.Pvp(true, false, true, true, 24, maxAttackers);
    }

    @Test
    void ferovostniStropOmezujeUtocniky() {
        PvpCoordinator pvp = new PvpCoordinator(config(2), () -> 0L);
        UUID target = UUID.randomUUID();

        assertTrue(pvp.registerAttacker(target, UUID.randomUUID(), false));
        assertTrue(pvp.registerAttacker(target, UUID.randomUUID(), false));
        assertFalse(pvp.registerAttacker(target, UUID.randomUUID(), false),
                "třetí útočník už se nevejde");
        assertEquals(2, pvp.attackerCount(target));
    }

    @Test
    void sebeobranaStropObchazi() {
        PvpCoordinator pvp = new PvpCoordinator(config(1), () -> 0L);
        UUID target = UUID.randomUUID();

        assertTrue(pvp.registerAttacker(target, UUID.randomUUID(), false));
        assertTrue(pvp.registerAttacker(target, UUID.randomUUID(), true),
                "napadený se smí bránit vždy");
        assertEquals(2, pvp.attackerCount(target));
    }

    @Test
    void opakovanaRegistraceJeIdempotentni() {
        PvpCoordinator pvp = new PvpCoordinator(config(1), () -> 0L);
        UUID target = UUID.randomUUID();
        UUID bot = UUID.randomUUID();

        assertTrue(pvp.registerAttacker(target, bot, false));
        assertTrue(pvp.registerAttacker(target, bot, false), "tentýž bot projde");
        assertEquals(1, pvp.attackerCount(target));

        pvp.unregisterAttacker(target, bot);
        assertEquals(0, pvp.attackerCount(target));
    }

    @Test
    void hrozbaExpiruje() {
        AtomicLong clock = new AtomicLong(1_000_000);
        PvpCoordinator pvp = new PvpCoordinator(config(2), clock::get);
        UUID victim = UUID.randomUUID();
        UUID attacker = UUID.randomUUID();

        pvp.recordThreat(victim, attacker, 42);
        assertTrue(pvp.threat(victim).isPresent(), "čerstvá hrozba platí");
        assertEquals(attacker, pvp.threat(victim).orElseThrow().attacker());

        clock.addAndGet(29_000);
        assertTrue(pvp.threat(victim).isPresent(), "hrozba platí do 30 s");

        clock.addAndGet(5_000);
        assertTrue(pvp.threat(victim).isEmpty(), "po 30 s hrozba expiruje");
    }

    @Test
    void clearAssistOdstraniZadost() {
        PvpCoordinator pvp = new PvpCoordinator(config(2), () -> 0L);
        UUID bot = UUID.randomUUID();
        pvp.clearAssist(bot); // idempotentní i bez záznamu
        assertTrue(pvp.assist(bot).isEmpty());
    }
}
