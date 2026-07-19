package dev.botalive.core.economy;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy mzdového ceníku najímání – povaha hýbe cenou, kamarádi mají slevu,
 * flákačům se nechce.
 */
class EmploymentPricesTest {

    @Test
    void neutralniPovahaNeutralniCena() {
        // 12/den × 2 dny × 1.05 (greed 0.5) × 1.025 (laziness/helpfulness 0.5)
        assertEquals(26.0, EmploymentPrices.wage(12.0, 2, 0.5, 0.5, 0.5, false), 1e-9);
    }

    @Test
    void chamtivyJeDrazsiNezSkromny() {
        double greedy = EmploymentPrices.wage(12.0, 3, 1.0, 0.5, 0.5, false);
        double modest = EmploymentPrices.wage(12.0, 3, 0.0, 0.5, 0.5, false);
        assertTrue(greedy > modest);
    }

    @Test
    void kamaradMaSlevu() {
        double stranger = EmploymentPrices.wage(18.0, 2, 0.5, 0.5, 0.5, false);
        double friend = EmploymentPrices.wage(18.0, 2, 0.5, 0.5, 0.5, true);
        assertTrue(friend < stranger);
        assertEquals(stranger * 0.8, friend, 0.5); // sleva ~20 % (zaokrouhlení)
    }

    @Test
    void mzdaNikdyNeklesnePodJedna() {
        assertEquals(1.0, EmploymentPrices.wage(0.5, 1, 0.0, 0.0, 1.0, true), 1e-9);
    }

    @Test
    void zaokrouhlujeSeNaPulky() {
        double wage = EmploymentPrices.wage(11.3, 1, 0.37, 0.21, 0.66, false);
        assertEquals(0.0, (wage * 2) % 1, 1e-9);
    }

    @Test
    void bezZajmuOPeniceIPomocSeNechce() {
        assertFalse(EmploymentPrices.willing(0.1, 0.2, false));
        assertTrue(EmploymentPrices.willing(0.8, 0.1, false), "chamtivce lákají peníze");
        assertTrue(EmploymentPrices.willing(0.1, 0.7, false), "ochotného láká pomoc");
        assertTrue(EmploymentPrices.willing(0.0, 0.0, true), "kamarádovi se neodmítá");
    }
}
