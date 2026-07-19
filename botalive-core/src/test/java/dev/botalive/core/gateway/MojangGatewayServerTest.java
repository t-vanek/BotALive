package dev.botalive.core.gateway;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy vestavěné HTTP gateway ({@link MojangGatewayServer}) – reálný HTTP
 * kolotoč join → hasJoined proti autoritě.
 */
class MojangGatewayServerTest {

    private static final byte[] SECRET =
            "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    private static final UUID BOT =
            UUID.nameUUIDFromBytes("OfflinePlayer:Pepa".getBytes(StandardCharsets.UTF_8));

    private CredentialAuthority authority;
    private MojangGatewayServer server;
    private String base;
    private HttpClient client;

    @BeforeEach
    void setUp() throws Exception {
        authority = new CredentialAuthority(SECRET, 60_000L, true);
        server = new MojangGatewayServer("127.0.0.1", 0, authority);
        server.start();
        base = "http://127.0.0.1:" + server.boundPort();
        client = HttpClient.newHttpClient();
    }

    @AfterEach
    void tearDown() {
        server.stop();
    }

    @Test
    void plnyKolotocJoinAHasJoined() throws Exception {
        assertTrue(server.boundPort() > 0);
        BotCredential cred = authority.issue(BOT, "Pepa");
        String serverId = "deadbeefserverhash";
        String undashed = BOT.toString().replace("-", "");

        int join = post("/session/minecraft/join",
                "{\"accessToken\":\"" + cred.token() + "\",\"selectedProfile\":\"" + undashed
                        + "\",\"serverId\":\"" + serverId + "\"}").statusCode();
        assertEquals(204, join);

        HttpResponse<String> has = get("/session/minecraft/hasJoined?username=Pepa&serverId=" + serverId);
        assertEquals(200, has.statusCode());
        assertTrue(has.body().contains(undashed));
        assertTrue(has.body().contains("\"name\":\"Pepa\""));

        // Podruhé už je join spotřebovaný → 204.
        assertEquals(204, get("/session/minecraft/hasJoined?username=Pepa&serverId=" + serverId)
                .statusCode());
    }

    @Test
    void neznamyServerIdVraci204() throws Exception {
        assertEquals(204, get("/session/minecraft/hasJoined?username=X&serverId=nic").statusCode());
    }

    @Test
    void podvrzenyTokenVraci403() throws Exception {
        int code = post("/session/minecraft/join",
                "{\"accessToken\":\"bad.token\",\"selectedProfile\":\""
                        + BOT.toString().replace("-", "") + "\",\"serverId\":\"sid9\"}").statusCode();
        assertEquals(403, code);
    }

    @Test
    void spatnaMetodaNaJoinVraci405() throws Exception {
        assertEquals(405, get("/session/minecraft/join").statusCode());
    }

    @Test
    void healthVraciOk() throws Exception {
        HttpResponse<String> health = get("/botalive/health");
        assertEquals(200, health.statusCode());
        assertTrue(health.body().contains("\"status\":\"ok\""));
    }

    private HttpResponse<String> get(String path) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path, String body) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + path))
                        .header("Content-Type", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString());
    }
}
