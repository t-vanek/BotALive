package dev.botalive.core.economy;

/**
 * Mzdy najímaných botů – čistá logika, jednotkově testovatelná.
 *
 * <p>Základ je denní sazba podle druhu práce (config); povaha bota ji
 * upravuje stejným duchem jako ceník trhu: chamtivý si řekne víc, líný si
 * nechá zaplatit za přemáhání, ochotný sleví – a kamarádi zaměstnavatele
 * dostávají kamarádskou slevu jako na trhu.</p>
 */
public final class EmploymentPrices {

    /** Kamarádská sleva (shodná s trhem). */
    private static final double FRIEND_DISCOUNT = 0.8;

    private EmploymentPrices() {
    }

    /**
     * Mzda za celou smlouvu.
     *
     * @param perDay      základní denní sazba (config podle druhu práce)
     * @param days        délka smlouvy ve dnech
     * @param greed       chamtivost bota (0–1; zvedá cenu až o ~40 %)
     * @param laziness    lenost (0–1; přemáhání stojí až +20 %)
     * @param helpfulness ochota (0–1; sleva až −15 %)
     * @param friend      je zaměstnavatel botův kamarád? (sleva 20 %)
     * @return mzda zaokrouhlená na půlky, minimálně 1
     */
    public static double wage(double perDay, int days, double greed,
                              double laziness, double helpfulness, boolean friend) {
        double price = perDay * days
                * (0.85 + greed * 0.4)
                * (1.0 + laziness * 0.2 - helpfulness * 0.15);
        if (friend) {
            price *= FRIEND_DISCOUNT;
        }
        return round(price);
    }

    /**
     * Má bot vůbec zájem o práci? Bez kamarádství rozhoduje kombinace
     * ochoty (práce pro druhé) a chamtivosti (peníze lákají) – komu chybí
     * obojí, tomu se nechce.
     *
     * @param greed       chamtivost (0–1)
     * @param helpfulness ochota (0–1)
     * @param friend      je zaměstnavatel kamarád?
     * @return {@code true}, pokud bot nabídku zváží
     */
    public static boolean willing(double greed, double helpfulness, boolean friend) {
        return friend || greed * 0.5 + helpfulness * 0.5 >= 0.25;
    }

    private static double round(double value) {
        return Math.max(1.0, Math.round(value * 2) / 2.0);
    }
}
