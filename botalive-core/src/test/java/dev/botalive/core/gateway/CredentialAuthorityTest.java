package dev.botalive.core.gateway;

import dev.botalive.core.gateway.CredentialAuthority.Authorization;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy vlastního ověřovacího procesu botů ({@link CredentialAuthority}).
 */
class CredentialAuthorityTest {

    private static final byte[] SECRET =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final UUID BOT =
            UUID.nameUUIDFromBytes("OfflinePlayer:Pepa".getBytes(StandardCharsets.UTF_8));

    private static CredentialAuthority authority(AtomicLong clock) {
        return new CredentialAuthority(SECRET, 60_000L, true, clock::get);
    }

    @Test
    void vydaniAOvereniTokenu() {
        CredentialAuthority auth = authority(new AtomicLong(1_000_000L));
        BotCredential cred = auth.issue(BOT, "Pepa");

        Optional<CredentialAuthority.VerifiedClaims> claims = auth.verifyToken(cred.token());
        assertTrue(claims.isPresent());
        assertEquals(BOT, claims.get().botId());
        assertEquals("Pepa", claims.get().name());
        assertFalse(cred.expired(1_000_000L));
    }

    @Test
    void podvrzenyTokenSeOdmitne() {
        CredentialAuthority auth = authority(new AtomicLong(1_000_000L));
        String token = auth.issue(BOT, "Pepa").token();
        String tampered = token.substring(0, token.length() - 2) + (token.endsWith("A") ? "B" : "A");

        assertTrue(auth.verifyToken(tampered).isEmpty());
        assertTrue(auth.verifyToken("naprosto.neplatny.token").isEmpty());
        assertTrue(auth.verifyToken(null).isEmpty());
    }

    @Test
    void offlineAutorizaceJeNaJednoPouziti() {
        CredentialAuthority auth = authority(new AtomicLong(1_000_000L));
        auth.issue(BOT, "Pepa");

        assertTrue(auth.authorizeLogin("Pepa", BOT).allowed());
        // Druhý pokus už autorizaci nemá (single-use).
        assertEquals(Authorization.Result.NO_PENDING, auth.authorizeLogin("Pepa", BOT).result());
    }

    @Test
    void autorizaceJmenaNerozlisujeVelikost() {
        CredentialAuthority auth = authority(new AtomicLong(1_000_000L));
        auth.issue(BOT, "Pepa");
        assertTrue(auth.authorizeLogin("pEpA", BOT).allowed());
    }

    @Test
    void neznameJmenoNeniAutorizovano() {
        CredentialAuthority auth = authority(new AtomicLong(1_000_000L));
        Authorization decision = auth.authorizeLogin("Zloduch", BOT);
        assertEquals(Authorization.Result.NO_PENDING, decision.result());
        assertFalse(decision.allowed());
    }

    @Test
    void vyprseleTokenIAutorizaceNeprojdou() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        CredentialAuthority auth = authority(clock);
        BotCredential cred = auth.issue(BOT, "Pepa");

        clock.addAndGet(61_000L); // za hranicí ttl 60 s
        assertTrue(auth.verifyToken(cred.token()).isEmpty());
        assertEquals(Authorization.Result.EXPIRED, auth.authorizeLogin("Pepa", BOT).result());
    }

    @Test
    void onlineCestaJoinAHasJoined() {
        CredentialAuthority auth = authority(new AtomicLong(1_000_000L));
        BotCredential cred = auth.issue(BOT, "Pepa");
        String serverId = "deadbeefserverhash";

        assertTrue(auth.registerJoin(cred.token(), BOT, serverId));

        Optional<GatewayProfile> profile = auth.resolveHasJoined("Pepa", serverId);
        assertTrue(profile.isPresent());
        assertEquals(BOT, profile.get().id());
        assertEquals(32, profile.get().undashedId().length());
        // hasJoined je jednorázové – podruhé už nic.
        assertTrue(auth.resolveHasJoined("Pepa", serverId).isEmpty());
    }

    @Test
    void joinOdmitneSpatnyProfilPodvrzenyTokenIJmeno() {
        CredentialAuthority auth = authority(new AtomicLong(1_000_000L));
        BotCredential cred = auth.issue(BOT, "Pepa");

        // Token je pro BOT, ale hlásí se jiné UUID.
        assertFalse(auth.registerJoin(cred.token(), UUID.randomUUID(), "sid1"));
        // Podvržený token.
        assertFalse(auth.registerJoin("bad.token", BOT, "sid2"));

        // Platný join, ale hasJoined se ptá na jiné jméno.
        BotCredential cred2 = auth.issue(BOT, "Pepa");
        assertTrue(auth.registerJoin(cred2.token(), BOT, "sid3"));
        assertTrue(auth.resolveHasJoined("Nekdo", "sid3").isEmpty());
    }

    @Test
    void kratkyKlicSeOdmitne() {
        assertThrows(IllegalArgumentException.class,
                () -> new CredentialAuthority("krátký".getBytes(StandardCharsets.UTF_8), 1_000L, true));
    }
}
