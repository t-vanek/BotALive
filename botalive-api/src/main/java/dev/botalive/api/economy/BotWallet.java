package dev.botalive.api.economy;

/**
 * Peněženka bota – jednoduchá vnitřní ekonomika.
 *
 * <p>Boti získávají prostředky činností (těžba, sběr) a utrácejí je při obchodech.
 * Zůstatky jsou persistentní. Implementace je thread-safe.</p>
 */
public interface BotWallet {

    /**
     * @return aktuální zůstatek
     */
    double balance();

    /**
     * Přičte prostředky.
     *
     * @param amount kladná částka
     * @param reason důvod (do transakčního logu)
     */
    void deposit(double amount, String reason);

    /**
     * Odečte prostředky, pokud je dostatečný zůstatek.
     *
     * @param amount kladná částka
     * @param reason důvod (do transakčního logu)
     * @return {@code true} při úspěchu, {@code false} při nedostatku prostředků
     */
    boolean withdraw(double amount, String reason);
}
