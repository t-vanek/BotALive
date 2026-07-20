package dev.botalive.core.gateway;

import dev.botalive.core.bot.BotManagerImpl;
import dev.botalive.core.config.BotAliveConfig;
import net.kyori.adventure.text.Component;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetAddress;
import java.util.UUID;

/**
 * Server-side pojistka proti zneužití identity bota při přihlášení.
 *
 * <p>Toto je vlastní ověřovací proces v akci: na offline-mode serveru se kdokoli
 * může připojit pod libovolným jménem. Pojistka proto u každého přihlášení, které
 * používá jméno spravovaného bota, vyžaduje platnou čekající autorizaci vydanou
 * {@link CredentialAuthority} bezprostředně předtím, než se bot připojil (a dle
 * konfigurace i to, že spojení přichází z lokálního zdroje). Přihlášení hráčů
 * s <em>vlastními</em> jmény se nedotýká – jede přes ni beze změny.</p>
 *
 * <p>Běží na {@link EventPriority#LOWEST} a je záměrně <b>fail-open</b>: jakákoli
 * neočekávaná chyba pojistky nesmí rozbít přihlašování na serveru, proto se
 * v takovém případě přihlášení povolí a chyba jen zaloguje. Odmítá se pouze
 * explicitní selhání ověření identity bota.</p>
 */
public final class BotLoginGuard implements Listener {

    private static final Logger LOG = LoggerFactory.getLogger(BotLoginGuard.class);

    private final BotManagerImpl botManager;
    private final CredentialAuthority authority;
    private final BotAliveConfig.Gateway config;

    /**
     * @param botManager manager botů (rozpoznání spravovaných identit)
     * @param authority  ověřovací autorita (čekající autorizace)
     * @param config     konfigurace gateway
     */
    public BotLoginGuard(BotManagerImpl botManager, CredentialAuthority authority,
                         BotAliveConfig.Gateway config) {
        this.botManager = botManager;
        this.authority = authority;
        this.config = config;
    }

    /** Ověří přihlášení pod jménem bota; cizí jména propustí beze změny. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onPreLogin(AsyncPlayerPreLoginEvent event) {
        try {
            if (!config.enabled() || !config.enforcePreLogin()) {
                return;
            }
            String name = event.getName();
            // Jen jména spravovaných botů jsou chráněná – skuteční hráči projdou.
            if (botManager.byName(name).isEmpty()) {
                return;
            }
            UUID presentedId = event.getUniqueId();

            if (config.restrictSource() && !isLocalSource(event.getAddress())) {
                deny(event, name, "ze vzdáleného zdroje " + describe(event.getAddress()));
                return;
            }

            CredentialAuthority.Authorization auth = authority.authorizeLogin(name, presentedId);
            if (!auth.allowed()) {
                deny(event, name, "bez platné čekající autorizace (" + auth.result() + ")");
                return;
            }
            if (auth.expectedId() != null && !auth.expectedId().equals(presentedId)) {
                LOG.warn("Bot '{}' se přihlásil s UUID {}, čekáno {} – povoluji, ale UUID nesedí.",
                        name, presentedId, auth.expectedId());
            }
        } catch (RuntimeException e) {
            // Fail-open: neznámá chyba pojistky nesmí rozbít přihlašování na serveru.
            LOG.warn("Chyba přihlašovací pojistky, přihlášení povoluji: {}", e.toString());
        }
    }

    private void deny(AsyncPlayerPreLoginEvent event, String name, String reason) {
        LOG.warn("Odmítám přihlášení pod jménem bota '{}' {} – vypadá to jako pokus o zneužití "
                + "identity.", name, reason);
        event.disallow(AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                Component.text("Toto jméno patří botovi BotAlive a je chráněné."));
    }

    /**
     * Zdroj považovaný za lokální (a tedy důvěryhodný pro připojení bota):
     * loopback, privátní (site-local), link-local nebo wildcard adresa. Veřejné
     * internetové adresy sem nepatří – to je typický vektor zneužití.
     *
     * @param addr zdrojová adresa spojení
     * @return {@code true} pro lokální zdroj
     */
    static boolean isLocalSource(InetAddress addr) {
        return addr != null && (addr.isLoopbackAddress() || addr.isSiteLocalAddress()
                || addr.isLinkLocalAddress() || addr.isAnyLocalAddress());
    }

    private static String describe(InetAddress addr) {
        return addr == null ? "(neznámý)" : addr.getHostAddress();
    }
}
