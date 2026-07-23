package dev.botalive.core.inventory;

import org.bukkit.Material;

import java.util.Map;
import java.util.Set;

/**
 * Centrální katalog vanilla materiálů – <b>jeden zdroj pravdy</b> pro to, co
 * je ruda, cennost, odpad nebo stavební blok.
 *
 * <p>Dřív žila klasifikace roztroušená v ručně vybraných podmnožinách
 * (MineGoal ORE_VALUES, ContainerService JUNK, InventoryHelper BANK_RESERVE,
 * BotNeeds tier gating). Tenhle katalog je sjednocuje a rozšiřuje na <b>celou
 * vanillu</b>: každá ruda se váhne a tier-gatuje, každý ingot/drahokam/nuget
 * se banku­je, každý sypký kámen je odpad, každý plný kvádr (prkna, kámen,
 * cihly, klády) je stavební blok.</p>
 *
 * <p><b>Bez registry:</b> klasifikace stojí na porovnání konstant a názvových
 * vzorech ({@code endsWith}/{@code contains}), takže funguje i v jednotkových
 * testech bez běžícího serveru (na rozdíl od {@code Material.isBlock()} /
 * {@code Tag.*.isTagged()}, které sahají na server Registry). Čisté a
 * deterministické.</p>
 */
public final class Materials {

    private Materials() {
    }

    // ==================================================================
    // Rudy, těžba, ekonomika
    // ==================================================================

    /** Ekonomická hodnota rudy podle rodiny (klíč = název bez prefixu DEEPSLATE_). */
    private static final Map<String, Double> ORE_VALUE = Map.ofEntries(
            Map.entry("COAL_ORE", 2.0),
            Map.entry("COPPER_ORE", 2.5),
            Map.entry("IRON_ORE", 5.0),
            Map.entry("GOLD_ORE", 8.0),
            Map.entry("REDSTONE_ORE", 4.0),
            Map.entry("LAPIS_ORE", 6.0),
            Map.entry("DIAMOND_ORE", 25.0),
            Map.entry("EMERALD_ORE", 20.0),
            Map.entry("NETHER_GOLD_ORE", 1.5),
            Map.entry("NETHER_QUARTZ_ORE", 1.5));

    /**
     * Je materiál ruda (vč. deepslate a netherových variant a ancient debris)?
     *
     * @param material materiál ({@code null} = ne)
     * @return {@code true} pro těžitelnou rudu
     */
    public static boolean isOre(Material material) {
        return material != null
                && (material.name().endsWith("_ORE") || material == Material.ANCIENT_DEBRIS);
    }

    /**
     * Ekonomická hodnota vytěžené rudy (mint do peněženky, „cenná ruda" pro
     * sledování žíly a paměť).
     *
     * @param material materiál
     * @return hodnota, nebo {@code null} pokud jde o bezcennou/žádnou rudu
     */
    public static Double oreValue(Material material) {
        if (material == Material.ANCIENT_DEBRIS) {
            return 30.0;
        }
        if (material == null || !material.name().endsWith("_ORE")) {
            return null;
        }
        return ORE_VALUE.get(valueKey(material));
    }

    /** @param material materiál @return {@code true} pokud má ruda ekonomickou hodnotu */
    public static boolean isValuableOre(Material material) {
        return oreValue(material) != null;
    }

    /** Klíč hodnoty: název bez prefixu {@code DEEPSLATE_} (netherové zůstávají zvlášť). */
    private static String valueKey(Material material) {
        String name = material.name();
        return name.startsWith("DEEPSLATE_") ? name.substring("DEEPSLATE_".length()) : name;
    }

    /**
     * Normalizovaná rodina rudy ({@code DEEPSLATE_X_ORE} → {@code X_ORE}) –
     * pro sledování žíly a mapu hloubek. Netherový prefix se nechává.
     *
     * @param material materiál
     * @return rodina rudy
     */
    public static String oreFamily(Material material) {
        String name = material.name();
        return name.startsWith("DEEPSLATE_") ? name.substring("DEEPSLATE_".length()) : name;
    }

