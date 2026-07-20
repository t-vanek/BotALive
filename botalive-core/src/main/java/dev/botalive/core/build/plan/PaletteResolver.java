package dev.botalive.core.build.plan;

import org.bukkit.Material;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/**
 * Skládá {@link Palette} z místního dřeva a seedu – dům se staví z toho, co
 * roste okolo (dub v listnatém lese, smrk v horách…), s variací základů
 * a střechy podle seedu, takže dva domy ze stejného dřeva nejsou totožné.
 * Nezávisí na Biome API (funguje i v packet režimu): druh dřeva dodá volající
 * z okolních kmenů nebo z batohu.
 */
public final class PaletteResolver {

    /** Známé předpony dřeva (odvození plank/kmene). */
    private static final Set<String> WOODS = Set.of(
            "OAK", "SPRUCE", "BIRCH", "JUNGLE", "ACACIA", "DARK_OAK",
            "MANGROVE", "CHERRY", "PALE_OAK");

    private PaletteResolver() {
    }

    /**
     * @param woodHint materiál napovídající dřevo (kmen/prkna), nebo {@code null}
     * @param seed     seed variace (typicky osobnostní seed bota)
     * @return paleta stavby
     */
    public static Palette resolve(Material woodHint, long seed) {
        String wood = woodPrefix(woodHint);
        Material planks = material(wood + "_PLANKS", Material.OAK_PLANKS);
        Material log = material(wood + "_LOG", Material.OAK_LOG);
        Random rng = new Random(seed);
        Material foundation = choose(rng, Material.COBBLESTONE, Material.STONE_BRICKS, Material.STONE);
        Material roof = choose(rng, Material.COBBLESTONE, planks, Material.STONE_BRICKS);

        Map<PaletteRole, List<Material>> byRole = new EnumMap<>(PaletteRole.class);
        byRole.put(PaletteRole.FOUNDATION,
                List.of(foundation, Material.COBBLESTONE, Material.STONE));
        byRole.put(PaletteRole.WALL, List.of(planks));
        byRole.put(PaletteRole.WALL_ACCENT, List.of(log, planks));
        byRole.put(PaletteRole.WINDOW, List.of(Material.GLASS, Material.GLASS_PANE));
        byRole.put(PaletteRole.ROOF, List.of(roof, Material.COBBLESTONE, planks));
        return new Palette(byRole);
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

    private static Material choose(Random rng, Material... options) {
        return options[rng.nextInt(options.length)];
    }
}
