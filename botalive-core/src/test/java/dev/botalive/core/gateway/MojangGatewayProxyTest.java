package dev.botalive.core.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy proxy na Mojang ({@link MojangProxy} + {@link MojangGatewayServer}):
 * neznámá jména/UUID (reální hráči) se relayují na skutečný session server,
 * boti dál procházejí lokálně přes autoritu. Fake session server běží na
 * loopbacku – žádné volání ven.
 */
class MojangGatewayProxyTest {

    private static final byte[] SECRET =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final UUID BOT =
            UUID.nameUUIDFromBytes("OfflinePlayer:Pepa".getBytes(StandardCharsets.UTF_8));

    private CredentialAuthority authority;
    private HttpServer fakeMojang;
    private MojangGatewayServer server;
    private HttpClient client;
    private String base;

    /** Poslední cesta a query, které gateway přeposlala na fake Mojang. */
    private final AtomicReference<String> lastMojangPath = new AtomicReference<>();
    private final AtomicReference<String> lastMojangQuery = new AtomicReference<>();
    /** Odpověď, kterou fake Mojang vrátí. */
    private volatile int mojangStatus = 200;
    private volatile String mojangBody = "";

    @BeforeEach
    void setUp() throws Exception {
        authority = new CredentialAuthority(SECRET, 60_000L, true);

        fakeMojang = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        fakeMojang.createContext("/session/minecraft/", exchange -> {
            lastMojangPath.set(exchange.getRequestURI().getPath());
            lastMojangQuery.set(exchange.getRequestURI().getRawQuery());
            byte[] body = mojangBody.getBytes(StandardCharsets.UTF_8);
            if (body.length == 0) {
                exchange.sendResponseHeaders(mojangStatus, -1);
                exchange.close();
            } else {
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(mojangStatus, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            }
        });
        fakeMojang.start();
        String mojangHost = "http://127.0.0.1:" + fakeMojang.getAddress().getPort();

        // NO_PROXY klient, ať test nezávisí na okolní JVM proxy konfiguraci.
        MojangProxy proxy = new MojangProxy(mojangHost, Duration.ofSeconds(5),
                HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build());
        server = new MojangGatewayServer("127.0.0.1", 0, authority, proxy);
        server.start();
        base = "http://127.0.0.1:" + server.boundPort();
        client = HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build();
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop();
        }
        if (fakeMojang != null) {
            fakeMojang.stop(0);
        }
    }

    @Test
    void botProchaziLokalneBezVolaniMojangu() throws Exception {
        BotCredential cred = authority.issue(BOT, "Pepa");
        String serverId = "botserverhash";
        String undashed = BOT.toString().replace("-", "");
        assertEquals(204, post("/session/minecraft/join",
                "{\"accessToken\":\"" + cred.token() + "\",\"selectedProfile\":\"" + undashed
                        + "\",\"serverId\":\"" + serverId + "\"}").statusCode());

        HttpResponse<String> has =
                get("/session/minecraft/hasJoined?username=Pepa&serverId=" + serverId);
        assertEquals(200, has.statusCode());
        assertTrue(has.body().contains(undashed));
        // Proxy se pro ověřeného bota vůbec nevolala.
        assertNull(lastMojangPath.get());
    }

    @Test
    void neznamyHracSeRelayujeNaMojangVcetnePodpisu() throws Exception {
        mojangStatus = 200;
        mojangBody = "{\"id\":\"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa\",\"name\":\"RealPlayer\","
                + "\"properties\":[{\"name\":\"textures\",\"value\":\"BASE64\","
                + "\"signature\":\"SIG\"}]}";

        HttpResponse<String> has = get(
                "/session/minecraft/hasJoined?username=RealPlayer&serverId=xyz&ip=1.2.3.4");
        assertEquals(200, has.statusCode());
        // Tělo se relayovalo doslova – včetně podepsaných textur (jinak skiny selžou).
        assertEquals(mojangBody, has.body());
        // Gateway přeposlala správnou cestu i parametry (včetně ip pro prevent-proxy-connections).
        assertEquals("/session/minecraft/hasJoined", lastMojangPath.get());
        assertEquals("username=RealPlayer&serverId=xyz&ip=1.2.3.4", lastMojangQuery.get());
    }

    @Test
    void mojangNezna204SeRelayuje() throws Exception {
        mojangStatus = 204;
        mojangBody = "";
        HttpResponse<String> has =
                get("/session/minecraft/hasJoined?username=Nikdo&serverId=zzz");
        assertEquals(204, has.statusCode());
        assertEquals("/session/minecraft/hasJoined", lastMojangPath.get());
    }

    @Test
    void profilSeRelayujeNaMojang() throws Exception {
        mojangStatus = 200;
        mojangBody = "{\"id\":\"abc\",\"name\":\"RealPlayer\",\"properties\":[]}";
        String id = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa";
        HttpResponse<String> resp =
                get("/session/minecraft/profile/" + id + "?unsigned=false");
        assertEquals(200, resp.statusCode());
        assertEquals(mojangBody, resp.body());
        assertEquals("/session/minecraft/profile/" + id, lastMojangPath.get());
        assertEquals("unsigned=false", lastMojangQuery.get());
    }

    @Test
    void nedostupnyMojangVraciNeovereno() {
        // Proxy na mrtvý loopback port → chyba spojení → 204 (neověřeno), bez výjimky.
        MojangProxy dead = new MojangProxy("http://127.0.0.1:1", Duration.ofMillis(500),
                HttpClient.newBuilder().proxy(HttpClient.Builder.NO_PROXY).build());
        MojangProxy.Relay relay = dead.hasJoined("username=X&serverId=Y");
        assertEquals(204, relay.status());
        assertEquals(0, relay.body().length);
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                        .build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
