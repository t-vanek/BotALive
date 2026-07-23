package dev.botalive.core.inventory;

import org.bukkit.Material;

import java.util.Set;

/**
 * Katalog <b>non-blokových itemů</b> – sourozenec {@link Materials}.
 *
 * <p>Zatímco {@link Materials} třídí bloky (rudy, odpad, stavební kvádry),
 * tenhle katalog třídí <b>itemy</b>: nástroje, zbraně, brnění, jídlo, lektvary,
 * suroviny vaření, osivo, dopravu, utility, barviva, palivo, cennosti a dropy
 * mobů. Dřív žila klasifikace roztroušená ({@link InventoryHelper} tool/armor/
 * food, {@link FurnaceService} fuel, {@link ItemVariants} varianty) – tady je
 * jeden zdroj pravdy pro „jaký druh itemu to je".</p>
 *
 * <p>Kategorie se <b>překrývají</b> (golden_carrot je jídlo i surovina vaření,
 * coal je palivo i cennost), proto katalog dává boolean predikáty (povolují
 * překryv) a k tomu {@link #primaryCategory} pro jednu výstižnou nálepku
 * (řazení, zobrazení). Klasifikace stojí na konstantách a názvových vzorech –
 * funguje i v testech bez server Registry (jediná výjimka {@link #isFood}
 * deleguje na {@link InventoryHelper#isFood} s vlastním fallbackem).</p>
 */
public final class Items {

    private Items() {
    }

    /** Hlavní kategorie itemu (jedna výstižná nálepka; predikáty povolují překryv). */
    public enum ItemCategory {
        /** Nástroj (krumpáč, sekera, lopata, motyka, nůžky, křesadlo, udice…). */
        TOOL,
        /** Zbraň (meč, luk, kuše, trojzubec). */
        WEAPON,
        /** Střelivo (šípy, ohňostroj do kuše). */
        AMMO,
        /** Brnění a nositelné (helma/plát/kalhoty/boty, štít, elytra). */
        ARMOR,
        /** Jídlo. */
        FOOD,
        /** Lektvar. */
        POTION,
        /** Surovina vaření. */
        BREWING,
        /** Osivo a sazenice. */
        SEED,
        /** Doprava (loďky, vozíky, koleje, sedlo, elytra). */
        TRANSPORT,
        /** Utility (kýble, kompas, mapa, jmenovka, vodítko, oko Enderu…). */
        UTILITY,
        /** Barvivo. */
        DYE,
        /** Palivo do pece. */
        FUEL,
        /** Cennost / měna. */
        VALUABLE,
        /** Drop moba (surovina craftu). */
        MOB_DROP,
        /** Blok (deleguje na {@link Materials}). */
        BLOCK,
        /** Nezařazené. */
        OTHER
    }

    // ==================================================================
    // Nástroje a zbraně
    // ==================================================================

    /** Utility nástroje bez krumpáče/sekery/… (poznají se jen jménem). */
    private static final Set<Material> SPECIAL_TOOLS = Set.of(
            Material.SHEARS, Material.FLINT_AND_STEEL, Material.FISHING_ROD,
            Material.BRUSH, Material.SPYGLASS, Material.CARROT_ON_A_STICK,
            Material.WARPED_FUNGUS_ON_A_STICK);

    /**
     * Nástroj (krumpáč, sekera, lopata, motyka + nůžky, křesadlo, udice…)?
     * Meč je zbraň, ne nástroj ({@link #isWeapon}).
     *
     * @param material materiál ({@code null} = ne)
     * @return {@code true} pro nástroj
     */
    public static boolean isTool(Material material) {
        if (material == null) {
            return false;
        }
        String name = material.name();
        if (name.endsWith("_PICKAXE") || name.endsWith("_SHOVEL") || name.endsWith("_HOE")
                || (name.endsWith("_AXE") && !name.endsWith("_PICKAXE"))) {
            return true;
        }
        return SPECIAL_TOOLS.contains(material);
    }

