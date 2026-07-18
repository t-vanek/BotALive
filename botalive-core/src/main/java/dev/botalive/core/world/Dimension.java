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
     * <p>Vanilla dimenze mají cestu {@code the_end}/{@code the_nether}.
     * Světy vytvořené pluginy (Multiverse…) ale CraftBukkit klíčuje jako
     * {@code minecraft:<jméno_světa>} – proto se uznávají i konvenční
     * přípony jmen ({@code world_the_end}, {@code mv_end}). Server mode
     * tuhle heuristiku nepotřebuje (čte Bukkit {@code World.Environment});
     * v packet modu je to nejlepší dostupný odhad.</p>
     *
     * @param worldKey klíč světa (např. {@code minecraft:the_end}), {@code null} toleruje
     * @return dimenze ({@link #OVERWORLD} pro neznámé klíče)
     */
    public static Dimension fromWorldKey(String worldKey) {
        if (worldKey == null) {
            return OVERWORLD;
        }
        String path = worldKey.substring(worldKey.indexOf(':') + 1);
        if (path.equals("the_end") || path.endsWith("_the_end") || path.endsWith("_end")) {
            return THE_END;
        }
        if (path.equals("the_nether") || path.endsWith("_the_nether")
                || path.endsWith("_nether")) {
            return NETHER;
        }
        return OVERWORLD;
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
