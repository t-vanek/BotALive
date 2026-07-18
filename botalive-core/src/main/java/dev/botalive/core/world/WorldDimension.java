package dev.botalive.core.world;

import java.util.Locale;

/**
 * Dimenze světa, jak ji vidí bot – řídí chování závislé na prostředí
 * (postel v Netheru vybuchuje, v Netheru není voda ani noc, vesnice a farmy
 * patří do overworldu, těžbu v Netheru řeší výprava).
 *
 * <p>V server režimu se určuje autoritativně z Bukkit
 * {@link org.bukkit.World.Environment}; v packet režimu heuristicky
 * z protokolového klíče světa ({@link #fromWorldKey(String)}) – vanilla klíče
 * {@code minecraft:the_nether} / {@code minecraft:the_end} i obvyklé názvy
 * Bukkit světů ({@code world_nether}, {@code world_the_end}).</p>
 */
public enum WorldDimension {

    /** Běžný svět (i neznámé custom dimenze – chovají se overworldově). */
    OVERWORLD,

    /** Nether – láva, žádná voda, postel vybuchuje. */
    NETHER,

    /** End – postel vybuchuje, void všude kolem. */
    END,

    /** Bot ještě nemá world view (před spawnem). */
    UNKNOWN;

    /**
     * @param environment Bukkit prostředí světa
     * @return dimenze; custom prostředí se bere jako {@link #OVERWORLD}
     */
    public static WorldDimension fromEnvironment(org.bukkit.World.Environment environment) {
        if (environment == null) {
            return OVERWORLD;
        }
        return switch (environment) {
            case NETHER -> NETHER;
            case THE_END -> END;
            default -> OVERWORLD;
        };
    }

    /**
     * Heuristika pro packet režim, kde je k dispozici jen klíč světa.
     *
     * @param worldKey protokolový klíč nebo název světa (např.
     *                 {@code minecraft:the_nether}, {@code world_nether})
     * @return odhadnutá dimenze; neznámé klíče jsou {@link #OVERWORLD}
     */
    public static WorldDimension fromWorldKey(String worldKey) {
        if (worldKey == null || worldKey.isBlank()) {
            return UNKNOWN;
        }
        String key = worldKey.toLowerCase(Locale.ROOT);
        if (key.contains("the_nether") || key.endsWith("_nether") || key.equals("nether")) {
            return NETHER;
        }
        // Pozor na falešné shody: „west_end" je běžný název overworld mapy,
        // Bukkit end světy končí na „_the_end" – bare „_end" se neuznává.
        if (key.contains("the_end") || key.equals("end")) {
            return END;
        }
        return OVERWORLD;
    }
}
