package dev.botalive.core.inventory;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Znalostní vrstva {@link MaterialGuide} – afordance materiálu (účel, co
 * smí/nesmí, tvorba, vylepšení, oprava) odvozené pravidly z katalogů.
 */
class MaterialGuideTest {

    private static void assertAny(List<String> list, String needle, String ctx) {
        assertTrue(list.stream().anyMatch(s -> s.contains(needle)), ctx + " → " + list);
    }

    @Test
    void krumpac() {
        MaterialGuide.Guidance g = MaterialGuide.of(Material.DIAMOND_PICKAXE);
        assertTrue(g.purpose().contains("těžba"), g.purpose());
        assertTrue(g.upgrade().contains("netherit"), g.upgrade());
        assertTrue(g.repair().contains("kovadlina"), g.repair());
        assertAny(g.can(), "používat", "can");
    }

    @Test
    void rudaMaTierGating() {
        MaterialGuide.Guidance g = MaterialGuide.of(Material.IRON_ORE);
        assertTrue(g.purpose().contains("vytěžit"), g.purpose());
        assertAny(g.cannot(), "tier 3", "cannot");
    }

    @Test
    void cennostSeBankuje() {
        MaterialGuide.Guidance g = MaterialGuide.of(Material.DIAMOND);
        assertTrue(g.purpose().contains("diamant"), g.purpose());
        assertAny(g.can(), "truhly", "bankable");
    }

    @Test
    void netheroveDrevoNehoriAneniPalivo() {
        assertFalse(Items.isFuel(Material.CRIMSON_PLANKS), "netherové dřevo nehoří");
        MaterialGuide.Guidance g = MaterialGuide.of(Material.CRIMSON_PLANKS);
        assertAny(g.cannot(), "NEHOŘÍ", "cannot");
        assertFalse(g.can().stream().anyMatch(s -> s.contains("palivo")),
                "netherové dřevo se nenabízí jako palivo: " + g.can());
    }

    @Test
    void hnileMasoJenVNouzi() {
        assertAny(MaterialGuide.of(Material.ROTTEN_FLESH).cannot(), "hlad", "rotten");
    }

    @Test
    void gravitacniBlokPada() {
        assertAny(MaterialGuide.of(Material.SAND).cannot(), "padá", "sand");
    }

    @Test
    void prknaSeTvoriZKlady() {
        MaterialGuide.Guidance g = MaterialGuide.of(Material.OAK_PLANKS);
        assertTrue(g.craft().contains("kláda"), g.craft());
        assertAny(g.can(), "palivo", "planks");
    }

    @Test
    void postelKeSpanku() {
        assertTrue(MaterialGuide.of(Material.RED_BED).purpose().contains("spánek"));
    }

    @Test
    void prazdnyProNull() {
        MaterialGuide.Guidance g = MaterialGuide.of(null);
        assertEquals("nic", g.purpose());
        assertNull(g.craft());
        assertNull(g.repair());
    }

    @Test
    void radkyMajiUcel() {
        assertFalse(MaterialGuide.lines(Material.DIAMOND_PICKAXE).isEmpty());
        assertTrue(MaterialGuide.lines(Material.IRON_ORE).stream()
                .anyMatch(l -> l.startsWith("k čemu:")));
    }
}
