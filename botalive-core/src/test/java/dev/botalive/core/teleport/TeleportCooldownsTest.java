package dev.botalive.core.teleport;

import org.junit.jupiter.api.Test;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy teleportačních cooldownů.
 */
class TeleportCooldownsTest {

    @Test
    void novyHracNemaCooldown() {
        TeleportCooldowns cooldowns = new TeleportCooldowns(30, () -> 0L);
        assertEquals(0, cooldowns.remainingMs(UUID.randomUUID()));
    }

    @Test
    void poPouzitiBeziCooldownAPakVyprsi() {
        AtomicLong clock = new AtomicLong(1_000_000);
        TeleportCooldowns cooldowns = new TeleportCooldowns(30, clock::get);
        UUID player = UUID.randomUUID();

        cooldowns.markUsed(player);
        assertEquals(30_000, cooldowns.remainingMs(player));

        clock.addAndGet(10_000);
        assertEquals(20_000, cooldowns.remainingMs(player));

        clock.addAndGet(20_000);
        assertEquals(0, cooldowns.remainingMs(player));
    }

    @Test
    void nulovyCooldownJeVypnuty() {
        TeleportCooldowns cooldowns = new TeleportCooldowns(0, () -> 0L);
        UUID player = UUID.randomUUID();

        cooldowns.markUsed(player);
        assertEquals(0, cooldowns.remainingMs(player));
    }

    @Test
    void cooldownyJsouPerHrac() {
        AtomicLong clock = new AtomicLong(0);
        TeleportCooldowns cooldowns = new TeleportCooldowns(30, clock::get);
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();

        cooldowns.markUsed(first);
        assertTrue(cooldowns.remainingMs(first) > 0);
        assertEquals(0, cooldowns.remainingMs(second));
    }

    @Test
    void clearOdstraniZaznam() {
        TeleportCooldowns cooldowns = new TeleportCooldowns(30, () -> 0L);
        UUID player = UUID.randomUUID();

        cooldowns.markUsed(player);
        cooldowns.clear(player);
        assertEquals(0, cooldowns.remainingMs(player));
    }
}
