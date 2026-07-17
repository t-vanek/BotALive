package dev.botalive.core.economy;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy Vault peněženky nad falešnou (synchronní, in-memory) ekonomikou.
 */
class VaultBotWalletTest {

    /** In-memory ekonomika – dokončuje futures synchronně, deterministicky. */
    private static final class FakeEconomy implements EconomyGateway {
        double balance;
        boolean accountExists;
        boolean rejectAll;

        @Override
        public String providerName() {
            return "FakeEconomy";
        }

        @Override
        public CompletableFuture<Double> ensureAccount(UUID botId, double startingBalance) {
            if (!accountExists) {
                accountExists = true;
                balance += startingBalance;
            }
            return CompletableFuture.completedFuture(balance);
        }

        @Override
        public CompletableFuture<Double> balance(UUID botId) {
            return CompletableFuture.completedFuture(balance);
        }

        @Override
        public CompletableFuture<Boolean> deposit(UUID botId, double amount) {
            if (rejectAll) {
                return CompletableFuture.completedFuture(false);
            }
            balance += amount;
            return CompletableFuture.completedFuture(true);
        }

        @Override
        public CompletableFuture<Boolean> withdraw(UUID botId, double amount) {
            if (rejectAll || balance < amount) {
                return CompletableFuture.completedFuture(false);
            }
            balance -= amount;
            return CompletableFuture.completedFuture(true);
        }
    }

    private static final UUID BOT = UUID.randomUUID();

    @Test
    void zalozeniUctuPripiseStartovniZustatek() {
        FakeEconomy eco = new FakeEconomy();
        VaultBotWallet wallet = new VaultBotWallet(BOT, eco, (a, b, c, d) -> { }, 100.0);
        assertEquals(100.0, eco.balance, 1e-9, "účet má být založen se startovním vkladem");
        assertEquals(100.0, wallet.balance(), 1e-9, "zrcadlo má odrážet zůstatek z ekonomiky");
    }

    @Test
    void existujiciUcetSeNezaklada() {
        FakeEconomy eco = new FakeEconomy();
        eco.accountExists = true;
        eco.balance = 250.0;
        VaultBotWallet wallet = new VaultBotWallet(BOT, eco, (a, b, c, d) -> { }, 100.0);
        assertEquals(250.0, eco.balance, 1e-9, "existující zůstatek se nesmí měnit");
        assertEquals(250.0, wallet.balance(), 1e-9);
    }

    @Test
    void vkladJdePresEkonomikuAZapiseTransakci() {
        FakeEconomy eco = new FakeEconomy();
        List<Double> movements = new ArrayList<>();
        VaultBotWallet wallet = new VaultBotWallet(BOT, eco,
                (id, balance, amount, reason) -> movements.add(amount), 0.0);
        wallet.deposit(40.0, "těžba");
        assertEquals(40.0, eco.balance, 1e-9, "vklad se má propsat do ekonomiky");
        assertEquals(40.0, wallet.balance(), 1e-9);
        assertEquals(List.of(40.0), movements, "pohyb má být v transakčním logu");
    }

    @Test
    void vyberSelzePriNedostatku() {
        FakeEconomy eco = new FakeEconomy();
        VaultBotWallet wallet = new VaultBotWallet(BOT, eco, (a, b, c, d) -> { }, 30.0);
        assertFalse(wallet.withdraw(50.0, "obchod"), "výběr nad zůstatek má selhat");
        assertEquals(30.0, eco.balance, 1e-9, "zůstatek se nesmí změnit");
        assertTrue(wallet.withdraw(30.0, "obchod"));
        assertEquals(0.0, eco.balance, 1e-9);
    }

    @Test
    void odmitnutaTransakceSeSrovnaSEkonomikou() {
        FakeEconomy eco = new FakeEconomy();
        VaultBotWallet wallet = new VaultBotWallet(BOT, eco, (a, b, c, d) -> { }, 100.0);
        eco.rejectAll = true;
        wallet.deposit(50.0, "test");
        // Ekonomika vklad odmítla → resync vrátí zrcadlo na skutečný zůstatek.
        assertEquals(100.0, eco.balance, 1e-9);
        assertEquals(100.0, wallet.balance(), 1e-9,
                "po odmítnuté transakci se zrcadlo musí srovnat se skutečností");
    }

    @Test
    void zaporneCastkySeIgnoruji() {
        FakeEconomy eco = new FakeEconomy();
        VaultBotWallet wallet = new VaultBotWallet(BOT, eco, (a, b, c, d) -> { }, 100.0);
        wallet.deposit(-5.0, "podvod");
        assertFalse(wallet.withdraw(-5.0, "podvod"));
        assertEquals(100.0, eco.balance, 1e-9);
    }
}
