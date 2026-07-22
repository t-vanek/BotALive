package dev.botalive.core.build;

import org.bukkit.Material;

import java.util.Set;

/**
 * Styl ohradní bariéry a jeho materiály – odděluje „co bariéra je" od „z čeho",
 * stejně jako {@link SettlementRoads} nechává tool/materiál na vykonavateli a
 * {@code PaletteResolver} skládá dům z místního dřeva. {@link Enclosure} řeší
 * jen geometrii; tady se rozhoduje, jestli je to dřevěný <b>plot</b> nebo
 * kamenná <b>hradba</b>, a z jakého dřeva.
 *
 * <p>Plot se – jako domy – staví z <b>okolního dřeva</b> (dub v lese, smrk
 * v horách…): {@code woodHint} (kmen/prkna z batohu nebo okolí) určí prefix,
 * neznámé spadne na dub. Hradba je kamenná (cobblestone wall) s dřevěnou
 * brankou.</p>
 */
public enum BarrierStyle {

    /** Dřevěný plot – kolem parcel domů a stád (výška 1). */
    FENCE,
    /** Kamenná hradba – kolem sídel (výška 2+). */
    WALL;

    /** Známé předpony dřeva (parita s {@code PaletteResolver.WOODS}). */
    private static final Set<String> WOODS = Set.of(
            "OAK", "SPRUCE", "BIRCH", "JUNGLE", "ACACIA", "DARK_OAK",
            "MANGROVE", "CHERRY", "PALE_OAK");

    /**
     * Materiály bariéry.
     *
     * @param post sloupek bariéry (plaňka plotu / kámen hradby)
     * @param gate branka (fence gate)
     */
    public record Materials(Material post, Material gate) {
    }

    /**
     * Materiály tohoto stylu podle napovězeného dřeva.
     *
     * @param woodHint materiál napovídající dřevo (kmen/prkna), nebo {@code null}
     *                 (pak dub); u hradby se ignoruje
     * @return sloupek + branka
     */
    public Materials materials(Material woodHint) {
        if (this == WALL) {
            // Kamenná hradba: zídka + dřevěná branka (branky jsou jen dřevěné).
            return new Materials(Material.COBBLESTONE_WALL, Material.OAK_FENCE_GATE);
        }
        String wood = woodPrefix(woodHint);
        return new Materials(
                material(wood + "_FENCE", Material.OAK_FENCE),
                material(wood + "_FENCE_GATE", Material.OAK_FENCE_GATE));
    }

    /** Předpona dřeva z materiálu (kmen/prkna); {@code OAK} když neznámé. */
    private static String woodPrefix(Material hint) {
        if (hint == null) {
            return "OAK";
        }
        String name = hint.name();
        for (String wood : WOODS) {
            if (name.startsWith(wood + "_")) {
                return wood;
            }
        }
        return "OAK";
    }

    private static Material material(String name, Material fallback) {
        try {
            return Material.valueOf(name);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }
}