    /** @param material materiál @return {@code true} pro krumpáč (libovolný tier) */
    public static boolean isPickaxe(Material material) {
        return InventoryHelper.isTool(material, InventoryHelper.ToolType.PICKAXE);
    }

    /** @param material materiál @return {@code true} pro sekeru (ne krumpáč) */
    public static boolean isAxe(Material material) {
        return InventoryHelper.isTool(material, InventoryHelper.ToolType.AXE);
    }

    /** @param material materiál @return {@code true} pro lopatu */
    public static boolean isShovel(Material material) {
        return InventoryHelper.isTool(material, InventoryHelper.ToolType.SHOVEL);
    }

    /** @param material materiál @return {@code true} pro motyku */
    public static boolean isHoe(Material material) {
        return InventoryHelper.isTool(material, InventoryHelper.ToolType.HOE);
    }

    /** @param material materiál @return {@code true} pro meč */
    public static boolean isSword(Material material) {
        return InventoryHelper.isTool(material, InventoryHelper.ToolType.SWORD);
    }

    /** @param material materiál @return {@code true} pro dálkovou zbraň (luk, kuše, trojzubec) */
    public static boolean isRangedWeapon(Material material) {
        return material == Material.BOW || material == Material.CROSSBOW
                || material == Material.TRIDENT;
    }

    /**
     * Zbraň vystřelující šípy (luk, kuše) – bez trojzubce, který se vrhá.
     * Rozhodovací gate „mám čím střílet šípy" (lov, drakovi krystaly, wither).
     *
     * @param material materiál
     * @return {@code true} pro luk nebo kuši
     */
    public static boolean isBow(Material material) {
        return material == Material.BOW || material == Material.CROSSBOW;
    }

    /** @param material materiál @return {@code true} pro zbraň nablízko (meč, trojzubec) */
    public static boolean isMeleeWeapon(Material material) {
        return isSword(material) || material == Material.TRIDENT;
    }

    /** @param material materiál @return {@code true} pro zbraň (meč nablízko i dálková) */
    public static boolean isWeapon(Material material) {
        return isSword(material) || isRangedWeapon(material);
    }

    /** @param material materiál @return {@code true} pro střelivo (šípy, ohňostroj do kuše) */
    public static boolean isAmmo(Material material) {
        return material == Material.ARROW || material == Material.SPECTRAL_ARROW
                || material == Material.TIPPED_ARROW || material == Material.FIREWORK_ROCKET;
    }

    // ==================================================================
    // Brnění a nositelné
    // ==================================================================

    /**
     * Kus brnění (helma/plát/kalhoty/boty libovolného tieru vč. želvího krunýře)?
     *
     * @param material materiál
     * @return {@code true} pro brnění
     */
    public static boolean isArmor(Material material) {
        return InventoryHelper.armorSlot(material) >= 0;
    }

    /** @param material materiál @return {@code true} pro nositelné (brnění nebo elytra) */
    public static boolean isWearable(Material material) {
        return isArmor(material) || material == Material.ELYTRA;
    }

    /** @param material materiál @return {@code true} pro štít */
    public static boolean isShield(Material material) {
        return material == Material.SHIELD;
    }

    // ==================================================================
    // Jídlo, lektvary, vaření
    // ==================================================================

    /** @param material materiál @return {@code true} pro jídlo ({@link InventoryHelper#isFood}) */
    public static boolean isFood(Material material) {
        return material != null && InventoryHelper.isFood(material);
    }

    /** @param material materiál @return {@code true} pro lektvar (i splash/lingering) */
    public static boolean isPotion(Material material) {
        return material == Material.POTION || material == Material.SPLASH_POTION
                || material == Material.LINGERING_POTION;
    }