    /**
     * Minimální tier krumpáče, aby z bloku něco padalo (vanilla pravidla).
     *
     * @param block těžený blok
     * @return tier dle {@link InventoryHelper#toolTier} (1 = dřevěný stačí)
     */
    public static int requiredPickTier(Material block) {
        String name = block.name();
        if (block == Material.ANCIENT_DEBRIS || block == Material.OBSIDIAN
                || block == Material.CRYING_OBSIDIAN) {
            return 5; // jen diamantový a lepší
        }
        if (block == Material.NETHER_GOLD_ORE || block == Material.NETHER_QUARTZ_ORE) {
            return 1; // netherové rudy padají z libovolného krumpáče
        }
        if (name.contains("DIAMOND_ORE") || name.contains("EMERALD_ORE")
                || name.contains("GOLD_ORE") || name.contains("REDSTONE_ORE")) {
            return 4; // železný a lepší
        }
        if (name.contains("IRON_ORE") || name.contains("LAPIS_ORE")
                || name.contains("COPPER_ORE")) {
            return 3; // kamenný a lepší
        }
        return 1; // uhlí, kámen, glowstone – stačí dřevěný
    }

    // ==================================================================
    // Bankovatelné cennosti (rudy-dropy, ingoty, drahokamy, nugety)
    // ==================================================================

    /**
     * Pracovní rezerva komodity: nad tento počet kusů je zbytek přebytek k
     * uložení do truhly. Rezerva je štědrá schválně – bot si nechá dost na
     * tavení/výrobu/opravy a bankuje jen skutečný přebytek. Netheritový
     * řetězec (ancient debris, scrap, ingot – příliš vzácný) se <b>nebankuje</b>.
     *
     * @param material materiál
     * @return rezerva v kusech (0 = nebankovatelné)
     */
    public static int bankReserve(Material material) {
        if (material == null) {
            return 0;
        }
        String name = material.name();
        // Surové kovy (RAW_IRON/GOLD/COPPER – ne bloky RAW_*_BLOCK).
        if (name.startsWith("RAW_") && !name.endsWith("_BLOCK")) {
            return 16;
        }
        // Nugety (železný/zlatý).
        if (name.endsWith("_NUGGET")) {
            return 32;
        }
        return switch (material) {
            case IRON_INGOT, COPPER_INGOT -> 16;
            case GOLD_INGOT -> 8;
            case COAL, CHARCOAL -> 32;
            case DIAMOND, EMERALD -> 12;
            case LAPIS_LAZULI, REDSTONE, QUARTZ, GLOWSTONE_DUST -> 32;
            case AMETHYST_SHARD -> 16;
            default -> 0;
        };
    }

    /**
     * Je materiál <b>bankovatelná komodita</b> (ruda-drop, ingot, uhlí, drahý
     * kámen, nuget)? Cennost, kterou má smysl nad rezervu uložit do truhly –
     * na rozdíl od odpadu ({@link #isBulkJunk}) a spotřebáku (jídlo, nástroje,
     * stavební bloky). Netherit se schválně nebankuje.
     *
     * @param material materiál ({@code null} = ne)
     * @return {@code true} pro komoditu s pracovní rezervou
     */
    public static boolean isBankable(Material material) {
        return bankReserve(material) > 0;
    }

    // ==================================================================
    // Odpad (sypké kameny a hlíny k uložení do truhly)
    // ==================================================================

    /** Bezcenný sypký materiál (kámen, hlína, štěrk) – přebytek do truhly. */
    private static final Set<Material> BULK_JUNK = Set.of(
            Material.COBBLESTONE, Material.MOSSY_COBBLESTONE, Material.COBBLED_DEEPSLATE,
            Material.STONE, Material.DEEPSLATE, Material.GRANITE, Material.DIORITE,
            Material.ANDESITE, Material.TUFF, Material.CALCITE, Material.BASALT,
            Material.SMOOTH_BASALT, Material.BLACKSTONE, Material.DRIPSTONE_BLOCK,
            Material.NETHERRACK, Material.DIRT, Material.COARSE_DIRT, Material.ROOTED_DIRT,
            Material.GRAVEL, Material.SAND, Material.RED_SAND, Material.MUD,
            Material.ROTTEN_FLESH);

