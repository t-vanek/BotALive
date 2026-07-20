package dev.botalive.core.network;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.UUID;

/**
 * Velocity „modern" player-info forwarding na straně bota.
 *
 * <p>Když boti míří na offline-mode backend za Velocity proxy, backend při
 * loginu pošle login-plugin dotaz na kanálu {@value #CHANNEL} a čeká na
 * podepsaný payload s identitou hráče (jinak připojení odmítne). Bot se
 * nepřipojuje přes proxy (jde přímo na backend na loopbacku), takže si tento
 * payload musí vyrobit sám – „zahraje" roli Velocity a podepíše ho stejným
 * tajným klíčem jako proxy ({@code forwarding.secret}).</p>
 *
 * <p>Formát dat (přesně jak je čte Paper): {@code signature ‖ data}, kde
 * {@code signature = HMAC_SHA256(secret, data)} (32 B) a
 * {@code data = VarInt version, String address, UUID id, String name,
 * VarInt properties=0}. Použitá verze je {@link #VERSION} (bez klíče/session –
 * bot nemá podpisový klíč hráče, což offline botovi nevadí).</p>
 *
 * <p>Třída je čistá a bez závislosti na síťové knihovně (jednotkově
 * testovatelná); enkodéry VarInt/String/UUID odpovídají Minecraft protokolu.</p>
 */
public final class VelocityForwarding {

    /** Login-plugin kanál Velocity modern forwardingu. */
    public static final String CHANNEL = "velocity:player_info";

    /** Forwarding verze bez podpisového klíče hráče (výchozí Velocity). */
    public static final int VERSION = 1;

    private static final String HMAC_ALGO = "HmacSHA256";

    private VelocityForwarding() {
    }

    /**
     * Sestaví tělo odpovědi login-plugin dotazu: podpis (32 B) následovaný daty.
     *
     * @param secret  sdílený tajný klíč (stejný jako {@code forwarding.secret} Velocity)
     * @param address adresa hráče/bota (typicky {@code 127.0.0.1} pro lokálního bota)
     * @param id      UUID bota
     * @param name    jméno bota
     * @return bajty k odeslání v {@code ServerboundCustomQueryAnswerPacket}
     */
    public static byte[] buildResponse(byte[] secret, String address, UUID id, String name) {
        byte[] data = forwardingData(address, id, name);
        byte[] signature = hmac(secret, data);
        byte[] out = new byte[signature.length + data.length];
        System.arraycopy(signature, 0, out, 0, signature.length);
        System.arraycopy(data, 0, out, signature.length, data.length);
        return out;
    }

    /**
     * @param address adresa hráče/bota
     * @param id      UUID bota
     * @param name    jméno bota
     * @return podepisovaná datová část (bez podpisu)
     */
    static byte[] forwardingData(String address, UUID id, String name) {
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        writeVarInt(buf, VERSION);
        writeString(buf, address);
        writeUuid(buf, id);
        writeString(buf, name);
        writeVarInt(buf, 0); // 0 profilových vlastností (bot nemá skin)
        return buf.toByteArray();
    }

    /**
     * @param secret tajný klíč HMAC
     * @param data   podepisovaná data
     * @return HMAC-SHA256 (32 B)
     */
    static byte[] hmac(byte[] secret, byte[] data) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGO);
            mac.init(new SecretKeySpec(secret, HMAC_ALGO));
            return mac.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Velocity HMAC výpočet selhal", e);
        }
    }

    // ------------------------------------------------------- protokolové enkodéry

    /** Zapíše VarInt (7 bitů na bajt, MSB = pokračování) – Minecraft protokol. */
    static void writeVarInt(ByteArrayOutputStream out, int value) {
        while ((value & ~0x7F) != 0) {
            out.write((value & 0x7F) | 0x80);
            value >>>= 7;
        }
        out.write(value);
    }

    /** Zapíše String jako VarInt délku (v bajtech UTF-8) + UTF-8 bajty. */
    static void writeString(ByteArrayOutputStream out, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        writeVarInt(out, bytes.length);
        out.writeBytes(bytes);
    }

    /** Zapíše UUID jako dva big-endian longy (most/least significant). */
    static void writeUuid(ByteArrayOutputStream out, UUID id) {
        writeLong(out, id.getMostSignificantBits());
        writeLong(out, id.getLeastSignificantBits());
    }

    private static void writeLong(ByteArrayOutputStream out, long v) {
        for (int i = 7; i >= 0; i--) {
            out.write((int) (v >>> (i * 8)) & 0xFF);
        }
    }
}