    /** Suroviny vaření (základ, modifikátory efektu, přísady). */
    private static final Set<Material> BREWING_INGREDIENTS = Set.of(
            Material.NETHER_WART, Material.BLAZE_POWDER, Material.GLASS_BOTTLE,
            Material.FERMENTED_SPIDER_EYE, Material.MAGMA_CREAM, Material.GHAST_TEAR,
            Material.GLISTERING_MELON_SLICE, Material.GOLDEN_CARROT, Material.RABBIT_FOOT,
            Material.PUFFERFISH, Material.PHANTOM_MEMBRANE, Material.SPIDER_EYE,
            Material.SUGAR, Material.GUNPOWDER, Material.DRAGON_BREATH, Material.REDSTONE,
            Material.GLOWSTONE_DUST);

    /** @param material materiál @return {@code true} pro surovinu vaření */
    public static boolean isBrewingIngredient(Material material) {
        return material != null && BREWING_INGREDIENTS.contains(material);
    }

    // ==================================================================
    // Farmaření
    // ==================================================================

    /** @param material materiál @return {@code true} pro osivo (semena, kakaové boby) */
    public static boolean isSeed(Material material) {
        if (material == null) {
            return false;
        }
        return material.name().endsWith("_SEEDS") || material == Material.NETHER_WART
                || material == Material.COCOA_BEANS;
    }

    /** @param material materiál @return {@code true} pro sazenici stromu */
    public static boolean isSapling(Material material) {
        return material != null && material.name().endsWith("_SAPLING");
    }

    // ==================================================================
    // Doprava, utility, barviva
    // ==================================================================

    private static final Set<Material> TRANSPORT_EXTRA = Set.of(
            Material.SADDLE, Material.ELYTRA, Material.CARROT_ON_A_STICK,
            Material.WARPED_FUNGUS_ON_A_STICK);

    /**
     * Loďka nebo prám (i varianta s truhlou, napříč dřevy vč. bambusového
     * prámu {@code _RAFT}).
     *
     * @param material materiál ({@code null} = ne)
     * @return {@code true} pro lodní item
     */
    public static boolean isBoat(Material material) {
        if (material == null) {
            return false;
        }
        String name = material.name();
        return name.endsWith("_BOAT") || name.endsWith("_RAFT");
    }

    /** @param material materiál @return {@code true} pro vozík (i s truhlou/pecí/násypkou/TNT) */
    public static boolean isMinecart(Material material) {
        if (material == null) {
            return false;
        }
        String name = material.name();
        return name.equals("MINECART") || name.endsWith("_MINECART");
    }

    /** @param material materiál @return {@code true} pro kolej (i powered/detector/activator) */
    public static boolean isRail(Material material) {
        if (material == null) {
            return false;
        }
        String name = material.name();
        return name.equals("RAIL") || name.endsWith("_RAIL");
    }

    /**
     * Dopravní prostředek (loďka/prám, vozík, kolej, sedlo, elytra, prut na
     * striderа)?
     *
     * @param material materiál
     * @return {@code true} pro dopravu
     */
    public static boolean isTransport(Material material) {
        return isBoat(material) || isMinecart(material) || isRail(material)
                || (material != null && TRANSPORT_EXTRA.contains(material));
    }

    /** @param material materiál @return {@code true} pro kýbl (prázdný i plný) */
    public static boolean isBucket(Material material) {
        return material != null
                && (material == Material.BUCKET || material.name().endsWith("_BUCKET"));
    }

    private static final Set<Material> UTILITY_ITEMS = Set.of(
            Material.FLINT_AND_STEEL, Material.COMPASS, Material.RECOVERY_COMPASS,
            Material.CLOCK, Material.MAP, Material.FILLED_MAP, Material.NAME_TAG,
            Material.LEAD, Material.SPYGLASS, Material.ENDER_EYE, Material.FIRE_CHARGE,
            Material.SHEARS, Material.BRUSH, Material.BUNDLE);