    /**
     * Je materiál <b>bezcenný sypký odpad</b> (kámen, hlína, štěrk, netherrack)?
     * Přebytek se ukládá do truhly ({@link ContainerService#depositJunk}).
     *
     * @param material materiál ({@code null} = ne)
     * @return {@code true} pro odpad k uložení
     */
    public static boolean isBulkJunk(Material material) {
        return material != null && BULK_JUNK.contains(material);
    }

    // ==================================================================
    // Stavební bloky (plné kvádry použitelné na stavbu/pilíř/most)
    // ==================================================================

    /** Přírodní i běžné řemeslné plné kvádry, které nezachytí názvový vzor. */
    private static final Set<Material> BUILDING_CUBES = Set.of(
            Material.STONE, Material.SMOOTH_STONE, Material.COBBLESTONE,
            Material.MOSSY_COBBLESTONE, Material.GRANITE, Material.POLISHED_GRANITE,
            Material.DIORITE, Material.POLISHED_DIORITE, Material.ANDESITE,
            Material.POLISHED_ANDESITE, Material.DEEPSLATE, Material.COBBLED_DEEPSLATE,
            Material.POLISHED_DEEPSLATE, Material.TUFF, Material.CALCITE,
            Material.DRIPSTONE_BLOCK, Material.NETHERRACK, Material.BLACKSTONE,
            Material.POLISHED_BLACKSTONE, Material.BASALT, Material.SMOOTH_BASALT,
            Material.POLISHED_BASALT, Material.END_STONE, Material.SANDSTONE,
            Material.SMOOTH_SANDSTONE, Material.CUT_SANDSTONE, Material.RED_SANDSTONE,
            Material.SMOOTH_RED_SANDSTONE, Material.CUT_RED_SANDSTONE, Material.PRISMARINE,
            Material.DARK_PRISMARINE, Material.DIRT, Material.COARSE_DIRT,
            Material.ROOTED_DIRT, Material.PACKED_MUD, Material.CLAY, Material.TERRACOTTA,
            Material.QUARTZ_BLOCK, Material.SMOOTH_QUARTZ);

    /** Názvové přípony, které NIKDY nejsou plný stavební kvádr (dělené/dekor/funkční). */
    private static final Set<String> NON_CUBE_SUFFIX = Set.of(
            "_SLAB", "_STAIRS", "_WALL", "_FENCE", "_FENCE_GATE", "_DOOR", "_TRAPDOOR",
            "_PANE", "_BUTTON", "_PRESSURE_PLATE", "_SIGN", "_HANGING_SIGN", "_CARPET",
            "_BANNER", "_BED", "_ORE", "_SAPLING", "_LEAVES", "_POWDER");

    /**
     * Je materiál <b>plný stavební kvádr</b> vhodný na stavbu, pilíř nebo most
     * (prkna, klády, kámen, cihly, betony, terakota…)? Vyloučené jsou dělené
     * bloky (schody, plotny, zídky), dekor a cennosti – aby jimi bot nepilíroval
     * ani je nepovažoval za rozpočet pokládek.
     *
     * @param material materiál ({@code null} = ne)
     * @return {@code true} pro stavební blok
     */
    public static boolean isBuildingBlock(Material material) {
        if (material == null) {
            return false;
        }
        String name = material.name();
        for (String suffix : NON_CUBE_SUFFIX) {
            if (name.endsWith(suffix)) {
                return false;
            }
        }
        if (name.contains("GLASS") || material == Material.REDSTONE) {
            return false; // sklo (i tabule) a redstone prach nejsou kvádr
        }
        // Dřevo: prkna, klády, kmeny, kůra (i oloupané varianty).
        if (isWood(material)) {
            return true;
        }
        // Cihlové kvádry (kamenné, netherové, endové, bahenní, prismarine…).
        if (name.endsWith("_BRICKS") || material == Material.BRICKS) {
            return true;
        }
        // Barevné plné kvádry (16 odstínů betonu a terakoty).
        if (name.endsWith("_CONCRETE") || name.endsWith("_TERRACOTTA")) {
            return true;
        }
        return BUILDING_CUBES.contains(material);
    }

