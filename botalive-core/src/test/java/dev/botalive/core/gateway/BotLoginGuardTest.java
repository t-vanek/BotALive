package dev.botalive.core.gateway;

import org.junit.jupiter.api.Test;

import java.net.InetAddress;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy politiky zdrojové adresy přihlašovací pojistky.
 */
class BotLoginGuardTest {

    @Test
    void loopbackJeLokalni() throws Exception {
        assertTrue(BotLoginGuard.isLocalSource(InetAddress.getByName("127.0.0.1")));
        assertTrue(BotLoginGuard.isLocalSource(InetAddress.getLoopbackAddress()));
        assertTrue(BotLoginGuard.isLocalSource(InetAddress.getByName("::1")));
    }

    @Test
    void privatniLanJeLokalni() throws Exception {
        assertTrue(BotLoginGuard.isLocalSource(InetAddress.getByName("192.168.1.20")));
        assertTrue(BotLoginGuard.isLocalSource(InetAddress.getByName("10.0.0.5")));
    }

    @Test
    void verejnaAdresaNeniLokalni() throws Exception {
        assertFalse(BotLoginGuard.isLocalSource(InetAddress.getByName("8.8.8.8")));
        assertFalse(BotLoginGuard.isLocalSource(InetAddress.getByName("203.0.113.7")));
    }

    @Test
    void nullNeniLokalni() {
        assertFalse(BotLoginGuard.isLocalSource(null));
    }
}