    /** @param material materiál @return {@code true} pro utility (kýbl, kompas, mapa, jmenovka…) */
    public static boolean isUtility(Material material) {
        return isBucket(material) || (material != null && UTILITY_ITEMS.contains(material));
    }

    /** @param material materiál @return {@code true} pro barvivo (vč. kostní moučky) */
    public static boolean isDye(Material material) {
        return material != null
                && (material.name().endsWith("_DYE") || material == Material.BONE_MEAL);
    }

    // ==================================================================
    // Palivo
    // ==================================================================

    private static final Set<Material> FUEL_EXTRA = Set.of(
            Material.COAL, Material.CHARCOAL, Material.COAL_BLOCK, Material.STICK,
            Material.BAMBOO, Material.BLAZE_ROD, Material.LAVA_BUCKET,
            Material.DRIED_KELP_BLOCK);

    /**
     * Faktické palivo do pece (uhlí, dřevo, klacky, bambus, blaze rod, láva).
     * Pozor: {@link FurnaceService} má vlastní <b>politiku</b>, co bot ochotně
     * spálí (užší) – tohle je věcná klasifikace „hoří ve peci".
     *
     * @param material materiál
     * @return {@code true} pro palivo
     */
    public static boolean isFuel(Material material) {
        if (material == null) {
            return false;
        }
        String name = material.name();
        // Netherové dřevo (crimson/warped) NEHOŘÍ – vyloučit, jinak by je vzor
        // _PLANKS/_STEM/_HYPHAE nesprávně označil za palivo.
        if (name.startsWith("CRIMSON_") || name.startsWith("WARPED_")) {
            return false;
        }
        if (name.endsWith("_PLANKS") || name.endsWith("_LOG") || name.endsWith("_WOOD")
                || name.endsWith("_STEM") || name.endsWith("_HYPHAE")) {
            return true;
        }
        return FUEL_EXTRA.contains(material);
    }

    // ==================================================================
    // Cennosti a dropy mobů
    // ==================================================================

    private static final Set<Material> VALUABLE_ITEMS = Set.of(
            Material.NETHER_STAR, Material.TOTEM_OF_UNDYING, Material.ENCHANTED_BOOK,
            Material.EXPERIENCE_BOTTLE, Material.HEART_OF_THE_SEA,
            Material.ENCHANTED_GOLDEN_APPLE, Material.NETHERITE_INGOT,
            Material.NETHERITE_SCRAP, Material.DRAGON_EGG);

    /**
     * Cennost nebo měna nad rámec bankovatelných surovin ({@link
     * Materials#isBankable}) – nether star, totem, enchant kniha, kovářské
     * šablony, hudební disky…
     *
     * @param material materiál
     * @return {@code true} pro cennost
     */
    /** @param material materiál @return {@code true} pro kovářskou šablonu (netherite/ozdoby) */
    public static boolean isSmithingTemplate(Material material) {
        return material != null && material.name().endsWith("_SMITHING_TEMPLATE");
    }

    /** @param material materiál @return {@code true} pro hudební disk */
    public static boolean isMusicDisc(Material material) {
        return material != null && material.name().startsWith("MUSIC_DISC");
    }

    /** @param material materiál @return {@code true} pro spawn vajíčko moba */
    public static boolean isSpawnEgg(Material material) {
        return material != null && material.name().endsWith("_SPAWN_EGG");
    }

    /** @param material materiál @return {@code true} pro koňské brnění */
    public static boolean isHorseArmor(Material material) {
        return material != null && material.name().endsWith("_HORSE_ARMOR");
    }

    public static boolean isValuable(Material material) {
        if (material == null) {
            return false;
        }
        return Materials.isBankable(material) || isSmithingTemplate(material)
                || isMusicDisc(material) || VALUABLE_ITEMS.contains(material);
    }

