package dev.botalive.core.network;

import dev.botalive.core.config.BotAliveConfig;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.network.ClientSession;
import org.geysermc.mcprotocollib.network.event.session.SessionListener;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.network.session.ClientNetworkSession;
import org.geysermc.mcprotocollib.protocol.MinecraftConstants;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Jedno klientské připojení bota k serveru (MCProtocolLib).
 *
 * <p>Bot je plnohodnotný Minecraft klient: prochází handshake, login i
 * konfigurační fází protokolu (tu obsluhují výchozí listenery knihovny včetně
 * keep-alive a known-packs). Identita je offline-mode profil – UUID odvozené
 * ze jména stejně, jako to dělá server v offline režimu, takže bot má na
 * serveru stabilní identitu napříč restarty.</p>
 *
 * <p>Každé připojení má vlastní jednovláknový executor pro zpracování paketů
 * postavený na virtuálním vlákně: garantuje pořadí paketů a škáluje na stovky
 * botů bez stovek platformních vláken.</p>
 */
public final class BotConnection {

    private static final Logger LOG = LoggerFactory.getLogger(BotConnection.class);

    private final String botName;
    private final UUID botId;
    private final BotAliveConfig.Network config;
    private final SessionListener listener;

    private volatile ClientNetworkSession session;
    private volatile ExecutorService packetExecutor;
    /** Reference zavíraného spojení pro {@link #awaitQuiesce} (vypnutí pluginu). */
    private volatile ClientNetworkSession closingSession;
    private volatile ExecutorService closingExecutor;

    /**
     * @param botName  jméno bota (login jméno)
     * @param botId    offline-mode UUID bota
     * @param config   síťová konfigurace
     * @param listener session listener (routing paketů)
     */
    public BotConnection(String botName, UUID botId, BotAliveConfig.Network config, SessionListener listener) {
        this.botName = botName;
        this.botId = botId;
        this.config = config;
        this.listener = listener;
    }

    /**
     * Naváže nové spojení. Předchozí session musí být odpojená.
     *
     * @param host cílová adresa serveru
     * @param port cílový port serveru
     */
    public synchronized void connect(String host, int port) {
        disconnectQuietly("reconnect");

        MinecraftProtocol protocol = new MinecraftProtocol(new GameProfile(botId, botName), null);

        // Jednovláknový executor na virtuálním vlákně: pořadí paketů zachováno,
        // cena vlákna zanedbatelná.
        ExecutorService executor = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("BotAlive-Net-" + botName).factory());
        this.packetExecutor = executor;

        ClientNetworkSession newSession = new ClientNetworkSession(
                new InetSocketAddress(host, port), protocol, executor, null, null);

        // Offline-mode klient: žádná autentizace proti session serverům Mojangu.
        newSession.setFlag(MinecraftConstants.SHOULD_AUTHENTICATE, false);
        // Keep-alive odbavuje knihovna sama (nezávisle na tick smyčce bota).
        newSession.setFlag(MinecraftConstants.AUTOMATIC_KEEP_ALIVE_MANAGEMENT, true);
        // Follow transfers – kdyby server bota přesměroval (velocity apod.).
        newSession.setFlag(MinecraftConstants.FOLLOW_TRANSFERS, true);
        // Klientský world model potřebuje plná registry data (min_y/height
        // dimenzí) – prázdná known-packs odpověď donutí server je poslat celá.
        if (config.packetWorldModel()) {
            newSession.setFlag(MinecraftConstants.SEND_BLANK_KNOWN_PACKS_RESPONSE, true);
        }

        newSession.addListener(listener);
        this.session = newSession;

        LOG.debug("[{}] Připojuji na {}:{}", botName, host, port);
        newSession.connect(false);
    }

    /**
     * @return {@code true} pokud je spojení navázané
     */
    public boolean connected() {
        ClientSession current = session;
        return current != null && current.isConnected();
    }

    /**
     * Odešle paket, pokud je spojení aktivní.
     *
     * @param packet odchozí paket
     */
    public void send(Packet packet) {
        ClientSession current = session;
        if (current != null && current.isConnected()) {
            current.send(packet);
        }
    }

    /**
     * Odpojí bota od serveru.
     *
     * @param reason důvod (do logu serveru)
     */
    public synchronized void disconnect(String reason) {
        disconnectQuietly(reason);
    }

    private void disconnectQuietly(String reason) {
        ClientNetworkSession current = session;
        session = null;
        if (current != null) {
            closingSession = current;
        }
        if (current != null && current.isConnected()) {
            try {
                current.disconnect(reason);
            } catch (Exception e) {
                LOG.debug("[{}] Chyba při odpojování: {}", botName, e.toString());
            }
        }
        ExecutorService executor = packetExecutor;
        packetExecutor = null;
        if (executor != null) {
            closingExecutor = executor;
            executor.shutdown();
        }
    }

    /**
     * Počká, až odpojené spojení skutečně zhasne – session zavřená a paketový
     * executor ukončený. Volá se při vypnutí pluginu PŘED návratem z
     * {@code onDisable}: Paper pak zavře plugin classloader a síťová vlákna,
     * která ještě dobíhají, by líně donačítala (relokované) třídy ze
     * zavřeného jaru – shutdown zaplaví „zip file error" a může utnout i
     * poslední write-behind zápis.
     *
     * @param millis maximální čekání
     */
    public void awaitQuiesce(long millis) {
        long deadline = System.currentTimeMillis() + millis;
        ClientNetworkSession closing = closingSession;
        closingSession = null;
        while (closing != null && closing.isConnected()
                && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
        ExecutorService executor = closingExecutor;
        closingExecutor = null;
        if (executor != null) {
            try {
                long remaining = Math.max(50, deadline - System.currentTimeMillis());
                if (!executor.awaitTermination(remaining, TimeUnit.MILLISECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
