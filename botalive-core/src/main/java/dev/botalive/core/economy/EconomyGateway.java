package dev.botalive.core.economy;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Brána k externí (serverové) ekonomice.
 *
 * <p>Odděluje {@link VaultBotWallet} od konkrétního Vault API – peněženka
 * je díky tomu testovatelná bez Bukkitu. Všechny operace jsou asynchronní:
 * ekonomické pluginy nejsou vlákno-bezpečné, implementace je maršáluje na
 * hlavní vlákno serveru.</p>
 */
public interface EconomyGateway {

    /** @return název poskytovatele ekonomiky (pro log) */
    String providerName();

    /**
     * Založí účet bota, pokud neexistuje, a připíše počáteční zůstatek.
     *
     * @param botId           UUID bota
     * @param startingBalance počáteční vklad při založení účtu
     * @return aktuální zůstatek po případném založení
     */
    CompletableFuture<Double> ensureAccount(UUID botId, double startingBalance);

    /**
     * @param botId UUID bota
     * @return aktuální zůstatek v serverové ekonomice
     */
    CompletableFuture<Double> balance(UUID botId);

    /**
     * Připíše prostředky.
     *
     * @param botId  UUID bota
     * @param amount kladná částka
     * @return {@code true}, pokud poskytovatel transakci přijal
     */
    CompletableFuture<Boolean> deposit(UUID botId, double amount);

    /**
     * Odečte prostředky.
     *
     * @param botId  UUID bota
     * @param amount kladná částka
     * @return {@code true}, pokud poskytovatel transakci přijal
     */
    CompletableFuture<Boolean> withdraw(UUID botId, double amount);
}
