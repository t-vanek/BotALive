package dev.botalive.core.economy;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy ceníku trhu.
 */
class MarketPricesTest {

    @Test
    void chamtivostZvedaCenu() {
        double modest = MarketPrices.price(Material.BREAD, 5, 0.0);
        double greedy = MarketPrices.price(Material.BREAD, 5, 1.0);
        assertTrue(greedy > modest);
    }

    @Test
    void kamaradskaCenaJeNizsi() {
        double full = MarketPrices.price(Material.IRON_INGOT, 6, 0.5);
        assertTrue(MarketPrices.friendly(full) < full);
    }

    @Test
    void cenaJeVzdyKladnaAZaokrouhlena() {
        double price = MarketPrices.price(Material.WHEAT, 1, 0.0);
        assertTrue(price >= 1.0);
        assertEquals(0, (price * 2) % 1, 1e-9, "cena je na půlky");
    }

    @Test
    void obchodujeSeJenKosikKomodit() {
        assertTrue(MarketPrices.sellable(Material.BREAD));
        assertTrue(MarketPrices.sellable(Material.IRON_INGOT));
        assertFalse(MarketPrices.sellable(Material.DIAMOND));
        assertFalse(MarketPrices.sellable(Material.DIRT));
    }
}
