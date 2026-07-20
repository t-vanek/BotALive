package dev.botalive.core.gateway;

import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.auth.SessionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * {@link SessionService} nasměrovaný na vlastní gateway BotAlive místo session
 * serveru Mojangu.
 *
 * <p>Výchozí {@code SessionService} má URL Mojangu zadrátované a neumožňuje je
 * přesměrovat, proto přepisujeme {@link #joinServer} tak, aby ohlášení
 * připojení ({@code /session/minecraft/join}) šlo na gateway a neslo podepsaný
 * token bota. Používá se jen v online-mode nasazení, kde je server (nebo proxy)
 * nasměrovaný na gateway – zapíná se konfigurací {@code gateway.client-auth}.</p>
 */
public final class GatewaySessionService extends SessionService {

    private static final Logger LOG = LoggerFactory.getLogger(GatewaySessionService.class);

    private final String joinUrl;
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    /**
     * @param baseUrl základní URL gateway (např. {@code http://127.0.0.1:41000})
     */
    public GatewaySessionService(String baseUrl) {
        String trimmed = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        this.joinUrl = trimmed + "/session/minecraft/join";
    }

    @Override
    public void joinServer(GameProfile profile, String authenticationToken, String serverId)
            throws IOException {
        String body = "{\"accessToken\":\"" + escape(authenticationToken)
                + "\",\"selectedProfile\":\"" + profile.getId().toString().replace("-", "")
                + "\",\"serverId\":\"" + escape(serverId) + "\"}";
        HttpRequest request = HttpRequest.newBuilder(URI.create(joinUrl))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();
        try {
            HttpResponse<Void> response = http.send(request, HttpResponse.BodyHandlers.discarding());
            int code = response.statusCode();
            if (code != 204 && code != 200) {
                throw new IOException("Gateway odmítla ohlášení připojení (HTTP " + code + ")");
            }
            LOG.debug("Ohlášení připojení '{}' na gateway proběhlo (HTTP {})", profile.getName(), code);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Přerušeno při volání gateway", e);
        }
    }

    /** Minimální JSON escapování (token je Base64URL, serverId hex – jen pojistka). */
    private static String escape(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
