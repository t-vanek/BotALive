package dev.botalive.core.gateway;

import java.util.UUID;

/**
 * Jednorázové ověřovací pověření vydané {@link CredentialAuthority} pro připojení
 * jednoho bota.
 *
 * <p>Pověření je jádrem vlastního ověřovacího procesu: BotAlive ho vystaví
 * bezprostředně před tím, než se bot připojí, čímž serveru (přes gateway)
 * dokáže, že spojení pochází od skutečného, pluginem řízeného bota – ne od
 * někoho, kdo se jen vydává za jeho jméno. Token je podepsaný HMAC-SHA256,
 * časově omezený a (dle konfigurace) na jedno použití.</p>
 *
 * @param botId       offline-mode UUID bota (stabilní identita)
 * @param botName     přihlašovací jméno bota
 * @param token       podepsaný token (payload + HMAC podpis), viz {@link CredentialAuthority}
 * @param issuedAtMs  čas vystavení (epoch ms)
 * @param expiresAtMs čas vypršení (epoch ms)
 */
public record BotCredential(UUID botId, String botName, String token,
                            long issuedAtMs, long expiresAtMs) {

    /**
     * @param nowMs aktuální čas (epoch ms)
     * @return {@code true} pokud pověření k danému času už vypršelo
     */
    public boolean expired(long nowMs) {
        return nowMs >= expiresAtMs;
    }

    /**
     * @param nowMs aktuální čas (epoch ms)
     * @return zbývající platnost v ms (nikdy záporná)
     */
    public long remainingMs(long nowMs) {
        return Math.max(0L, expiresAtMs - nowMs);
    }
}
