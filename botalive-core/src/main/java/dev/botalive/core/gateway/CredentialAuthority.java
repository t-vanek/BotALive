package dev.botalive.core.gateway;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Autorita vlastního ověřovacího procesu botů – „vydavatel a ověřovatel"
 * jednorázových pověření.
 *
 * <p>Řeší jádro problému offline-mode serveru: bez ověření se kdokoli může
 * připojit pod libovolným jménem, včetně jmen botů. Autorita proto pro každé
 * připojení bota vystaví krátkodobé pověření a nabízí dvě nezávislé cesty, jak
 * ho ověřit:</p>
 *
 * <ul>
 *   <li><b>Offline cesta</b> (výchozí, {@link #issue} + {@link #authorizeLogin}):
 *       token se po síti neposílá (offline handshake pro něj nemá pole). Autorita
 *       si při vystavení zapamatuje <em>čekající autorizaci</em> a server-side
 *       posluchač loginu ({@code BotLoginGuard}) ji při přihlášení bota spotřebuje.
 *       Impersonátor nezpůsobí vystavení autorizace, takže login pod jménem bota
 *       bez čekající autorizace se odmítne.</li>
 *   <li><b>Online cesta</b> (volitelná, {@link #registerJoin} + {@link #resolveHasJoined}):
 *       bot v online-mode handshaku pošle podepsaný token na endpoint gateway
 *       {@code /join}; token se ověří kryptograficky (HMAC-SHA256), bez stavu.
 *       Server pak profil vyzvedne přes {@code hasJoined}.</li>
 * </ul>
 *
 * <p>Formát tokenu (vše mimo číselné časy je Base64URL bez výplně, oddělovač
 * {@code '.'} se v Base64URL abecedě nevyskytuje):</p>
 * <pre>{@code
 *   payload = b64(botId) '.' b64(name) '.' issuedAt '.' expiresAt '.' b64(nonce)
 *   token   = payload '.' b64(HMAC_SHA256(secret, payload))
 * }</pre>
 *
 * <p>Instance je vláknově bezpečná (souběžné vystavování a ověřování z tick,
 * síťových i HTTP vláken). Hodiny se injektují kvůli testovatelnosti.</p>
 */
public final class CredentialAuthority {

    private static final String HMAC_ALGO = "HmacSHA256";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();
    private static final SecureRandom RANDOM = new SecureRandom();

    private final byte[] secret;
    private final long ttlMs;
    private final boolean singleUse;
    private final LongSupplier clock;

    /** Čekající offline autorizace podle jména (lowercase). */
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    /** Ověřené online „join" požadavky podle serverId (mezistupeň k hasJoined). */
    private final Map<String, Join> joins = new ConcurrentHashMap<>();

    /**
     * @param secret    tajný klíč HMAC (min. 16 B; delší je lepší)
     * @param ttlMs     platnost vydaných pověření v ms
     * @param singleUse zda offline autorizaci spotřebovat na první použití
     */
    public CredentialAuthority(byte[] secret, long ttlMs, boolean singleUse) {
        this(secret, ttlMs, singleUse, System::currentTimeMillis);
    }

    /**
     * @param secret    tajný klíč HMAC
     * @param ttlMs     platnost vydaných pověření v ms
     * @param singleUse zda offline autorizaci spotřebovat na první použití
     * @param clock     zdroj času (epoch ms) – injektovaný kvůli testům
     */
    public CredentialAuthority(byte[] secret, long ttlMs, boolean singleUse, LongSupplier clock) {
        if (secret == null || secret.length < 16) {
            throw new IllegalArgumentException("Tajný klíč gateway musí mít alespoň 16 bajtů");
        }
        this.secret = secret.clone();
        this.ttlMs = Math.max(1_000L, ttlMs);
        this.singleUse = singleUse;
        this.clock = clock;
    }

    // ---------------------------------------------------------- vydání pověření

    /**
     * Vystaví nové pověření pro bota a zaregistruje čekající offline autorizaci.
     * Volá se bezprostředně před připojením bota (i při reconnectu).
     *
     * @param botId offline-mode UUID bota
     * @param name  přihlašovací jméno bota
     * @return podepsané pověření
     */
    public BotCredential issue(UUID botId, String name) {
        long now = clock.getAsLong();
        long expires = now + ttlMs;
        byte[] nonce = new byte[12];
        RANDOM.nextBytes(nonce);
        String token = sign(botId, name, now, expires, nonce);
        pending.put(name.toLowerCase(Locale.ROOT), new Pending(botId, expires));
        sweep(now);
        return new BotCredential(botId, name, token, now, expires);
    }

    // ------------------------------------------------------------- offline cesta

    /**
     * Ověří přihlášení bota v offline režimu spotřebováním čekající autorizace.
     *
     * @param name        přihlašovací jméno z login paketu
     * @param presentedId UUID z login paketu (pro kontrolu/log)
     * @return rozhodnutí autorizace včetně očekávaného UUID
     */
    public Authorization authorizeLogin(String name, UUID presentedId) {
        long now = clock.getAsLong();
        String key = name.toLowerCase(Locale.ROOT);
        Pending current = pending.get(key);
        if (current == null) {
            return new Authorization(Authorization.Result.NO_PENDING, null);
        }
        if (now >= current.expiresAtMs()) {
            pending.remove(key, current);
            return new Authorization(Authorization.Result.EXPIRED, current.botId());
        }
        if (singleUse && !pending.remove(key, current)) {
            // Souběžně spotřebováno jiným pokusem – ber jako už použité.
            return new Authorization(Authorization.Result.NO_PENDING, current.botId());
        }
        return new Authorization(Authorization.Result.ALLOWED, current.botId());
    }

    // -------------------------------------------------------------- online cesta

    /**
     * Zpracuje požadavek {@code /session/minecraft/join} od bota: ověří token
     * kryptograficky a zaznamená profil pro následné {@code hasJoined}.
     *
     * @param accessToken     token z pověření bota
     * @param selectedProfile UUID, pod kterým se bot hlásí
     * @param serverId        serverId hash z handshaku
     * @return {@code true} pokud je token platný a odpovídá profilu
     */
    public boolean registerJoin(String accessToken, UUID selectedProfile, String serverId) {
        Optional<VerifiedClaims> claims = verifyToken(accessToken);
        if (claims.isEmpty() || !claims.get().botId().equals(selectedProfile)) {
            return false;
        }
        long now = clock.getAsLong();
        joins.put(serverId, new Join(new GatewayProfile(selectedProfile, claims.get().name()),
                now + ttlMs));
        sweep(now);
        return true;
    }

    /**
     * Zpracuje serverový dotaz {@code hasJoined}: vrátí profil pro dřívější
     * platný {@code join} a spotřebuje ho.
     *
     * @param username jméno z dotazu serveru
     * @param serverId serverId hash z dotazu serveru
     * @return profil, pokud existuje odpovídající join
     */
    public Optional<GatewayProfile> resolveHasJoined(String username, String serverId) {
        Join join = joins.remove(serverId);
        if (join == null || clock.getAsLong() >= join.expiresAtMs()) {
            return Optional.empty();
        }
        if (!join.profile().name().equalsIgnoreCase(username)) {
            return Optional.empty();
        }
        return Optional.of(join.profile());
    }

    // -------------------------------------------------------------- ověření tokenu

    /**
     * Ověří podpis a platnost tokenu (bez stavu).
     *
     * @param token token k ověření
     * @return nárokované údaje, pokud je token platný a nevypršel
     */
    public Optional<VerifiedClaims> verifyToken(String token) {
        if (token == null) {
            return Optional.empty();
        }
        try {
            String[] parts = token.split("\\.");
            if (parts.length != 6) {
                return Optional.empty();
            }
            String payload = parts[0] + "." + parts[1] + "." + parts[2] + "." + parts[3]
                    + "." + parts[4];
            byte[] expected = hmac(payload.getBytes(StandardCharsets.UTF_8));
            byte[] actual = B64D.decode(parts[5]);
            if (!MessageDigest.isEqual(expected, actual)) {
                return Optional.empty();
            }
            UUID botId = UUID.fromString(new String(B64D.decode(parts[0]), StandardCharsets.UTF_8));
            String name = new String(B64D.decode(parts[1]), StandardCharsets.UTF_8);
            long issued = Long.parseLong(parts[2]);
            long expires = Long.parseLong(parts[3]);
            if (clock.getAsLong() >= expires) {
                return Optional.empty();
            }
            return Optional.of(new VerifiedClaims(botId, name, issued, expires));
        } catch (RuntimeException e) {
            // Poškozený/podvržený token – neplatný, nikdy nevyhazujeme dál.
            return Optional.empty();
        }
    }

    /** @return počet aktuálně čekajících offline autorizací (diagnostika). */
    public int pendingCount() {
        return pending.size();
    }

    // ------------------------------------------------------------------- interní

    private String sign(UUID botId, String name, long issued, long expires, byte[] nonce) {
        String payload = B64.encodeToString(botId.toString().getBytes(StandardCharsets.UTF_8))
                + "." + B64.encodeToString(name.getBytes(StandardCharsets.UTF_8))
                + "." + issued
                + "." + expires
                + "." + B64.encodeToString(nonce);
        byte[] sig = hmac(payload.getBytes(StandardCharsets.UTF_8));
        return payload + "." + B64.encodeToString(sig);
    }

    private byte[] hmac(byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret, HMAC_ALGO));
            return mac.doFinal(data);
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException("HMAC výpočet selhal", e);
        }
    }

    /** Odstraní vypršelé záznamy, aby paměť nerostla neomezeně. */
    private void sweep(long now) {
        pending.values().removeIf(p -> now >= p.expiresAtMs());
        joins.values().removeIf(j -> now >= j.expiresAtMs());
    }

    private record Pending(UUID botId, long expiresAtMs) {
    }

    private record Join(GatewayProfile profile, long expiresAtMs) {
    }

    /**
     * Ověřené údaje z platného tokenu.
     *
     * @param botId       UUID bota
     * @param name        jméno bota
     * @param issuedAtMs  čas vystavení
     * @param expiresAtMs čas vypršení
     */
    public record VerifiedClaims(UUID botId, String name, long issuedAtMs, long expiresAtMs) {
    }

    /**
     * Výsledek offline autorizace přihlášení.
     *
     * @param result     výsledek
     * @param expectedId UUID, které autorita pro dané jméno očekávala (nebo {@code null})
     */
    public record Authorization(Result result, UUID expectedId) {

        /** Možné výsledky autorizace. */
        public enum Result {
            /** Platná čekající autorizace nalezena a spotřebována. */
            ALLOWED,
            /** Pro jméno není žádná (nespotřebovaná) čekající autorizace. */
            NO_PENDING,
            /** Čekající autorizace existovala, ale vypršela. */
            EXPIRED
        }

        /** @return {@code true} jen pro {@link Result#ALLOWED}. */
        public boolean allowed() {
            return result == Result.ALLOWED;
        }
    }
}
