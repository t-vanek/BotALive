package dev.botalive.core.economy;

import org.bukkit.Material;

import java.util.Map;

/**
 * Ceník trhu mezi boty – čistá logika, jednotkově testovatelná.
 *
 * <p>Základní ceny vycházejí z obchodu s vesničany (chléb ~2, železo ~6);
 * chamtivost prodejce cenu zvedá, kamarádi dostávají slevu. Prodává se jen
 * malý košík komodit – jídlo a základní suroviny.</p>
 */
public final class MarketPrices {

    private static final Map<Material, Double> BASE = Map.of(
            Material.BREAD, 2.0,
            Material.COOKED_BEEF, 3.0,
            Material.COOKED_PORKCHOP, 3.0,
            Material.COOKED_CHICKEN, 2.5,
            Material.COOKED_MUTTON, 2.5,
            Material.BAKED_POTATO, 1.5,
            Material.WHEAT, 0.8,
            Material.COAL, 1.5,
            Material.IRON_INGOT, 6.0);

    private MarketPrices() {
    }

    /** @return {@code true} pokud se materiál na trhu obchoduje */
    public static boolean sellable(Material material) {
        return BASE.containsKey(material);
    }

    /**
     * Cena nabídky.
     *
     * @param material materiál
     * @param count    počet kusů
     * @param greed    chamtivost prodejce (0–1; zvedá cenu až o ~40 %)
     * @return cena zaokrouhlená na půlky, minimálně 1
     */
    public static double price(Material material, int count, double greed) {
        double base = BASE.getOrDefault(material, 1.0);
        return round(base * count * (0.85 + greed * 0.4));
    }

    /**
     * @param price plná cena
     * @return kamarádská cena (sleva 20 %)
     */
    public static double friendly(double price) {
        return round(price * 0.8);
    }

    private static double round(double value) {
        return Math.max(1.0, Math.round(value * 2) / 2.0);
    }
}
