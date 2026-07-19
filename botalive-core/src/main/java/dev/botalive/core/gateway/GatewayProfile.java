package dev.botalive.core.gateway;

import java.util.UUID;

/**
 * Minimalistický herní profil (UUID + jméno), jak ho vrací Mojang session API
 * v odpovědi {@code hasJoined}.
 *
 * <p>Záměrně nezávisí na typu {@code GameProfile} z MCProtocolLib – gateway
 * i ověřovací autorita zůstávají bez závislosti na síťové knihovně a jsou
 * jednotkově testovatelné bez Minecraftu.</p>
 *
 * @param id   UUID hráče/bota
 * @param name jméno hráče/bota
 */
public record GatewayProfile(UUID id, String name) {

    /**
     * @return UUID bez pomlček – formát, ve kterém Mojang session API vrací
     *         pole {@code id} v profilu
     */
    public String undashedId() {
        return id.toString().replace("-", "");
    }

    /**
     * Parsuje UUID z formátu s pomlčkami i bez nich (Mojang API používá formu
     * bez pomlček).
     *
     * @param raw textová podoba UUID (32 hex znaků nebo kanonická forma s pomlčkami)
     * @return rozparsované UUID
     * @throws IllegalArgumentException při neplatném vstupu
     */
    public static UUID parseId(String raw) {
        String s = raw.trim();
        if (s.length() == 32 && s.indexOf('-') < 0) {
            s = s.substring(0, 8) + "-" + s.substring(8, 12) + "-" + s.substring(12, 16)
                    + "-" + s.substring(16, 20) + "-" + s.substring(20);
        }
        return UUID.fromString(s);
    }
}
