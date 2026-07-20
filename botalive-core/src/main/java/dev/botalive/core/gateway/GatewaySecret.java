package dev.botalive.core.gateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Zdroj tajného klíče HMAC pro {@link CredentialAuthority}.
 *
 * <p>Priorita: (1) klíč z konfigurace (umožní sdílet stejný klíč mezi více
 * servery/instancemi – fleet botů), jinak (2) klíč z datového souboru
 * {@code gateway-secret.key}, který se při první potřebě vygeneruje a uloží.
 * Tím je klíč stabilní napříč restarty, aniž by ho admin musel řešit.</p>
 */
public final class GatewaySecret {

    private static final Logger LOG = LoggerFactory.getLogger(GatewaySecret.class);
    private static final String FILE_NAME = "gateway-secret.key";
    private static final int GENERATED_BYTES = 32;

    private GatewaySecret() {
    }

    /**
     * Vyřeší tajný klíč pro autoritu.
     *
     * @param configured klíč z konfigurace (prázdný = použít/vygenerovat soubor)
     * @param dataFolder datová složka pluginu
     * @return bajty tajného klíče (min. 16 B)
     */
    public static byte[] resolve(String configured, File dataFolder) {
        if (configured != null && !configured.isBlank()) {
            return configured.getBytes(StandardCharsets.UTF_8);
        }
        Path file = dataFolder.toPath().resolve(FILE_NAME);
        try {
            if (Files.exists(file)) {
                String stored = Files.readString(file, StandardCharsets.UTF_8).trim();
                if (!stored.isBlank()) {
                    return Base64.getDecoder().decode(stored);
                }
            }
            byte[] secret = generate();
            Files.createDirectories(dataFolder.toPath());
            Files.writeString(file, Base64.getEncoder().encodeToString(secret), StandardCharsets.UTF_8);
            LOG.info("Vygenerován nový tajný klíč gateway: {}", file);
            return secret;
        } catch (IOException | IllegalArgumentException e) {
            LOG.warn("Nelze načíst/uložit tajný klíč gateway ({}) – používám dočasný klíč pro "
                    + "tento běh.", e.toString());
            return generate();
        }
    }

    private static byte[] generate() {
        byte[] secret = new byte[GENERATED_BYTES];
        new SecureRandom().nextBytes(secret);
        return secret;
    }
}
