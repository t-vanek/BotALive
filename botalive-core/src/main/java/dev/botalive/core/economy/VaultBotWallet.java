package dev.botalive.core.economy;

import dev.botalive.api.economy.BotWallet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.UUID;

/**
 * Peněženka bota napojená na serverovou ekonomiku (Vault).
 *
 * <p>AI potřebuje synchronní odpovědi z vlastních vláken, ale ekonomické
 * pluginy běží na hlavním vlákně. Peněženka proto drží lokální zrcadlo
 * zůstatku: operace se aplikují okamžitě na zrcadlo (optimisticky) a
 * write-through se maršáluje přes {@link EconomyGateway}; po každé dokončené
 * operaci se zrcadlo srovná se skutečným zůstatkem, takže se propíšou i vnější
 * změny (hráč pošle botovi {@code /pay}, admin {@code /eco set}...).</p>
 *
 * <p>Transakce se dál zapisují i do databáze BotAlive – zůstává transakční
 * historie a statistiky, autoritou nad zůstatkem je ale serverová ekonomika.</p>
 */
public final class VaultBotWallet implements BotWallet {

    private static final Logger LOG = LoggerFactory.getLogger(VaultBotWallet.class);
    /** Jak často (ms) si při čtení zůstatku vyžádat srovnání s ekonomikou. */
    private static final long SYNC_INTERVAL_MS = 30_000;

    /** Zápis transakce do historie (implementuje {@code BotRepository::saveWallet}). */
    @FunctionalInterface
    public interface TransactionLog {
        /**
         * @param botId   UUID bota
         * @param balance zůstatek po transakci
         * @param amount  pohyb (+vklad / −výběr)
         * @param reason  důvod transakce
         */
        void record(UUID botId, double balance, double amount, String reason);
    }

    private final UUID botId;
    private final EconomyGateway gateway;
    private final TransactionLog log;

    private volatile double cached;
    private volatile long lastSyncMs;

    /**
     * @param botId           UUID bota
     * @param gateway         brána na serverovou ekonomiku
     * @param log             transakční historie (typicky DB repozitář)
     * @param startingBalance počáteční vklad při prvním založení účtu
     */
    public VaultBotWallet(UUID botId, EconomyGateway gateway, TransactionLog log,
                          double startingBalance) {
        this.botId = botId;
        this.gateway = gateway;
        this.log = log;
        gateway.ensureAccount(botId, startingBalance).whenComplete((balance, error) -> {
            if (error != null) {
                LOG.warn("Založení účtu bota {} v ekonomice selhalo: {}", botId, error.toString());
            } else if (balance != null) {
                applySync(balance);
            }
        });
    }

    @Override
    public double balance() {
        // Líné srovnání s ekonomikou – zachytí vnější změny (/pay, /eco set).
        long now = System.currentTimeMillis();
        if (now - lastSyncMs > SYNC_INTERVAL_MS) {
            lastSyncMs = now;
            resync();
        }
        return cached;
    }

    @Override
    public synchronized void deposit(double amount, String reason) {
        if (amount <= 0) {
            return;
        }
        cached += amount;
        log.record(botId, cached, amount, reason);
        gateway.deposit(botId, amount).whenComplete((ok, error) -> {
            if (error != null || ok == null || !ok) {
                LOG.warn("Vklad {} pro bota {} ekonomika odmítla ({})", amount, botId,
                        error != null ? error.toString() : "zamítnuto");
            }
            resync();
        });
    }

    @Override
    public synchronized boolean withdraw(double amount, String reason) {
        if (amount <= 0 || cached < amount) {
            return false;
        }
        cached -= amount;
        log.record(botId, cached, -amount, reason);
        gateway.withdraw(botId, amount).whenComplete((ok, error) -> {
            if (error != null || ok == null || !ok) {
                LOG.warn("Výběr {} pro bota {} ekonomika odmítla ({})", amount, botId,
                        error != null ? error.toString() : "zamítnuto");
            }
            resync();
        });
        return true;
    }

    /**
     * Vynutí okamžité (asynchronní) srovnání zrcadla se serverovou ekonomikou.
     * Používá prodej hráčům – příchozí {@code /pay} se na zrcadle projeví až
     * po resyncu a 30s líné okno je na hlídání platby moc dlouhé.
     */
    public void refresh() {
        lastSyncMs = System.currentTimeMillis();
        resync();
    }

    /** Asynchronně srovná zrcadlo se skutečným zůstatkem v ekonomice. */
    private void resync() {
        gateway.balance(botId).whenComplete((balance, error) -> {
            if (error == null && balance != null) {
                applySync(balance);
            }
        });
    }

    private synchronized void applySync(double balance) {
        this.cached = balance;
        this.lastSyncMs = System.currentTimeMillis();
    }
}
