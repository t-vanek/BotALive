package dev.botalive.core.world;

/**
 * Typ dimenze, ve které se bot nachází.
 *
 * <p>Identita světa zůstává jménem (server mode) nebo protokolovým klíčem
 * (packet mode); dimenze je odvozená vlastnost pro rozhodování AI – v Endu
 * se nespí (postel exploduje), nefarmaří ani nestaví domy, zato se bojuje
 * s drakem. Vlastní dimenze datapacků spadají na {@link #OVERWORLD}
 * (nejbezpečnější výchozí chování).</p>
 */
public enum Dimension {

    /** Běžný svět. */
    OVERWORLD,

    /** Nether – postel exploduje, voda se vypařuje. */
    NETHER,

    /** End – void, endermani, drak; postel exploduje. */
    THE_END;

    /**
     * Odvodí dimenzi z protokolového klíče světa (packet mode).
     *
     * @param worldKey klíč světa (např. {@code minecraft:the_end}), {@code null} toleruje
     * @return dimenze ({@link #OVERWORLD} pro neznámé klíče)
     */
    public static Dimension fromWorldKey(String worldKey) {
        if (worldKey == null) {
            return OVERWORLD;
        }
        // Klíč může nést vlastní namespace (multiverse pluginy) – rozhoduje cesta.
        String path = worldKey.substring(worldKey.indexOf(':') + 1);
        return switch (path) {
            case "the_end" -> THE_END;
            case "the_nether" -> NETHER;
            default -> OVERWORLD;
        };
    }

    /**
     * Odvodí dimenzi z Bukkit prostředí světa (server mode).
     *
     * @param environment prostředí světa, {@code null} toleruje
     * @return dimenze ({@link #OVERWORLD} pro NORMAL/CUSTOM)
     */
    public static Dimension fromBukkit(org.bukkit.World.Environment environment) {
        if (environment == null) {
            return OVERWORLD;
        }
        return switch (environment) {
            case NETHER -> NETHER;
            case THE_END -> THE_END;
            default -> OVERWORLD;
        };
    }
}
