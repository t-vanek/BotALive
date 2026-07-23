package dev.botalive.core.inventory;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Znalostní vrstva {@link StationGuide} – karta pracovní stanice (účel,
 * vstupy, výstupy, který bot ji obsluhuje). Registry-free, bez serveru.
 */
class StationGuideTest {

    private static void assertAny(List<String> list, String needle, String ctx) {
        assertTrue(list.stream().anyMatch(s -> s.contains(needle)), ctx + " → " + list);
    }

    @Test
    void pracovniBlokyJsouStanice() {
        assertTrue(StationGuide.isStation(Material.FURNACE));
        assertTrue(StationGuide.isStation(Material.CRAFTING_TABLE));
        assertTrue(StationGuide.isStation(Material.SMITHING_TABLE));
        assertTrue(StationGuide.isStation(Material.ANVIL));
        assertTrue(StationGuide.isStation(Material.COMPOSTER));
        assertTrue(StationGuide.isStation(Material.CHEST));
    }

    @Test
    void obycejneBlokyStaniceNejsou() {
        assertFalse(StationGuide.isStation(Material.STONE));
        assertFalse(StationGuide.isStation(Material.DIAMOND));
        assertFalse(StationGuide.isStation(Material.OAK_LOG));
        assertFalse(StationGuide.isStation(null));
        assertNull(StationGuide.of(Material.STONE));
        assertNull(StationGuide.of(null));
        assertTrue(StationGuide.lines(Material.STONE).isEmpty(), "nestanice nemá řádky");
    }

    @Test
    void pecTaviAPeceSPalivem() {
        StationGuide.Guidance g = StationGuide.of(Material.FURNACE);
        assertTrue(g.purpose().contains("tavení"), g.purpose());
        assertAny(g.needs(), "palivo", "needs");
        assertAny(g.produces(), "ingoty", "produces");
        assertEquals("SmeltGoal", g.usedBy());
    }

    @Test
    void kovarskyStulPovysujeNaNetherit() {
        StationGuide.Guidance g = StationGuide.of(Material.SMITHING_TABLE);
        assertTrue(g.purpose().contains("netherit"), g.purpose());
        assertAny(g.needs(), "netheritový ingot", "needs");
        assertEquals("SmithGoal", g.usedBy());
    }

    @Test
    void kompsterVyrabiHnojivo() {
        StationGuide.Guidance g = StationGuide.of(Material.COMPOSTER);
        assertAny(g.produces(), "kostní moučka", "produces");
        assertEquals("CompostGoal", g.usedBy());
    }

    @Test
    void poskozeneKovadlinyMajiStejnouKartuJakoKovadlina() {
        // Opotřebené varianty (CHIPPED/DAMAGED) jsou pořád kovadlina.
        for (Material anvil : List.of(Material.ANVIL, Material.CHIPPED_ANVIL,
                Material.DAMAGED_ANVIL)) {
            StationGuide.Guidance g = StationGuide.of(anvil);
            assertTrue(g.purpose().contains("oprava"), anvil + " → " + g.purpose());
            assertEquals("RepairGoal (sama je gravitační – opotřebením padá)", g.usedBy(),
                    anvil.name());
        }
    }

    @Test
    void uloziteNevyrabi() {
        // Truhla/sud jen skladují – žádný „vyrobí" řádek.
        for (Material store : List.of(Material.CHEST, Material.TRAPPED_CHEST, Material.BARREL)) {
            StationGuide.Guidance g = StationGuide.of(store);
            assertTrue(g.produces().isEmpty(), store + " nevyrábí");
            assertFalse(StationGuide.lines(store).stream().anyMatch(s -> s.startsWith("vyrobí:")),
                    store + " nemá řádek vyrobí");
            assertAny(StationGuide.lines(store), "StashGoal", store.name());
        }
    }

    @Test
    void radkyKartyMajiStitky() {
        List<String> lines = StationGuide.lines(Material.ENCHANTING_TABLE);
        assertAny(lines, "stanice:", "purpose label");
        assertAny(lines, "potřebuje:", "needs label");
        assertAny(lines, "vyrobí:", "produces label");
        assertAny(lines, "bot:", "usedBy label");
    }
}