    /** @param material materiál @return {@code true} pro kládu/kmen (náhradní cíl těžby) */
    public static boolean isLog(Material material) {
        if (material == null) {
            return false;
        }
        String name = material.name();
        return (name.endsWith("_LOG") || name.endsWith("_STEM")) && !name.startsWith("STRIPPED_");
    }

    /** @param material materiál @return {@code true} pro prkna (libovolné dřevo) */
    public static boolean isPlanks(Material material) {
        return material != null && material.name().endsWith("_PLANKS");
    }

    /** @param material materiál @return {@code true} pro listí (libovolného stromu) */
    public static boolean isLeaves(Material material) {
        return material != null && material.name().endsWith("_LEAVES");
    }

    /** Přírodní kámen overworldu (z čeho je svět udělaný). */
    private static final Set<Material> BASE_STONE = Set.of(
            Material.STONE, Material.GRANITE, Material.DIORITE, Material.ANDESITE,
            Material.DEEPSLATE, Material.TUFF, Material.CALCITE);

    /**
     * Přírodní kámen overworldu (kámen, žula, diorit, andezit, deepslate, tuff,
     * kalcit) – co bot při kopání běžně potká jako „jen kámen".
     *
     * @param material materiál ({@code null} = ne)
     * @return {@code true} pro přírodní kámen
     */
    public static boolean isStone(Material material) {
        return material != null && BASE_STONE.contains(material);
    }

    /**
     * Je materiál <b>dřevo</b> – prkna, klády, kmeny, dřevo nebo kůra (i
     * oloupané varianty, napříč celou vanillou vč. crimson/warped)? Tj. „mám
     * z čeho craftit / stavět dřevem". Širší než {@link #isLog} (ten je jen
     * těžební cíl, bez prken a oloupaných).
     *
     * @param material materiál ({@code null} = ne)
     * @return {@code true} pro dřevěný materiál
     */
    public static boolean isWood(Material material) {
        if (material == null) {
            return false;
        }
        String name = material.name();
        return name.endsWith("_PLANKS") || name.endsWith("_LOG") || name.endsWith("_WOOD")
                || name.endsWith("_STEM") || name.endsWith("_HYPHAE");
    }

    // ==================================================================
    // Tvary bloků (dělené a průchozí – doplněk k plným kvádrům)
    // ==================================================================

    /** @param material materiál @return {@code true} pro plotnu (půlblok) */
    public static boolean isSlab(Material material) {
        return material != null && material.name().endsWith("_SLAB");
    }

    /** @param material materiál @return {@code true} pro schody */
    public static boolean isStairs(Material material) {
        return material != null && material.name().endsWith("_STAIRS");
    }

    /** @param material materiál @return {@code true} pro zídku */
    public static boolean isWall(Material material) {
        return material != null && material.name().endsWith("_WALL");
    }

    /** @param material materiál @return {@code true} pro plot (ne branku) */
    public static boolean isFence(Material material) {
        return material != null && material.name().endsWith("_FENCE");
    }

    /** @param material materiál @return {@code true} pro branku plotu */
    public static boolean isFenceGate(Material material) {
        return material != null && material.name().endsWith("_FENCE_GATE");
    }

    /** @param material materiál @return {@code true} pro dveře (ne padací) */
    public static boolean isDoor(Material material) {
        return material != null && material.name().endsWith("_DOOR");
    }

