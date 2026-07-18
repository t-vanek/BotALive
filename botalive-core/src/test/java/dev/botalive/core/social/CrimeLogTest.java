package dev.botalive.core.social;

import dev.botalive.core.util.BlockPos;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy knihy zločinů – odhalení krádeže a cesta k usmíření.
 */
class CrimeLogTest {

    private static final String WORLD = "world";
    private static final BlockPos CHEST = new BlockPos(10, 64, 10);

    private final UUID thief = new UUID(0, 1);
    private final UUID victim = new UUID(0, 2);

    @Test
    void neodhalenaKradezNetiziSvedomi() {
        CrimeLog log = new CrimeLog();
        log.reportTheft(WORLD, CHEST, thief, "Zloděj");
        assertTrue(log.pendingAmends(thief).isEmpty(),
                "dokud o krádeži nikdo neví, není se komu omlouvat");
    }

    @Test
    void odhalenaKradezCekaNaOdcineni() {
        CrimeLog log = new CrimeLog();
        log.reportTheft(WORLD, CHEST, thief, "Zloděj");
        assertTrue(log.discoverTheft(WORLD, CHEST, victim).isPresent());

        var amends = log.pendingAmends(thief).orElseThrow();
        assertEquals(victim, amends.victim());

        // Vyrovnání (dar předán) – podruhé se už neřeší.
        log.settleAmends(amends);
        assertTrue(log.pendingAmends(thief).isEmpty(), "co je předáno, je předáno");
    }

    @Test
    void odhaleniSeNehlasiOpakovaneAniPachateli() {
        CrimeLog log = new CrimeLog();
        log.reportTheft(WORLD, CHEST, thief, "Zloděj");
        assertTrue(log.discoverTheft(WORLD, CHEST, thief).isEmpty(),
                "vlastní krádež se nehlásí");
        assertTrue(log.discoverTheft(WORLD, CHEST, victim).isPresent());
        assertTrue(log.discoverTheft(WORLD, CHEST, victim).isEmpty(),
                "podruhé se nezuří");
    }

    @Test
    void cizisKrivdySeNepletouDoSvedomi() {
        CrimeLog log = new CrimeLog();
        log.reportTheft(WORLD, CHEST, thief, "Zloděj");
        log.discoverTheft(WORLD, CHEST, victim);
        assertTrue(log.pendingAmends(victim).isEmpty(),
                "oběť nemá co odčiňovat");
    }
}
