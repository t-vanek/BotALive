package dev.botalive.core.nether;

import dev.botalive.core.bot.ServerSideView;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy připravenosti na výpravu do Netheru (gear gating).
 */
class NetherReadinessTest {

    /** Snapshot z dvojic (materiál, počet) v hotbaru + nasazená zbroj. */
    private static ServerSideView.Snapshot snapshot(Material[] armor, Object... pairs) {
        Material[] bar = new Material[9];
        int[] counts = new int[9];
        List<Material> main = new ArrayList<>();
        int slot = 0;
        for (int i = 0; i < pairs.length; i += 2) {
            Material material = (Material) pairs[i];
            int count = (Integer) pairs[i + 1];
            if (slot < 9) {
                bar[slot] = material;
                counts[slot] = count;
                slot++;
            } else {
                main.add(material);
            }
        }
        Material[] mainArr = new Material[27];
        for (int i = 0; i < main.size() && i < 27; i++) {
            mainArr[i] = main.get(i);
        }
        return new ServerSideView.Snapshot(null, bar, counts, mainArr, null, null,
                armor, null, 0, 20, 20, 0, false, false, false, 1000, 0);
    }

    private static Material[] ironArmor() {
        return new Material[]{Material.IRON_BOOTS, Material.IRON_LEGGINGS,
                Material.IRON_CHESTPLATE, Material.IRON_HELMET};
    }

    @Test
    void plnaVybavaJePripravena() {
        var readiness = NetherReadiness.assess(snapshot(ironArmor(),
                Material.IRON_PICKAXE, 1, Material.IRON_SWORD, 1, Material.BREAD, 8), 4);
        assertTrue(readiness.gearReady(4));
    }

    @Test
    void bezZbrojeSeDoPeklaNechodi() {
        var readiness = NetherReadiness.assess(snapshot(new Material[4],
                Material.IRON_PICKAXE, 1, Material.IRON_SWORD, 1, Material.BREAD, 8), 4);
        assertFalse(readiness.gearReady(4));
    }

    @Test
    void bezJidlaSeDoPeklaNechodi() {
        var readiness = NetherReadiness.assess(snapshot(ironArmor(),
                Material.IRON_PICKAXE, 1, Material.IRON_SWORD, 1, Material.BREAD, 2), 4);
        assertFalse(readiness.gearReady(4), "2 chleby na výpravu nestačí");
    }

    @Test
    void lukSeSipyNahradiMec() {
        var readiness = NetherReadiness.assess(snapshot(ironArmor(),
                Material.IRON_PICKAXE, 1, Material.BOW, 1, Material.ARROW, 16,
                Material.BREAD, 8), 4);
        assertTrue(readiness.gearReady(4), "luk + šípy = dálková zbraň stačí");
    }

    @Test
    void kamennyKrumpacNestaci() {
        var readiness = NetherReadiness.assess(snapshot(ironArmor(),
                Material.STONE_PICKAXE, 1, Material.IRON_SWORD, 1, Material.BREAD, 8), 4);
        assertFalse(readiness.gearReady(4), "na quartz je potřeba aspoň železný krumpáč");
    }

    @Test
    void zlateBotySePocitajiDoZbroje() {
        Material[] armor = {Material.GOLDEN_BOOTS, Material.IRON_LEGGINGS,
                Material.IRON_CHESTPLATE, Material.IRON_HELMET};
        var readiness = NetherReadiness.assess(snapshot(armor,
                Material.IRON_PICKAXE, 1, Material.IRON_SWORD, 1, Material.BREAD, 8), 4);
        assertTrue(readiness.gearReady(4), "zlaté boty jsou nasazené schválně (piglini)");
        assertTrue(readiness.hasGoldenBoots());
    }

    @Test
    void stavbaPortaluChceKresadloAObsidian() {
        var ready = NetherReadiness.assess(snapshot(ironArmor(),
                Material.FLINT_AND_STEEL, 1, Material.OBSIDIAN, 14), 4);
        assertTrue(ready.canBuildPortal());
        var noFlint = NetherReadiness.assess(snapshot(ironArmor(),
                Material.OBSIDIAN, 14), 4);
        assertFalse(noFlint.canBuildPortal());
        var fewObsidian = NetherReadiness.assess(snapshot(ironArmor(),
                Material.FLINT_AND_STEEL, 1, Material.OBSIDIAN, 10), 4);
        assertFalse(fewObsidian.canBuildPortal(), "plný rám chce 14 obsidiánu");
    }
}