    /** @param material materiál @return {@code true} pro padací dveře */
    public static boolean isTrapdoor(Material material) {
        return material != null && material.name().endsWith("_TRAPDOOR");
    }

    // ==================================================================
    // Materiálové rodiny (dekor, sypké, led, minerální bloky)
    // ==================================================================

    /** @param material materiál @return {@code true} pro sklo (tabule i plné, ne láhev) */
    public static boolean isGlass(Material material) {
        if (material == null) {
            return false;
        }
        String name = material.name();
        return name.endsWith("GLASS") || name.endsWith("GLASS_PANE");
    }

    /** @param material materiál @return {@code true} pro vlnu */
    public static boolean isWool(Material material) {
        return material != null && material.name().endsWith("_WOOL");
    }

    /** @param material materiál @return {@code true} pro koberec */
    public static boolean isCarpet(Material material) {
        return material != null && material.name().endsWith("_CARPET");
    }

    /** @param material materiál @return {@code true} pro beton (plný, ne prášek) */
    public static boolean isConcrete(Material material) {
        return material != null && material.name().endsWith("_CONCRETE");
    }

    /** @param material materiál @return {@code true} pro betonový prášek (gravitační) */
    public static boolean isConcretePowder(Material material) {
        return material != null && material.name().endsWith("_CONCRETE_POWDER");
    }

    /** @param material materiál @return {@code true} pro terakotu (i glazovanou/barevnou) */
    public static boolean isTerracotta(Material material) {
        return material != null && material.name().endsWith("TERRACOTTA");
    }

    /** @param material materiál @return {@code true} pro deepslate v jakékoli podobě */
    public static boolean isDeepslate(Material material) {
        return material != null && material.name().contains("DEEPSLATE");
    }

    /** @param material materiál @return {@code true} pro písek (obyčejný i červený) */
    public static boolean isSand(Material material) {
        return material == Material.SAND || material == Material.RED_SAND;
    }

    private static final Set<Material> ICE = Set.of(
            Material.ICE, Material.PACKED_ICE, Material.BLUE_ICE, Material.FROSTED_ICE);

    /** @param material materiál @return {@code true} pro led (jakýkoli) */
    public static boolean isIce(Material material) {
        return material != null && ICE.contains(material);
    }

    /**
     * Gravitační blok (padá dolů) – písek, štěrk, betonový prášek, kovadlina.
     *
     * @param material materiál ({@code null} = ne)
     * @return {@code true} pro gravitační blok
     */
    public static boolean isGravityBlock(Material material) {
        if (material == null) {
            return false;
        }
        if (isSand(material) || material == Material.GRAVEL || isConcretePowder(material)) {
            return true;
        }
        return switch (material) {
            case ANVIL, CHIPPED_ANVIL, DAMAGED_ANVIL, SUSPICIOUS_SAND, SUSPICIOUS_GRAVEL,
                 POINTED_DRIPSTONE, DRAGON_EGG -> true;
            default -> false;
        };
    }

    private static final Set<Material> MINERAL_BLOCKS = Set.of(
            Material.IRON_BLOCK, Material.GOLD_BLOCK, Material.DIAMOND_BLOCK,
            Material.EMERALD_BLOCK, Material.COAL_BLOCK, Material.REDSTONE_BLOCK,
            Material.LAPIS_BLOCK, Material.NETHERITE_BLOCK, Material.COPPER_BLOCK,
            Material.RAW_IRON_BLOCK, Material.RAW_GOLD_BLOCK, Material.RAW_COPPER_BLOCK,
            Material.AMETHYST_BLOCK);

    /**
     * Minerální/skladovací blok (nakomprimovaná surovina – železný blok, zlatý
     * blok, blok surové mědi…). Cennost, ne stavební materiál.
     *
     * @param material materiál ({@code null} = ne)
     * @return {@code true} pro minerální blok
     */
    public static boolean isMineralBlock(Material material) {
        return material != null && MINERAL_BLOCKS.contains(material);
    }
}
