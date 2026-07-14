package dev.botalive.core.economy;

import dev.botalive.api.economy.BotWallet;
import dev.botalive.core.persistence.BotRepository;

import java.util.UUID;

/**
 * Peněženka bota s okamžitým in-memory stavem a asynchronní persistencí.
 *
 * <p>Zůstatek je autoritativní v paměti (AI potřebuje synchronní odpovědi),
 * každá změna se propíše do databáze včetně transakčního logu.</p>
 */
public final class BotWalletImpl implements BotWallet {

    private final UUID botId;
    private final BotRepository repository;
    private final boolean enabled;

    private double balance;

    /**
     * @param botId          UUID bota
     * @param repository     repozitář
     * @param initialBalance zůstatek načtený z databáze
     * @param enabled        zda je ekonomika zapnutá v konfiguraci
     */
    public BotWalletImpl(UUID botId, BotRepository repository, double initialBalance, boolean enabled) {
        this.botId = botId;
        this.repository = repository;
        this.balance = initialBalance;
        this.enabled = enabled;
    }

    @Override
    public synchronized double balance() {
        return balance;
    }

    @Override
    public synchronized void deposit(double amount, String reason) {
        if (!enabled || amount <= 0) {
            return;
        }
        balance += amount;
        repository.saveWallet(botId, balance, amount, reason);
    }

    @Override
    public synchronized boolean withdraw(double amount, String reason) {
        if (!enabled || amount <= 0 || balance < amount) {
            return false;
        }
        balance -= amount;
        repository.saveWallet(botId, balance, -amount, reason);
        return true;
    }
}
