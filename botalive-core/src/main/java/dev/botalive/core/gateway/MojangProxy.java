package dev.botalive.core.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

/**
 * Proxy na skutečné Mojang session API kvůli ověření reálných hráčů.
 *
 * <p>{@link MojangGatewayServer} je autorita jen pro identitu <em>botů</em>.
 * Když online-mode server nasměrovaný na gateway dostane dotaz
 * {@code hasJoined} pro jméno, které gateway nezná (typicky reálný hráč),
 * přepošle ho sem a tato třída ho relayuje na skutečný Mojang. Díky tomu
 * projdou zároveň:</p>
 * <ul>
 *   <li><b>boti</b> – ověřeni lokálně přes HMAC ({@link CredentialAuthority}),</li>
 *   <li><b>reální hráči</b> – ověřeni skutečným Mojangem přes tuto proxy,</li>
 * </ul>
 * <p>zatímco cizí falešný bot neprojde ani jednou cestou (nemá pověření
 * gateway ani platný Mojang účet).</p>
 *
 * <p>Odpověď Mojangu se relayuje <em>doslova</em> (stav i tělo včetně
 * podepsaných {@code properties} se skinem), jinak by hráčům nefungovaly
 * textury a {@code prevent-proxy-connections} by je odmítl.</p>
 *
 * <p>Volání běží na virtuálních vláknech HTTP gateway, takže blokující
 * {@link HttpClient#send} nikdy nezdrží herní ani tick vlákna.</p>
 */
public final class MojangProxy {

    private static final Logger LOG = LoggerFactory.getLogger(MojangProxy.class);

    /** Výchozí session server Mojangu. */
    public static final String DEFAULT_SESSION_HOST = "https://sessionserver.mojang.com";

    private final String sessionHost;
    private final Duration timeout;
    private final HttpClient http;

    /**
     * @param sessionHost základní URL session serveru (prázdné = {@value #DEFAULT_SESSION_HOST})
     * @param timeout     strop na spojení i odpověď
     */
    public MojangProxy(String sessionHost, Duration timeout) {
        this(sessionHost, timeout, HttpClient.newBuilder().connectTimeout(timeout).build());
    }

    /**
     * Konstruktor pro testy – injektuje HTTP klienta (např. nasměrovaného na
     * lokální fake session server bez volání ven).
     *
     * @param sessionHost základní URL session serveru
     * @param timeout     strop na odpověď
     * @param http        HTTP klient
     */
    MojangProxy(String sessionHost, Duration timeout, HttpClient http) {
        this.sessionHost = stripTrailingSlash(sessionHost == null || sessionHost.isBlank()
                ? DEFAULT_SESSION_HOST : sessionHost);
        this.timeout = timeout;
        this.http = http;
    }

    /**
     * Přepošle serverový dotaz {@code hasJoined} na skutečný Mojang.
     *
     * @param rawQuery syrový query string ({@code username}, {@code serverId},
     *                 případně {@code ip}) – předává se beze změny
     * @return odpověď Mojangu (stav + tělo); {@link Relay#UNVERIFIED} při chybě spojení
     */
    public Relay hasJoined(String rawQuery) {
        return get("/session/minecraft/hasJoined" + suffix(rawQuery));
    }

    /**
     * Přepošle dotaz na profil (skiny) reálného hráče na skutečný Mojang.
     * Offline UUID botů u Mojangu neexistují → vrátí prázdno (204), což serveru
     * nevadí (bot zůstane bez textur).
     *
     * @param id       UUID z cesty
     * @param rawQuery syrový query string (např. {@code unsigned=false})
     * @return odpověď Mojangu (stav + tělo); {@link Relay#UNVERIFIED} při chybě spojení
     */
    public Relay profile(String id, String rawQuery) {
        return get("/session/minecraft/profile/" + id + suffix(rawQuery));
    }

    private Relay get(String path) {
        try {
            HttpRequest req = HttpRequest.newBuilder(URI.create(sessionHost + path))
                    .timeout(timeout)
                    .GET()
                    .build();
            HttpResponse<byte[]> resp = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            String ct = resp.headers().firstValue("Content-Type").orElse(null);
            return new Relay(resp.statusCode(), resp.body(), ct);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Relay.UNVERIFIED;
        } catch (Exception e) {
            LOG.debug("Proxy na Mojang selhala ({}): {}", path, e.toString());
            return Relay.UNVERIFIED;
        }
    }

    private static String suffix(String rawQuery) {
        return rawQuery == null || rawQuery.isBlank() ? "" : "?" + rawQuery;
    }

    private static String stripTrailingSlash(String s) {
        return s.endsWith("/") ? s.substring(0, s.length() - 1) : s;
    }

    /**
     * Odpověď z upstreamu určená k relayování zpět serveru.
     *
     * @param status      HTTP stav
     * @param body        tělo (může být prázdné)
     * @param contentType hlavička {@code Content-Type} (nebo {@code null})
     */
    public record Relay(int status, byte[] body, String contentType) {

        /** Neověřeno / chyba spojení (Mojang sémantika: 204). */
        public static final Relay UNVERIFIED = new Relay(204, new byte[0], null);
    }
}
