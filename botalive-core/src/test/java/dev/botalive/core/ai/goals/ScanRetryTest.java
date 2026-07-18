package dev.botalive.core.ai.goals;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy opakování skenu přes studenou chunk cache.
 */
class ScanRetryTest {

    @Test
    void prvniSkenBeziHnedBezCekani() {
        ScanRetry retry = new ScanRetry(3, 25);

        assertFalse(retry.waiting(), "před prvním skenem se nečeká");
    }

    @Test
    void poNeuspechuCekaAZkusiZnovu() {
        ScanRetry retry = new ScanRetry(3, 5);

        assertTrue(retry.shouldRetry(), "první neúspěch → zkusit znovu");
        assertTrue(retry.firstFailure(), "právě proběhl první neúspěch");

        for (int i = 0; i < 5; i++) {
            assertTrue(retry.waiting(), "tick " + i + " se má čekat");
        }
        assertFalse(retry.waiting(), "po odčekání se skenuje znovu");

        assertTrue(retry.shouldRetry(), "druhý neúspěch → ještě jeden pokus");
        assertFalse(retry.firstFailure(), "druhý neúspěch už není první");
    }

    @Test
    void poVycerpaniPokusuToVzda() {
        ScanRetry retry = new ScanRetry(3, 5);

        assertTrue(retry.shouldRetry(), "pokus 1 → retry");
        assertTrue(retry.shouldRetry(), "pokus 2 → retry");
        assertFalse(retry.shouldRetry(), "pokus 3 (= max) → vzdát");
    }

    @Test
    void resetZacneNovyCyklus() {
        ScanRetry retry = new ScanRetry(2, 5);
        assertTrue(retry.shouldRetry());
        assertFalse(retry.shouldRetry(), "vyčerpáno");

        retry.reset();

        assertFalse(retry.waiting(), "po resetu se nečeká");
        assertTrue(retry.shouldRetry(), "po resetu se počítá od začátku");
        assertTrue(retry.firstFailure(), "po resetu je neúspěch zase první");
    }
}