    private static final Set<Material> MOB_DROPS = Set.of(
            Material.STRING, Material.FEATHER, Material.LEATHER, Material.RABBIT_HIDE,
            Material.BONE, Material.GUNPOWDER, Material.SLIME_BALL, Material.ENDER_PEARL,
            Material.BLAZE_ROD, Material.SPIDER_EYE, Material.GHAST_TEAR,
            Material.MAGMA_CREAM, Material.INK_SAC, Material.GLOW_INK_SAC,
            Material.PHANTOM_MEMBRANE, Material.RABBIT_FOOT, Material.ROTTEN_FLESH,
            Material.PRISMARINE_SHARD, Material.PRISMARINE_CRYSTALS,
            Material.NAUTILUS_SHELL, Material.SHULKER_SHELL, Material.HONEYCOMB,
            Material.NETHER_STAR, Material.WITHER_SKELETON_SKULL, Material.DRAGON_BREATH);

    /** @param material materiál @return {@code true} pro drop moba (surovina craftu) */
    public static boolean isMobDrop(Material material) {
        return material != null && MOB_DROPS.contains(material);
    }

    // ==================================================================
    // Kovy, drahokamy, jídlo, knihy a další podkategorie
    // ==================================================================

    /** @param material materiál @return {@code true} pro ingot (železný/zlatý/měděný/netheritový) */
    public static boolean isIngot(Material material) {
        return material != null && material.name().endsWith("_INGOT");
    }

    /** @param material materiál @return {@code true} pro nuget (železný/zlatý) */
    public static boolean isNugget(Material material) {
        return material != null && material.name().endsWith("_NUGGET");
    }

    /** @param material materiál @return {@code true} pro surový kov (ruda-drop, ne blok) */
    public static boolean isRawMetal(Material material) {
        if (material == null) {
            return false;
        }
        String name = material.name();
        return name.startsWith("RAW_") && !name.endsWith("_BLOCK");
    }

    private static final Set<Material> GEMS = Set.of(
            Material.DIAMOND, Material.EMERALD, Material.LAPIS_LAZULI,
            Material.AMETHYST_SHARD, Material.QUARTZ);

    /** @param material materiál @return {@code true} pro drahý kámen (diamant, smaragd…) */
    public static boolean isGem(Material material) {
        return material != null && GEMS.contains(material);
    }

    private static final Set<Material> RAW_FOOD = Set.of(
            Material.BEEF, Material.PORKCHOP, Material.CHICKEN, Material.MUTTON,
            Material.RABBIT, Material.COD, Material.SALMON);

    /** @param material materiál @return {@code true} pro syrové maso/rybu (k upečení) */
    public static boolean isRawFood(Material material) {
        return material != null && RAW_FOOD.contains(material);
    }

    /** @param material materiál @return {@code true} pro pečené jídlo (COOKED_*) */
    public static boolean isCookedFood(Material material) {
        return material != null && material.name().startsWith("COOKED_");
    }

    private static final Set<Material> BOOKS = Set.of(
            Material.BOOK, Material.WRITABLE_BOOK, Material.WRITTEN_BOOK,
            Material.ENCHANTED_BOOK, Material.KNOWLEDGE_BOOK);

    /** @param material materiál @return {@code true} pro knihu (i psací/enchantovanou) */
    public static boolean isBook(Material material) {
        return material != null && BOOKS.contains(material);
    }

    /** @param material materiál @return {@code true} pro vlajku */
    public static boolean isBanner(Material material) {
        return material != null && material.name().endsWith("_BANNER");
    }

    /** @param material materiál @return {@code true} pro postel */
    public static boolean isBed(Material material) {
        return material != null && material.name().endsWith("_BED");
    }

    /** @param material materiál @return {@code true} pro hlavu/lebku moba */
    public static boolean isHead(Material material) {
        if (material == null) {
            return false;
        }
        String name = material.name();
        return name.endsWith("_HEAD") || name.endsWith("_SKULL");
    }

    /** @param material materiál @return {@code true} pro mapu (prázdnou i vyplněnou) */
    public static boolean isMap(Material material) {
        return material == Material.MAP || material == Material.FILLED_MAP;
    }

