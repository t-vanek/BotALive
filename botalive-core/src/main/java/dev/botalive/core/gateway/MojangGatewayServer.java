package dev.botalive.core.gateway;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Vestavěná HTTP gateway ve tvaru Mojang session API – vlastní autorita
 * BotAlive pro identitu a ověření botů.
 *
 * <p>Reimplementuje podmnožinu session serveru Mojangu potřebnou k ověření
 * připojení:</p>
 * <ul>
 *   <li>{@code POST /session/minecraft/join} – klient (bot) ohlásí připojení
 *       a předloží podepsaný token; gateway ho ověří.</li>
 *   <li>{@code GET  /session/minecraft/hasJoined} – server ověří, že se klient
 *       skutečně ohlásil, a dostane jeho profil.</li>
 *   <li>{@code GET  /session/minecraft/profile/{id}} – dotaz na profil (BotAlive
 *       nemá skiny, vrací prázdné vlastnosti).</li>
 *   <li>{@code GET  /botalive/health} – diagnostika.</li>
 * </ul>
 *
 * <p>Nasazení: online-mode server (nebo proxy) nasměrovaný na tuto gateway
 * (např. {@code -Dminecraft.api.session.host=http://127.0.0.1:41000}) pak
 * ověřuje připojení botů proti BotAlive místo Mojangu. Standardně běží na
 * loopbacku a je vypnutá – server-side pojistka {@code BotLoginGuard} chrání
 * offline server i bez HTTP vrstvy.</p>
 *
 * <p>Server běží na vlastních (virtuálních) vláknech a nikdy neblokuje herní
 * ani tick vlákna.</p>
 */
public final class MojangGatewayServer {

    private static final Logger LOG = LoggerFactory.getLogger(MojangGatewayServer.class);
    private static final int MAX_BODY_BYTES = 8 * 1024;

    private final String bindHost;
    private final int port;
    private final CredentialAuthority authority;

    private volatile HttpServer server;
    private volatile ExecutorService executor;
    private volatile int boundPort = -1;

    /**
     * @param bindHost  adresa, na které gateway naslouchá (typicky 127.0.0.1)
     * @param port      port; 0 = přidělí OS (užitečné pro testy)
     * @param authority ověřovací autorita
     */
    public MojangGatewayServer(String bindHost, int port, CredentialAuthority authority) {
        this.bindHost = bindHost;
        this.port = port;
        this.authority = authority;
    }

    /**
     * Spustí HTTP server.
     *
     * @throws IOException při chybě navázání portu
     */
    public synchronized void start() throws IOException {
        if (server != null) {
            return;
        }
        HttpServer http = HttpServer.create(new InetSocketAddress(bindHost, port), 0);
        ExecutorService exec = Executors.newVirtualThreadPerTaskExecutor();
        http.setExecutor(exec);
        http.createContext("/session/minecraft/hasJoined", this::handleHasJoined);
        http.createContext("/session/minecraft/join", this::handleJoin);
        http.createContext("/session/minecraft/profile/", this::handleProfile);
        http.createContext("/botalive/health", this::handleHealth);
        http.start();
        this.server = http;
        this.executor = exec;
        this.boundPort = http.getAddress().getPort();
        LOG.info("Mojang gateway naslouchá na {}:{}", bindHost, boundPort);
    }

    /** Zastaví HTTP server. */
    public synchronized void stop() {
        HttpServer http = server;
        server = null;
        if (http != null) {
            http.stop(0);
        }
        ExecutorService exec = executor;
        executor = null;
        if (exec != null) {
            exec.shutdown();
            try {
                if (!exec.awaitTermination(1, TimeUnit.SECONDS)) {
                    exec.shutdownNow();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
    }

    /** @return skutečně navázaný port (užitečné při {@code port == 0}), nebo -1 před startem */
    public int boundPort() {
        return boundPort;
    }

    // ------------------------------------------------------------------- handlery

    private void handleHasJoined(HttpExchange exchange) throws IOException {
        try {
            if (!"GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                respondEmpty(exchange, 405);
                return;
            }
            Map<String, String> q = query(exchange);
            String username = q.get("username");
            String serverId = q.get("serverId");
            if (username == null || serverId == null) {
                respondEmpty(exchange, 204);
                return;
            }
            Optional<GatewayProfile> profile = authority.resolveHasJoined(username, serverId);
            if (profile.isEmpty()) {
                respondEmpty(exchange, 204); // Mojang sémantika: 204 = neověřeno
                return;
            }
            respondJson(exchange, 200, profileJson(profile.get()));
        } catch (RuntimeException e) {
            LOG.debug("hasJoined selhal: {}", e.toString());
            safeEmpty(exchange, 204);
        }
    }

    private void handleJoin(HttpExchange exchange) throws IOException {
        try {
            if (!"POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                respondEmpty(exchange, 405);
                return;
            }
            String body = readBody(exchange);
            JsonObject json = JsonParser.parseString(body).getAsJsonObject();
            String accessToken = optString(json, "accessToken");
            String selected = optString(json, "selectedProfile");
            String serverId = optString(json, "serverId");
            if (accessToken == null || selected == null || serverId == null) {
                respondEmpty(exchange, 400);
                return;
            }
            UUID selectedId = GatewayProfile.parseId(selected);
            boolean ok = authority.registerJoin(accessToken, selectedId, serverId);
            respondEmpty(exchange, ok ? 204 : 403); // Mojang sémantika: 204 = úspěch
        } catch (RuntimeException e) {
            LOG.debug("join selhal: {}", e.toString());
            safeEmpty(exchange, 403);
        }
    }

    private void handleProfile(HttpExchange exchange) throws IOException {
        // BotAlive nespravuje skiny – vrací prázdný obsah (server si poradí bez textur).
        safeEmpty(exchange, 204);
    }

    private void handleHealth(HttpExchange exchange) throws IOException {
        try {
            JsonObject json = new JsonObject();
            json.addProperty("status", "ok");
            json.addProperty("pending", authority.pendingCount());
            respondJson(exchange, 200, json);
        } catch (RuntimeException e) {
            safeEmpty(exchange, 500);
        }
    }

    // ------------------------------------------------------------------- pomocné

    private static JsonObject profileJson(GatewayProfile profile) {
        JsonObject json = new JsonObject();
        json.addProperty("id", profile.undashedId());
        json.addProperty("name", profile.name());
        json.add("properties", new JsonArray());
        return json;
    }

    private static Map<String, String> query(HttpExchange exchange) {
        Map<String, String> result = new HashMap<>();
        String raw = exchange.getRequestURI().getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return result;
        }
        for (String pair : raw.split("&")) {
            int eq = pair.indexOf('=');
            if (eq <= 0) {
                continue;
            }
            String k = URLDecoder.decode(pair.substring(0, eq), StandardCharsets.UTF_8);
            String v = URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);
            result.putIfAbsent(k, v);
        }
        return result;
    }

    private static String readBody(HttpExchange exchange) throws IOException {
        try (InputStream in = exchange.getRequestBody()) {
            byte[] data = in.readNBytes(MAX_BODY_BYTES);
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    private static String optString(JsonObject json, String key) {
        return json.has(key) && json.get(key).isJsonPrimitive() ? json.get(key).getAsString() : null;
    }

    private static void respondJson(HttpExchange exchange, int code, JsonObject json)
            throws IOException {
        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(code, body.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(body);
        }
    }

    private static void respondEmpty(HttpExchange exchange, int code) throws IOException {
        exchange.sendResponseHeaders(code, -1);
        exchange.close();
    }

    private static void safeEmpty(HttpExchange exchange, int code) {
        try {
            respondEmpty(exchange, code);
        } catch (IOException ignored) {
            exchange.close();
        }
    }
}
