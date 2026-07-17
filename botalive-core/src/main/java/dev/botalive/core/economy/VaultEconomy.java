package dev.botalive.core.economy;

import dev.botalive.core.scheduler.MainThreadBridge;
import net.milkbowl.vault.economy.Economy;
import net.milkbowl.vault.economy.EconomyResponse;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.plugin.RegisteredServiceProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Adaptér serverové ekonomiky přes Vault.
 *
 * <p>Vault je volitelná závislost ({@code softdepend}) – třídy {@code net.milkbowl.*}
 * dodává až plugin Vault za běhu. Proto se instance vyrábí výhradně přes
 * {@link #detect(MainThreadBridge)}, které nepřítomnost Vaultu (nebo chybějícího
 * ekonomického poskytovatele) ošetří vrácením {@code Optional.empty()}.</p>
 *
 * <p>Ekonomické pluginy nejsou vlákno-bezpečné; každé volání se maršáluje na
 * globální region (hlavní vlákno) přes {@link MainThreadBridge}.</p>
 */
public final class VaultEconomy implements EconomyGateway {

    private static final Logger LOG = LoggerFactory.getLogger(VaultEconomy.class);

    private final Economy economy;
    private final MainThreadBridge bridge;

    private VaultEconomy(Economy economy, MainThreadBridge bridge) {
        this.economy = economy;
        this.bridge = bridge;
    }

    /**
     * Najde ekonomického poskytovatele registrovaného ve Vaultu.
     *
     * @param bridge most na hlavní vlákno
     * @return adaptér, nebo empty pokud Vault či poskytovatel chybí
     */
    public static Optional<VaultEconomy> detect(MainThreadBridge bridge) {
        if (Bukkit.getPluginManager().getPlugin("Vault") == null) {
            return Optional.empty();
        }
        try {
            RegisteredServiceProvider<Economy> registration =
                    Bukkit.getServicesManager().getRegistration(Economy.class);
            if (registration == null) {
                LOG.info("Vault je nainstalovaný, ale žádný ekonomický plugin se v něm "
                        + "neregistroval – boti použijí interní peněženku");
                return Optional.empty();
            }
            return Optional.of(new VaultEconomy(registration.getProvider(), bridge));
        } catch (NoClassDefFoundError e) {
            // Vault plugin existuje, ale nedodal očekávané API (exotická verze).
            LOG.warn("Vault API není dostupné ({}) – boti použijí interní peněženku",
                    e.toString());
            return Optional.empty();
        }
    }

    @Override
    public String providerName() {
        return economy.getName();
    }

    @Override
    public CompletableFuture<Double> ensureAccount(UUID botId, double startingBalance) {
        return onMain(() -> {
            OfflinePlayer player = Bukkit.getOfflinePlayer(botId);
            if (!economy.hasAccount(player)) {
                if (!economy.createPlayerAccount(player)) {
                    return 0.0;
                }
                if (startingBalance > 0) {
                    economy.depositPlayer(player, startingBalance);
                }
            }
            return economy.getBalance(player);
        });
    }

    @Override
    public CompletableFuture<Double> balance(UUID botId) {
        return onMain(() -> economy.getBalance(Bukkit.getOfflinePlayer(botId)));
    }

    @Override
    public CompletableFuture<Boolean> deposit(UUID botId, double amount) {
        return onMain(() -> {
            EconomyResponse response = economy.depositPlayer(Bukkit.getOfflinePlayer(botId), amount);
            return response.transactionSuccess();
        });
    }

    @Override
    public CompletableFuture<Boolean> withdraw(UUID botId, double amount) {
        return onMain(() -> {
            EconomyResponse response = economy.withdrawPlayer(Bukkit.getOfflinePlayer(botId), amount);
            return response.transactionSuccess();
        });
    }

    /** Vyhodnotí dotaz na globálním regionu (hlavním vlákně). */
    private <T> CompletableFuture<T> onMain(Supplier<T> query) {
        CompletableFuture<T> future = new CompletableFuture<>();
        bridge.runGlobal(() -> {
            try {
                future.complete(query.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }
}