    private static final Set<Material> THROWABLES = Set.of(
            Material.SNOWBALL, Material.EGG, Material.ENDER_PEARL,
            Material.EXPERIENCE_BOTTLE, Material.SPLASH_POTION, Material.LINGERING_POTION,
            Material.FIRE_CHARGE);

    /** @param material materiál @return {@code true} pro vrhací item (koule, vejce, splash…) */
    public static boolean isThrowable(Material material) {
        return material != null && THROWABLES.contains(material);
    }

    private static final Set<Material> CROPS = Set.of(
            Material.WHEAT, Material.CARROT, Material.POTATO, Material.BEETROOT,
            Material.PUMPKIN, Material.MELON, Material.MELON_SLICE, Material.NETHER_WART,
            Material.SUGAR_CANE, Material.SWEET_BERRIES, Material.GLOW_BERRIES,
            Material.COCOA_BEANS, Material.BAMBOO);

    /** @param material materiál @return {@code true} pro sklizenou zemědělskou produkci */
    public static boolean isCrop(Material material) {
        return material != null && CROPS.contains(material);
    }

    /** @param material materiál @return {@code true} pro helmu (i želví krunýř) */
    public static boolean isHelmet(Material material) {
        return InventoryHelper.armorSlot(material) == 3;
    }

    /** @param material materiál @return {@code true} pro prsní plát */
    public static boolean isChestplate(Material material) {
        return InventoryHelper.armorSlot(material) == 2;
    }

    /** @param material materiál @return {@code true} pro kalhoty (nohavice) */
    public static boolean isLeggings(Material material) {
        return InventoryHelper.armorSlot(material) == 1;
    }

    /** @param material materiál @return {@code true} pro boty */
    public static boolean isBoots(Material material) {
        return InventoryHelper.armorSlot(material) == 0;
    }

    // ==================================================================
    // Souhrnná kategorie
    // ==================================================================

    /**
     * Jedna výstižná kategorie itemu (pro řazení/zobrazení). Překrývající se
     * itemy řeší priorita: výbava a spotřebák před surovinovou nálepkou (coal
     * → FUEL ne VALUABLE; golden_carrot → FOOD ne BREWING). Bloky delegují na
     * {@link Materials}.
     *
     * @param material materiál ({@code null} = OTHER)
     * @return hlavní kategorie
     */
    public static ItemCategory primaryCategory(Material material) {
        if (material == null) {
            return ItemCategory.OTHER;
        }
        if (isWearable(material) || isShield(material)) {
            return ItemCategory.ARMOR;
        }
        if (isWeapon(material)) {
            return ItemCategory.WEAPON;
        }
        if (isTool(material)) {
            return ItemCategory.TOOL;
        }
        if (isAmmo(material)) {
            return ItemCategory.AMMO;
        }
        if (isPotion(material)) {
            return ItemCategory.POTION;
        }
        if (isFood(material)) {
            return ItemCategory.FOOD;
        }
        if (isTransport(material)) {
            return ItemCategory.TRANSPORT;
        }
        if (isSeed(material) || isSapling(material)) {
            return ItemCategory.SEED;
        }
        if (isBrewingIngredient(material)) {
            return ItemCategory.BREWING;
        }
        if (isUtility(material)) {
            return ItemCategory.UTILITY;
        }
        if (isDye(material)) {
            return ItemCategory.DYE;
        }
        if (isFuel(material)) {
            return ItemCategory.FUEL;
        }
        if (isValuable(material)) {
            return ItemCategory.VALUABLE;
        }
        if (isMobDrop(material)) {
            return ItemCategory.MOB_DROP;
        }
        if (Materials.isBuildingBlock(material) || Materials.isOre(material)
                || Materials.isBulkJunk(material)) {
            return ItemCategory.BLOCK;
        }
        return ItemCategory.OTHER;
    }
}
