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
     * Výchozí (solidní) paleta – zpětně kompatibilní vstupní bod.
     *
     * @param woodHint materiál napovídající dřevo (kmen/prkna), nebo {@code null}
     * @param seed     seed variace (typicky osobnostní seed bota)
     * @return paleta stavby na stupni {@link BuildTier#SOLID}
     */
    public static Palette resolve(Material woodHint, long seed) {
        return resolve(woodHint, seed, BuildTier.SOLID);
    }

    /**
     * Paleta pro daný stavební stupeň: {@link BuildTier#PROVISIONAL srub} ze
     * dřeva s otvory místo oken, {@link BuildTier#SOLID solidní} kámen+sklo
     * (dnešní vzhled), {@link BuildTier#REFINED reprezentativní} cihly/tesaný
     * kámen. Geometrie se nemění – jen materiály rolí, takže povýšení domu je
     * záměna palety, ne přestavba.
     *
     * @param woodHint materiál napovídající dřevo (kmen/prkna), nebo {@code null}
     * @param seed     seed variace (typicky osobnostní seed bota)
     * @param tier     stavební stupeň
     * @return paleta stavby
     */
    public static Palette resolve(Material woodHint, long seed, BuildTier tier) {
        String wood = woodPrefix(woodHint);
        Material planks = material(wood + "_PLANKS", Material.OAK_PLANKS);
        Material log = material(wood + "_LOG", Material.OAK_LOG);
        Random rng = new Random(seed);

        Map<PaletteRole, List<Material>> byRole = new EnumMap<>(PaletteRole.class);
        switch (tier) {
            case PROVISIONAL -> {
                // Srub: dřevo a hlína, okna jen otvory (prázdná role → LEAVE_EMPTY).
                byRole.put(PaletteRole.FOUNDATION,
                        List.of(planks, Material.DIRT, Material.COBBLESTONE));
                byRole.put(PaletteRole.WALL, List.of(planks));
                byRole.put(PaletteRole.WALL_ACCENT, List.of(log, planks));
                byRole.put(PaletteRole.WINDOW, List.of());
                byRole.put(PaletteRole.ROOF, List.of(planks, log));
            }
            case SOLID -> {
                Material foundation =
                        choose(rng, Material.COBBLESTONE, Material.STONE_BRICKS, Material.STONE);
                Material roof = choose(rng, Material.COBBLESTONE, planks, Material.STONE_BRICKS);
                byRole.put(PaletteRole.FOUNDATION,
                        List.of(foundation, Material.COBBLESTONE, Material.STONE));
                byRole.put(PaletteRole.WALL, List.of(planks));
                byRole.put(PaletteRole.WALL_ACCENT, List.of(log, planks));
                byRole.put(PaletteRole.WINDOW, List.of(Material.GLASS, Material.GLASS_PANE));
                byRole.put(PaletteRole.ROOF, List.of(roof, Material.COBBLESTONE, planks));
            }
            case REFINED -> {
                // Reprezentativní: cihly / tesaný kámen, tabulková okna.
                Material wall = choose(rng, Material.BRICKS, Material.STONE_BRICKS);
                Material roof = choose(rng, Material.BRICKS, Material.STONE_BRICKS,
                        Material.COBBLESTONE);
                byRole.put(PaletteRole.FOUNDATION,
                        List.of(Material.STONE_BRICKS, Material.STONE, Material.COBBLESTONE));
                byRole.put(PaletteRole.WALL, List.of(wall, planks));
                byRole.put(PaletteRole.WALL_ACCENT, List.of(log));
                // Sklo (ne tabule): boti ho umí vytavit z písku, tabule zatím ne –
                // tak se reprezentativní okna vůbec zasklí. Tabule zůstává přijatelná.
                byRole.put(PaletteRole.WINDOW, List.of(Material.GLASS, Material.GLASS_PANE));
                byRole.put(PaletteRole.ROOF,
                        List.of(roof, Material.STONE_BRICKS, Material.COBBLESTONE));
            }
            default -> throw new IllegalStateException("neznámý tier: " + tier);
        }
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
