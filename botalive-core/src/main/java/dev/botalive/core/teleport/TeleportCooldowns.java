package dev.botalive.core.teleport;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Cooldowny teleportace pro běžné hráče.
 *
 * <p>Admini cooldown obcházejí (řeší volající podle práv); tady se jen
 * eviduje čas posledního použití per hráč. Injektovatelné hodiny drží třídu
 * čistou a testovatelnou. Thread-safe.</p>
 */
public final class TeleportCooldowns {

    private final long cooldownMs;
    private final LongSupplier clock;
    private final Map<UUID, Long> lastUse = new ConcurrentHashMap<>();

    /**
     * @param cooldownSeconds délka cooldownu v sekundách (0 = vypnuto)
     */
    public TeleportCooldowns(int cooldownSeconds) {
        this(cooldownSeconds, System::currentTimeMillis);
    }

    /**
     * @param cooldownSeconds délka cooldownu v sekundách (0 = vypnuto)
     * @param clock           zdroj času (pro testy)
     */
    public TeleportCooldowns(int cooldownSeconds, LongSupplier clock) {
        this.cooldownMs = Math.max(0, cooldownSeconds) * 1000L;
        this.clock = clock;
    }

    /**
     * @param playerId UUID hráče
     * @return zbývající cooldown v ms; 0 = může teleportovat
     */
    public long remainingMs(UUID playerId) {
        if (cooldownMs == 0) {
            return 0;
        }
        Long last = lastUse.get(playerId);
        if (last == null) {
            return 0;
        }
        long elapsed = clock.getAsLong() - last;
        return Math.max(0, cooldownMs - elapsed);
    }

    /**
     * Zaznamená použití teleportu (start cooldownu).
     *
     * @param playerId UUID hráče
     */
    public void markUsed(UUID playerId) {
        if (cooldownMs > 0) {
            lastUse.put(playerId, clock.getAsLong());
        }
    }

    /**
     * Smaže záznam hráče (např. při odchodu ze serveru – úklid paměti).
     *
     * @param playerId UUID hráče
     */
    public void clear(UUID playerId) {
        lastUse.remove(playerId);
    }
}
