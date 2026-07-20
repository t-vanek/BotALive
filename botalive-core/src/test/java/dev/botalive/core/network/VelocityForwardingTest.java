package dev.botalive.core.network;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Testy Velocity modern forwarding payloadu ({@link VelocityForwarding}).
 * Ověřují, že podpis odpovídá nezávisle spočítanému HMAC (jak by ho ověřil
 * Paper) a že se datová část dekóduje zpět na správnou identitu.
 */
class VelocityForwardingTest {

    private static final byte[] SECRET =
            "velocity-shared-secret-123456789".getBytes(StandardCharsets.UTF_8);
    private static final UUID ID =
            UUID.nameUUIDFromBytes("OfflinePlayer:Pepa".getBytes(StandardCharsets.UTF_8));

    @Test
    void podpisOdpovidaNezavislemuHmac() throws Exception {
        byte[] response = VelocityForwarding.buildResponse(SECRET, "127.0.0.1", ID, "Pepa");

        // Formát: signature(32) ‖ data. Paper takto ověřuje integritu.
        byte[] signature = Arrays.copyOfRange(response, 0, 32);
        byte[] data = Arrays.copyOfRange(response, 32, response.length);

        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET, "HmacSHA256"));
        byte[] expected = mac.doFinal(data);

        assertArrayEquals(expected, signature, "podpis musí být HMAC-SHA256(secret, data)");
    }

    @Test
    void dataSeDekodujiNaSpravnouIdentitu() {
        byte[] response = VelocityForwarding.buildResponse(SECRET, "127.0.0.1", ID, "Pepa");
        int[] pos = {32}; // přeskoč 32B podpis

        assertEquals(VelocityForwarding.VERSION, readVarInt(response, pos), "forwarding verze");
        assertEquals("127.0.0.1", readString(response, pos), "adresa");
        assertEquals(ID, readUuid(response, pos), "UUID bota");
        assertEquals("Pepa", readString(response, pos), "jméno bota");
        assertEquals(0, readVarInt(response, pos), "0 profilových vlastností");
        assertEquals(response.length, pos[0], "žádné přebytečné bajty");
    }

    @Test
    void jinySecretDavaJinyPodpis() {
        byte[] a = VelocityForwarding.buildResponse(SECRET, "127.0.0.1", ID, "Pepa");
        byte[] b = VelocityForwarding.buildResponse(
                "uplne-jiny-secret-0000000000000".getBytes(StandardCharsets.UTF_8),
                "127.0.0.1", ID, "Pepa");
        assertFalse(Arrays.equals(Arrays.copyOfRange(a, 0, 32), Arrays.copyOfRange(b, 0, 32)),
                "jiný klíč → jiný podpis");
    }

    @Test
    void varIntViceBajtovyPodleProtokolu() {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        VelocityForwarding.writeVarInt(out, 300);
        // 300 = 0xAC 0x02 (7 bitů na bajt, MSB = pokračování)
        assertArrayEquals(new byte[]{(byte) 0xAC, 0x02}, out.toByteArray());
    }

    // ------------------------------------------------------- dekodéry pro test

    private static int readVarInt(byte[] b, int[] pos) {
        int value = 0, shift = 0, read;
        do {
            read = b[pos[0]++] & 0xFF;
            value |= (read & 0x7F) << shift;
            shift += 7;
        } while ((read & 0x80) != 0);
        return value;
    }

    private static String readString(byte[] b, int[] pos) {
        int len = readVarInt(b, pos);
        String s = new String(b, pos[0], len, StandardCharsets.UTF_8);
        pos[0] += len;
        return s;
    }

    private static UUID readUuid(byte[] b, int[] pos) {
        long msb = readLong(b, pos);
        long lsb = readLong(b, pos);
        return new UUID(msb, lsb);
    }

    private static long readLong(byte[] b, int[] pos) {
        long v = 0;
        for (int i = 0; i < 8; i++) {
            v = (v << 8) | (b[pos[0]++] & 0xFFL);
        }
        return v;
    }
}
