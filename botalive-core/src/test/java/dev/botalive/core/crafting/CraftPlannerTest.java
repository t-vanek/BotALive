package dev.botalive.core.crafting;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy kompletní survival crafting progrese (sdílený plánovač).
 */
class CraftPlannerTest {

    /** Sestaví stav z dvojic (materiál, počet); bot nemíří na REFINED. */
    private static CraftPlanner.State state(Object... pairs) {
        return build(false, pairs);
    }

    /** Jako {@link #state}, ale bot míří na reprezentativní dům (masonry). */
    private static CraftPlanner.State stateMasonry(Object... pairs) {
        return build(true, pairs);
    }

    private static CraftPlanner.State build(boolean masonry, Object... pairs) {
        Map<Material, Integer> items = new HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            items.merge((Material) pairs[i], (Integer) pairs[i + 1], Integer::sum);
        }
        Material log = items.keySet().stream()
                .filter(m -> m.name().endsWith("_LOG")).findFirst().orElse(null);
        Material plank = items.keySet().stream()
                .filter(m -> m.name().endsWith("_PLANKS")).findFirst().orElse(null);
        Material wool = items.keySet().stream()
                .filter(m -> m.name().endsWith("_WOOL")).findFirst().orElse(null);
        return new CraftPlanner.State(items, log, plank, Material.COBBLESTONE, wool, masonry);
    }

    /** Kompletně vybavený bot (nic dalšího nedává smysl). */
    private static CraftPlanner.State fullKit() {
        return state(Material.OAK_PLANKS, 4, Material.STICK, 4, Material.COBBLESTONE, 3,
                Material.CRAFTING_TABLE, 1, Material.FURNACE, 1, Material.TORCH, 8,
                Material.DIAMOND_PICKAXE, 1, Material.DIAMOND_SWORD, 1,
                Material.DIAMOND_AXE, 1, Material.STONE_SHOVEL, 1, Material.SHIELD, 1,
                Material.DIAMOND_CHESTPLATE, 1, Material.DIAMOND_LEGGINGS, 1,
                Material.DIAMOND_HELMET, 1, Material.DIAMOND_BOOTS, 1,
                Material.BOW, 1, Material.ARROW, 16, Material.CHEST, 1,
                Material.OAK_BOAT, 1, Material.OAK_DOOR, 1, Material.RED_BED, 1);
    }

    @Test
    void bezSurovinNicNeplanuje() {
        assertNull(CraftPlanner.next(state()));
    }

    @Test
    void ctyriCihlyDajiCihlovyBlok() {
        // Reprezentativní dům (REFINED): ze 4 cihel se složí cihlový blok.
        assertEquals("cihlový blok", CraftPlanner.next(state(Material.BRICK, 4)).id());
        // Pod 4 cihly se blok nesloží.
        assertNull(CraftPlanner.next(state(Material.BRICK, 3)));
        // Se zásobou bloků se už další nemelou (strop).
        assertNull(CraftPlanner.next(state(Material.BRICK, 8, Material.BRICKS, 16)));
    }

    @Test
    void tesaneCihlyJenProStaviteleRefined() {
        // Stavitel REFINED (masonry) ze 4 kamenů složí tesané cihly.
        assertEquals("tesané cihly", CraftPlanner.next(stateMasonry(Material.STONE, 4)).id());
        // Bez masonry se kámen na tesané cihly nemele – nestavitel by plýtval.
        assertNull(CraftPlanner.next(state(Material.STONE, 4)));
        // Se zásobou tesaných cihel už další netřeba (strop).
        assertNull(CraftPlanner.next(
                stateMasonry(Material.STONE, 8, Material.STONE_BRICKS, 16)));
    }

    @Test
    void osivoOdemkneMotyku() {
        // Bot s kamennou výbavou a osivem si dodělá motyku (zorá pole i bez role).
        CraftPlanner.State withSeeds = state(
                Material.OAK_PLANKS, 4, Material.STICK, 4, Material.COBBLESTONE, 4,
                Material.CRAFTING_TABLE, 1, Material.FURNACE, 1, Material.TORCH, 8,
                Material.STONE_PICKAXE, 1, Material.STONE_SWORD, 1, Material.STONE_AXE, 1,
                Material.STONE_SHOVEL, 1, Material.WHEAT_SEEDS, 1);
        assertEquals("motyka", CraftPlanner.next(withSeeds).id());
        // Bez osiva si motyku nikdo nedělá (žádné plýtvání).
        CraftPlanner.State noSeeds = state(
                Material.OAK_PLANKS, 4, Material.STICK, 4, Material.COBBLESTONE, 4,
                Material.CRAFTING_TABLE, 1, Material.FURNACE, 1, Material.TORCH, 8,
                Material.STONE_PICKAXE, 1, Material.STONE_SWORD, 1, Material.STONE_AXE, 1,
                Material.STONE_SHOVEL, 1);
        assertNull(CraftPlanner.next(noSeeds));
    }

    @Test
    void bezPosteleAVlnySeUdelajiNuzky() {
        // Bot bez postele a vlny si s železem udělá nůžky (ostříhá ovce na vlnu).
        Object[] base = {
                Material.OAK_PLANKS, 4, Material.STICK, 4, Material.CRAFTING_TABLE, 1,
                Material.DIAMOND_PICKAXE, 1, Material.DIAMOND_SWORD, 1, Material.DIAMOND_AXE, 1,
                Material.STONE_SHOVEL, 1, Material.FURNACE, 1, Material.TORCH, 8};
        assertEquals("nůžky", CraftPlanner.next(withExtra(base, Material.IRON_INGOT, 2)).id());
        // Bez železa nůžky nevzniknou.
        assertNull(CraftPlanner.next(state(base)));
        // S postelí už nůžky netřeba.
        assertNull(CraftPlanner.next(withExtra(base, Material.IRON_INGOT, 2,
                Material.RED_BED, 1)));
    }

    /** Sestaví stav ze základu + dalších dvojic. */
    private static CraftPlanner.State withExtra(Object[] base, Object... extra) {
        Object[] all = new Object[base.length + extra.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(extra, 0, all, base.length, extra.length);
        return state(all);
    }

    @Test
    void obsidianDiamantKnihaOdemknouEnchantovaciStul() {
        // Samoenchantování: bot s obsidiánem, diamanty a knihou si postaví stůl.
        CraftPlanner.State s = state(
                Material.OAK_PLANKS, 4, Material.STICK, 4, Material.CRAFTING_TABLE, 1,
                Material.DIAMOND_PICKAXE, 1, Material.DIAMOND_SWORD, 1, Material.DIAMOND_AXE, 1,
                Material.STONE_SHOVEL, 1, Material.FURNACE, 1, Material.TORCH, 8,
                Material.OBSIDIAN, 4, Material.DIAMOND, 2, Material.BOOK, 1);
        assertEquals("enchantovací stůl", CraftPlanner.next(s).id());
        // Bez knihy se stůl nedělá – obsidián zůstane na portál.
        CraftPlanner.State noBook = state(
                Material.OAK_PLANKS, 4, Material.STICK, 4, Material.CRAFTING_TABLE, 1,
                Material.DIAMOND_PICKAXE, 1, Material.DIAMOND_SWORD, 1, Material.DIAMOND_AXE, 1,
                Material.STONE_SHOVEL, 1, Material.FURNACE, 1, Material.TORCH, 8,
                Material.OBSIDIAN, 4, Material.DIAMOND, 2);
        assertNull(CraftPlanner.next(noBook));
    }

    @Test
    void progresePrknaTyckyPonkNastroje() {
        assertEquals("prkna", CraftPlanner.next(state(Material.OAK_LOG, 3)).id());
        assertEquals("tyčky", CraftPlanner.next(state(Material.OAK_PLANKS, 4)).id());
        assertEquals("ponk", CraftPlanner.next(
                state(Material.OAK_PLANKS, 5, Material.STICK, 4)).id());
        assertEquals("dřevěný krumpáč", CraftPlanner.next(state(
                Material.OAK_PLANKS, 5, Material.STICK, 4,
                Material.CRAFTING_TABLE, 1)).id());
    }

    @Test
    void kamennaGeneraceVcetneSekeryALopaty() {
        CraftPlanner.State s = state(Material.OAK_PLANKS, 5, Material.STICK, 8,
                Material.COBBLESTONE, 12, Material.CRAFTING_TABLE, 1,
                Material.WOODEN_PICKAXE, 1, Material.WOODEN_SWORD, 1,
                Material.WOODEN_AXE, 1);
        assertEquals("kamenný krumpáč", CraftPlanner.next(s).id());
        CraftPlanner.State s2 = state(Material.OAK_PLANKS, 5, Material.STICK, 8,
                Material.COBBLESTONE, 12, Material.CRAFTING_TABLE, 1,
                Material.STONE_PICKAXE, 1, Material.WOODEN_SWORD, 1,
                Material.WOODEN_AXE, 1);
        assertEquals("kamenný meč", CraftPlanner.next(s2).id());
        CraftPlanner.State s3 = state(Material.OAK_PLANKS, 5, Material.STICK, 8,
                Material.COBBLESTONE, 12, Material.CRAFTING_TABLE, 1,
                Material.STONE_PICKAXE, 1, Material.STONE_SWORD, 1,
                Material.WOODEN_AXE, 1);
        assertEquals("kamenná sekera", CraftPlanner.next(s3).id());
        CraftPlanner.State s4 = state(Material.OAK_PLANKS, 5, Material.STICK, 8,
                Material.COBBLESTONE, 12, Material.CRAFTING_TABLE, 1,
                Material.STONE_PICKAXE, 1, Material.STONE_SWORD, 1,
                Material.STONE_AXE, 1);
        assertEquals("kamenná lopata", CraftPlanner.next(s4).id());
    }

    @Test
    void pecPredZelezem() {
        CraftPlanner.State s = state(Material.OAK_PLANKS, 5, Material.STICK, 8,
                Material.COBBLESTONE, 12, Material.CRAFTING_TABLE, 1,
                Material.STONE_PICKAXE, 1, Material.STONE_SWORD, 1,
                Material.STONE_AXE, 1, Material.STONE_SHOVEL, 1);
        CraftPlanner.Plan pec = CraftPlanner.next(s);
        assertEquals("pec", pec.id());
        assertEquals(8, pec.ingredients().get(Material.COBBLESTONE));
    }

    @Test
    void zeleznaGeneraceStitABrneni() {
        // Základ: kamenná výbava + pec + pochodně, 20 ingotů.
        Object[] base = {Material.OAK_PLANKS, 12, Material.STICK, 8,
                Material.COBBLESTONE, 3, Material.CRAFTING_TABLE, 1,
                Material.FURNACE, 1, Material.TORCH, 8,
                Material.STONE_SWORD, 1, Material.STONE_AXE, 1,
                Material.STONE_SHOVEL, 1, Material.IRON_INGOT, 20};
        CraftPlanner.State s = state(concat(base, Material.STONE_PICKAXE, 1));
        assertEquals("železný krumpáč", CraftPlanner.next(s).id());

        CraftPlanner.State s2 = state(concat(base, Material.IRON_PICKAXE, 1));
        assertEquals("železný meč", CraftPlanner.next(s2).id());

        CraftPlanner.State s3 = state(concat(base, Material.IRON_PICKAXE, 1,
                Material.IRON_SWORD, 1, Material.IRON_AXE, 1));
        assertEquals("štít", CraftPlanner.next(s3).id());

        CraftPlanner.State s4 = state(concat(base, Material.IRON_PICKAXE, 1,
                Material.IRON_SWORD, 1, Material.IRON_AXE, 1, Material.SHIELD, 1));
        assertEquals("železný prsní plát", CraftPlanner.next(s4).id());

        CraftPlanner.State s5 = state(concat(base, Material.IRON_PICKAXE, 1,
                Material.IRON_SWORD, 1, Material.IRON_AXE, 1, Material.SHIELD, 1,
                Material.IRON_CHESTPLATE, 1));
        assertEquals("železné kalhoty", CraftPlanner.next(s5).id());
    }

    @Test
    void diamantovaGenerace() {
        CraftPlanner.State s = state(Material.OAK_PLANKS, 12, Material.STICK, 8,
                Material.COBBLESTONE, 3, Material.CRAFTING_TABLE, 1,
                Material.FURNACE, 1, Material.TORCH, 8, Material.SHIELD, 1,
                Material.IRON_PICKAXE, 1, Material.IRON_SWORD, 1, Material.IRON_AXE, 1,
                Material.STONE_SHOVEL, 1,
                Material.IRON_CHESTPLATE, 1, Material.IRON_LEGGINGS, 1,
                Material.IRON_HELMET, 1, Material.IRON_BOOTS, 1,
                Material.DIAMOND, 24);
        assertEquals("diamantový krumpáč", CraftPlanner.next(s).id());
    }

    @Test
    void lukSipyTruhlaLodka() {
        Object[] geared = {Material.OAK_PLANKS, 20, Material.STICK, 8,
                Material.COBBLESTONE, 3, Material.CRAFTING_TABLE, 1,
                Material.FURNACE, 1, Material.TORCH, 8, Material.SHIELD, 1,
                Material.DIAMOND_PICKAXE, 1, Material.DIAMOND_SWORD, 1,
                Material.DIAMOND_AXE, 1, Material.STONE_SHOVEL, 1,
                Material.DIAMOND_CHESTPLATE, 1, Material.DIAMOND_LEGGINGS, 1,
                Material.DIAMOND_HELMET, 1, Material.DIAMOND_BOOTS, 1};
        CraftPlanner.State bow = state(concat(geared, Material.STRING, 3));
        assertEquals("luk", CraftPlanner.next(bow).id());

        CraftPlanner.State arrows = state(concat(geared, Material.BOW, 1,
                Material.FLINT, 2, Material.FEATHER, 2));
        assertEquals("šípy", CraftPlanner.next(arrows).id());

        CraftPlanner.State chest = state(concat(geared, Material.BOW, 1,
                Material.ARROW, 16));
        assertEquals("truhla", CraftPlanner.next(chest).id());

        CraftPlanner.State boat = state(concat(geared, Material.BOW, 1,
                Material.ARROW, 16, Material.CHEST, 1));
        assertEquals("loďka", CraftPlanner.next(boat).id());
    }

    @Test
    void kompletniVybavaNicNepotrebuje() {
        assertNull(CraftPlanner.next(fullKit()));
    }

    @Test
    void kovadlinaACompaster() {
        Object[] geared = {Material.OAK_PLANKS, 20, Material.STICK, 8,
                Material.COBBLESTONE, 3, Material.CRAFTING_TABLE, 1,
                Material.FURNACE, 1, Material.SMOKER, 1, Material.BLAST_FURNACE, 1,
                Material.TORCH, 8, Material.SHIELD, 1,
                Material.DIAMOND_PICKAXE, 1, Material.DIAMOND_SWORD, 1,
                Material.DIAMOND_AXE, 1, Material.STONE_SHOVEL, 1,
                Material.DIAMOND_CHESTPLATE, 1, Material.DIAMOND_LEGGINGS, 1,
                Material.DIAMOND_HELMET, 1, Material.DIAMOND_BOOTS, 1,
                Material.BOW, 1, Material.ARROW, 16, Material.CHEST, 1,
                Material.OAK_BOAT, 1, Material.OAK_DOOR, 1, Material.RED_BED, 1};
        // Přebytek železa → bloky → kovadlina.
        CraftPlanner.Plan blok = CraftPlanner.next(state(concat(geared,
                Material.IRON_INGOT, 35)));
        assertEquals("blok železa", blok.id());
        CraftPlanner.Plan kovadlina = CraftPlanner.next(state(concat(geared,
                Material.IRON_BLOCK, 3, Material.IRON_INGOT, 4)));
        assertEquals("kovadlina", kovadlina.id());
        assertEquals(3, kovadlina.ingredients().get(Material.IRON_BLOCK));

        // Dostatek prken → půlky → composter.
        CraftPlanner.Plan pulky = CraftPlanner.next(state(concat(geared,
                Material.ANVIL, 1)));
        assertEquals("dřevěné půlky", pulky.id());
        CraftPlanner.Plan composter = CraftPlanner.next(state(concat(geared,
                Material.ANVIL, 1, Material.OAK_SLAB, 7)));
        assertEquals("composter", composter.id());
        assertEquals(7, composter.ingredients().get(Material.OAK_SLAB));
    }

    @Test
    void udirnaABlastovaPec() {
        Object[] geared = {Material.OAK_PLANKS, 8, Material.STICK, 8,
                Material.COBBLESTONE, 3, Material.CRAFTING_TABLE, 1,
                Material.TORCH, 8, Material.SHIELD, 1,
                Material.IRON_PICKAXE, 1, Material.IRON_SWORD, 1, Material.IRON_AXE, 1,
                Material.STONE_SHOVEL, 1};
        // Udírna: pec + 4 klády.
        CraftPlanner.Plan smoker = CraftPlanner.next(state(concat(geared,
                Material.FURNACE, 1, Material.OAK_LOG, 4, Material.IRON_INGOT, 4)));
        assertEquals("udírna", smoker.id());
        assertEquals(4, smoker.ingredients().get(Material.OAK_LOG));

        // Blastová pec: 5 železa + pec + 3 smooth stone (a udírnu už má).
        CraftPlanner.Plan blast = CraftPlanner.next(state(concat(geared,
                Material.FURNACE, 1, Material.SMOKER, 1,
                Material.IRON_INGOT, 6, Material.SMOOTH_STONE, 3)));
        assertEquals("blastová pec", blast.id());
        assertEquals(5, blast.ingredients().get(Material.IRON_INGOT));
        assertEquals(3, blast.ingredients().get(Material.SMOOTH_STONE));

        // Bez smooth stone se blastová pec neplánuje (čeká na tavicí řetěz).
        CraftPlanner.Plan waiting = CraftPlanner.next(state(concat(geared,
                Material.FURNACE, 1, Material.SMOKER, 1, Material.IRON_INGOT, 6)));
        assertTrue(waiting == null || !"blastová pec".equals(waiting.id()));
    }

    @Test
    void pochodneZUhliATycek() {
        CraftPlanner.State s = state(Material.OAK_PLANKS, 5, Material.STICK, 8,
                Material.COBBLESTONE, 3, Material.CRAFTING_TABLE, 1,
                Material.FURNACE, 1, Material.STONE_PICKAXE, 1,
                Material.STONE_SWORD, 1, Material.STONE_AXE, 1,
                Material.STONE_SHOVEL, 1, Material.COAL, 3);
        CraftPlanner.Plan torch = CraftPlanner.next(s);
        assertEquals("pochodně", torch.id());
        assertFalse(torch.needsTable());
    }

    @Test
    void vybavaDoNetheru() {
        Object[] geared = {Material.OAK_PLANKS, 4, Material.STICK, 4,
                Material.COBBLESTONE, 3, Material.CRAFTING_TABLE, 1,
                Material.FURNACE, 1, Material.TORCH, 8, Material.SHIELD, 1,
                Material.DIAMOND_PICKAXE, 1, Material.DIAMOND_SWORD, 1,
                Material.DIAMOND_AXE, 1, Material.STONE_SHOVEL, 1,
                Material.DIAMOND_CHESTPLATE, 1, Material.DIAMOND_LEGGINGS, 1,
                Material.DIAMOND_HELMET, 1, Material.DIAMOND_BOOTS, 1,
                Material.BOW, 1, Material.ARROW, 16, Material.CHEST, 1,
                Material.OAK_BOAT, 1, Material.OAK_DOOR, 1, Material.RED_BED, 1};

        // Křesadlo: železo + pazourek, 2×2 (bez ponku) – až s diamantovým krumpáčem.
        CraftPlanner.Plan flintSteel = CraftPlanner.next(state(concat(geared,
                Material.IRON_INGOT, 1, Material.FLINT, 1)));
        assertEquals("křesadlo", flintSteel.id());
        assertFalse(flintSteel.needsTable());

        // Křesadlo předbíhá šípy – jinak by opakovaná výroba šípů spolykala
        // každý pazourek a bot s lukem by křesadlo nikdy nevyrobil.
        Object[] lowArrows = geared.clone();
        for (int i = 0; i < lowArrows.length; i++) {
            if (Integer.valueOf(16).equals(lowArrows[i])) {
                lowArrows[i] = 2; // ARROW klesly pod 16 → šípy by se plánovaly
            }
        }
        CraftPlanner.Plan beforeArrows = CraftPlanner.next(state(concat(lowArrows,
                Material.IRON_INGOT, 1, Material.FLINT, 1, Material.FEATHER, 3)));
        assertEquals("křesadlo", beforeArrows.id());

        // Se železným krumpáčem se křesadlo neplánuje (obsidián by nevytěžil).
        Object[] ironTier = geared.clone();
        for (int i = 0; i < ironTier.length; i++) {
            if (ironTier[i] == Material.DIAMOND_PICKAXE) {
                ironTier[i] = Material.IRON_PICKAXE;
            }
        }
        CraftPlanner.Plan early = CraftPlanner.next(state(concat(ironTier,
                Material.IRON_INGOT, 1, Material.FLINT, 1)));
        assertTrue(early == null || !"křesadlo".equals(early.id()));

        // Zlaté boty: 4 na boty + 2 do rezervy na barter.
        CraftPlanner.Plan boots = CraftPlanner.next(state(concat(geared,
                Material.FLINT_AND_STEEL, 1, Material.GOLD_INGOT, 6)));
        assertEquals("zlaté boty", boots.id());
        CraftPlanner.Plan fewGold = CraftPlanner.next(state(concat(geared,
                Material.FLINT_AND_STEEL, 1, Material.GOLD_INGOT, 5)));
        assertTrue(fewGold == null || !"zlaté boty".equals(fewGold.id()),
                "5 ingotů nestačí (rezerva na barter)");
    }

    @Test
    void netheritovyRetez() {
        Object[] geared = {Material.OAK_PLANKS, 4, Material.STICK, 4,
                Material.COBBLESTONE, 3, Material.CRAFTING_TABLE, 1,
                Material.FURNACE, 1, Material.TORCH, 8, Material.SHIELD, 1,
                Material.DIAMOND_PICKAXE, 1, Material.DIAMOND_SWORD, 1,
                Material.DIAMOND_AXE, 1, Material.STONE_SHOVEL, 1,
                Material.DIAMOND_CHESTPLATE, 1, Material.DIAMOND_LEGGINGS, 1,
                Material.DIAMOND_HELMET, 1, Material.DIAMOND_BOOTS, 1,
                Material.BOW, 1, Material.ARROW, 16, Material.CHEST, 1,
                Material.OAK_BOAT, 1, Material.OAK_DOOR, 1, Material.RED_BED, 1,
                Material.FLINT_AND_STEEL, 1, Material.GOLDEN_BOOTS, 1};

        // 4 úlomky + 4 zlato → ingot (na ponku).
        CraftPlanner.Plan ingot = CraftPlanner.next(state(concat(geared,
                Material.NETHERITE_SCRAP, 4, Material.GOLD_INGOT, 4)));
        assertEquals("netheritový ingot", ingot.id());
        assertTrue(ingot.needsTable());
        assertEquals(4, ingot.ingredients().get(Material.NETHERITE_SCRAP));
        assertEquals(4, ingot.ingredients().get(Material.GOLD_INGOT));

        // 3 úlomky nestačí.
        CraftPlanner.Plan few = CraftPlanner.next(state(concat(geared,
                Material.NETHERITE_SCRAP, 3, Material.GOLD_INGOT, 4)));
        assertTrue(few == null || !"netheritový ingot".equals(few.id()));

        // Kovářský stůl se plánuje, jakmile je doma netherová kořist.
        CraftPlanner.Plan table = CraftPlanner.next(state(concat(geared,
                Material.NETHERITE_INGOT, 1, Material.IRON_INGOT, 2)));
        assertEquals("kovářský stůl", table.id());
        assertEquals(2, table.ingredients().get(Material.IRON_INGOT));

        // Bez netherové kořisti kovářský stůl nedává smysl.
        CraftPlanner.Plan noLoot = CraftPlanner.next(state(concat(geared,
                Material.IRON_INGOT, 2)));
        assertTrue(noLoot == null || !"kovářský stůl".equals(noLoot.id()));
    }

    @Test
    void kopieKovarskeSablony() {
        Object[] geared = {Material.OAK_PLANKS, 4, Material.STICK, 4,
                Material.COBBLESTONE, 3, Material.CRAFTING_TABLE, 1,
                Material.FURNACE, 1, Material.TORCH, 8, Material.SHIELD, 1,
                Material.DIAMOND_PICKAXE, 1, Material.DIAMOND_SWORD, 1,
                Material.DIAMOND_AXE, 1, Material.STONE_SHOVEL, 1,
                Material.DIAMOND_CHESTPLATE, 1, Material.DIAMOND_LEGGINGS, 1,
                Material.DIAMOND_HELMET, 1, Material.DIAMOND_BOOTS, 1,
                Material.BOW, 1, Material.ARROW, 16, Material.CHEST, 1,
                Material.OAK_BOAT, 1, Material.OAK_DOOR, 1, Material.RED_BED, 1,
                Material.FLINT_AND_STEEL, 1, Material.GOLDEN_BOOTS, 1,
                Material.SMITHING_TABLE, 1, Material.NETHERITE_INGOT, 1};

        // Jedna šablona + spousta diamantové výbavy → duplikace se vyplatí.
        CraftPlanner.Plan copy = CraftPlanner.next(state(concat(geared,
                Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 1,
                Material.DIAMOND, 7, Material.NETHERRACK, 4)));
        assertEquals("kopie kovářské šablony", copy.id());
        assertEquals(7, copy.ingredients().get(Material.DIAMOND));
        assertEquals(1, copy.ingredients().get(Material.NETHERRACK));

        // Dvě šablony nebo málo diamantů → nekopíruje se.
        CraftPlanner.Plan two = CraftPlanner.next(state(concat(geared,
                Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 2,
                Material.DIAMOND, 7, Material.NETHERRACK, 4)));
        assertTrue(two == null || !"kopie kovářské šablony".equals(two.id()));
        CraftPlanner.Plan poor = CraftPlanner.next(state(concat(geared,
                Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE, 1,
                Material.DIAMOND, 6, Material.NETHERRACK, 4)));
        assertTrue(poor == null || !"kopie kovářské šablony".equals(poor.id()));
    }

    @Test
    void retezOciEnderu() {
        // Perla + rod bez prachu: nejdřív se mele prach (1 rod = 2 prachy).
        CraftPlanner.Plan powder = CraftPlanner.next(plus(fullKit(),
                Material.ENDER_PEARL, 2, Material.BLAZE_ROD, 2));
        assertEquals("blaze prach", powder.id());
        assertEquals(1, powder.ingredients().get(Material.BLAZE_ROD));

        // S prachem po ruce se skládá oko (perla + prach, 2×2 bez ponku).
        CraftPlanner.Plan eye = CraftPlanner.next(plus(fullKit(),
                Material.ENDER_PEARL, 2, Material.BLAZE_POWDER, 2));
        assertEquals("oko Enderu", eye.id());
        assertFalse(eye.needsTable());
        assertEquals(1, eye.ingredients().get(Material.ENDER_PEARL));
        assertEquals(1, eye.ingredients().get(Material.BLAZE_POWDER));

        // Bez perly se rody nemelou – zůstávají vcelku (budoucí vaření).
        assertNull(CraftPlanner.next(plus(fullKit(), Material.BLAZE_ROD, 3)));
        // Bez prachu a rodů se z perel nic nesloží.
        assertNull(CraftPlanner.next(plus(fullKit(), Material.ENDER_PEARL, 5)));

        // Strop 12 očí: rám portálu má 12 slotů, víc jich bot nepotřebuje.
        assertNull(CraftPlanner.next(plus(fullKit(), Material.ENDER_EYE, 12,
                Material.ENDER_PEARL, 3, Material.BLAZE_POWDER, 3)));
        CraftPlanner.Plan last = CraftPlanner.next(plus(fullKit(),
                Material.ENDER_EYE, 11, Material.ENDER_PEARL, 1,
                Material.BLAZE_POWDER, 1));
        assertEquals("oko Enderu", last.id());
    }

    @Test
    void striderskyRetezHoubaNaPrutu() {
        // Sedlo + houba bez prutu: nejdřív rybářský prut (string jinak
        // patří luku – ten už fullKit má).
        CraftPlanner.Plan rod = CraftPlanner.next(plus(fullKit(),
                Material.SADDLE, 1, Material.WARPED_FUNGUS, 1, Material.STRING, 2));
        assertEquals("rybářský prut", rod.id());
        // S prutem po ruce: houba na prutu (2×2, bez ponku).
        CraftPlanner.Plan steering = CraftPlanner.next(plus(fullKit(),
                Material.SADDLE, 1, Material.WARPED_FUNGUS, 1,
                Material.FISHING_ROD, 1));
        assertEquals("houba na prutu", steering.id());
        assertFalse(steering.needsTable());
        // Bez sedla nemá řízení stridera smysl.
        CraftPlanner.Plan noSaddle = CraftPlanner.next(plus(fullKit(),
                Material.WARPED_FUNGUS, 1, Material.FISHING_ROD, 1));
        assertTrue(noSaddle == null || !"houba na prutu".equals(noSaddle.id()));
    }

    @Test
    void vareniAKotvaRespawnu() {
        // Stojan: bradavice (je co vařit) + blaze rod + kámen.
        CraftPlanner.Plan stand = CraftPlanner.next(plus(fullKit(),
                Material.NETHER_WART, 3, Material.BLAZE_ROD, 1));
        assertEquals("varný stojan", stand.id());
        // Bez bradavice se stojan nestaví (rod zůstává vcelku – oči Enderu).
        CraftPlanner.Plan noWart = CraftPlanner.next(plus(fullKit(),
                Material.BLAZE_ROD, 1));
        assertTrue(noWart == null || !"varný stojan".equals(noWart.id()));
        // Lahve ze skla, jakmile stojí stojan.
        CraftPlanner.Plan bottles = CraftPlanner.next(plus(fullKit(),
                Material.BREWING_STAND, 1, Material.GLASS, 3));
        assertEquals("skleněné lahve", bottles.id());
        // Palivo: stojan + bradavice bez prachu → mele se jeden rod.
        CraftPlanner.Plan fuel = CraftPlanner.next(plus(fullKit(),
                Material.BREWING_STAND, 1, Material.NETHER_WART, 2,
                Material.BLAZE_ROD, 1));
        assertEquals("blaze prach (palivo)", fuel.id());

        // Kotva respawnu: nejdřív glowstone z prachu, pak kotva samotná.
        CraftPlanner.Plan glow = CraftPlanner.next(plus(fullKit(),
                Material.CRYING_OBSIDIAN, 6, Material.GLOWSTONE_DUST, 8));
        assertEquals("glowstone", glow.id());
        CraftPlanner.Plan anchor = CraftPlanner.next(plus(fullKit(),
                Material.CRYING_OBSIDIAN, 6, Material.GLOWSTONE, 4));
        assertEquals("kotva respawnu", anchor.id());
        assertEquals(6, anchor.ingredients().get(Material.CRYING_OBSIDIAN));
        assertEquals(3, anchor.ingredients().get(Material.GLOWSTONE));
    }

    @Test
    void raketyAShulkerBox() {
        // Rakety jen s elytrami (papír + střelný prach, 2×2).
        CraftPlanner.Plan rockets = CraftPlanner.next(plus(fullKit(),
                Material.ELYTRA, 1, Material.PAPER, 2, Material.GUNPOWDER, 3));
        assertEquals("rakety", rockets.id());
        assertFalse(rockets.needsTable());
        // Bez elytr rakety nemají co pohánět.
        CraftPlanner.Plan noWings = CraftPlanner.next(plus(fullKit(),
                Material.PAPER, 2, Material.GUNPOWDER, 3));
        assertTrue(noWings == null || !"rakety".equals(noWings.id()));
        // Papír z třtiny, když chybí (rakety čekají na prach i papír).
        CraftPlanner.Plan paper = CraftPlanner.next(plus(fullKit(),
                Material.ELYTRA, 1, Material.SUGAR_CANE, 6, Material.GUNPOWDER, 3));
        assertEquals("papír", paper.id());

        // Shulker box: 2 ulity + truhla (tu fullKit má).
        CraftPlanner.Plan box = CraftPlanner.next(plus(fullKit(),
                Material.SHULKER_SHELL, 2));
        assertEquals("shulker box", box.id());
        assertEquals(2, box.ingredients().get(Material.SHULKER_SHELL));
        // Jedna ulita nestačí.
        CraftPlanner.Plan oneShell = CraftPlanner.next(plus(fullKit(),
                Material.SHULKER_SHELL, 1));
        assertTrue(oneShell == null || !"shulker box".equals(oneShell.id()));
    }

    @Test
    void vsechnyPlanyMajiValidniMatice() {
        // Sanity: každý plán z progrese má neprázdnou matici a ingredience.
        CraftPlanner.Plan plan = CraftPlanner.next(state(Material.OAK_LOG, 3));
        assertTrue(plan.ingredients().values().stream().allMatch(v -> v > 0));
        assertEquals(9, plan.matrix().length);
    }

    private static Object[] concat(Object[] base, Object... extra) {
        Object[] out = new Object[base.length + extra.length];
        System.arraycopy(base, 0, out, 0, base.length);
        System.arraycopy(extra, 0, out, base.length, extra.length);
        return out;
    }

    /** Stav rozšířený o další itemy (nad základem, typicky plnou výbavou). */
    private static CraftPlanner.State plus(CraftPlanner.State base, Object... pairs) {
        Map<Material, Integer> items = new HashMap<>(base.items());
        for (int i = 0; i < pairs.length; i += 2) {
            items.merge((Material) pairs[i], (Integer) pairs[i + 1], Integer::sum);
        }
        return new CraftPlanner.State(items, base.logType(), base.plankType(),
                base.stoneType(), base.woolType(), base.masonry());
    }
}
