package dev.botalive.core.ai;

import dev.botalive.core.bot.ServerSideView;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy připravenosti na výpravu do Endu.
 */
class EndReadinessTest {

    private static ServerSideView.Snapshot snapshot(Material[] armor, Material[] main,
                                                    Material... hotbar) {
        Material[] bar = new Material[9];
        int[] counts = new int[9];
        for (int i = 0; i < hotbar.length && i < 9; i++) {
            bar[i] = hotbar[i];
            counts[i] = hotbar[i] == null ? 0 : 1;
        }
        Material[] inventory = new Material[27];
        for (int i = 0; i < main.length && i < 27; i++) {
            inventory[i] = main[i];
        }
        return new ServerSideView.Snapshot(null, bar, counts, inventory, null, null, null,
                armor, null, 0, 20, 20, 0, false, false, false, 1000, 0);
    }

    private static Material[] ironArmor(int pieces) {
        Material[] armor = new Material[4];
        Material[] set = {Material.IRON_HELMET, Material.IRON_CHESTPLATE,
                Material.IRON_LEGGINGS, Material.IRON_BOOTS};
        for (int i = 0; i < pieces && i < 4; i++) {
            armor[i] = set[i];
        }
        return armor;
    }

    @Test
    void bezVybavySeNevyrazi() {
        EndReadiness readiness = EndReadiness.assess(
                snapshot(new Material[4], new Material[0]));
        assertFalse(readiness.expeditionReady());
        assertFalse(readiness.wellArmed());
    }

    @Test
    void nullSnapshotJeNepripraven() {
        assertFalse(EndReadiness.assess(null).expeditionReady());
    }

    @Test
    void zeleznaVybavaJidloABlokyStaci() {
        Material[] main = {Material.COBBLESTONE, Material.COBBLESTONE, Material.COBBLESTONE,
                Material.COBBLESTONE, Material.COBBLESTONE, Material.COBBLESTONE,
                Material.COBBLESTONE, Material.COBBLESTONE, Material.BREAD};
        EndReadiness readiness = EndReadiness.assess(
                snapshot(ironArmor(3), main, Material.IRON_SWORD, Material.BREAD));
        assertTrue(readiness.expeditionReady(),
                "železný meč + 3 kusy brnění + jídlo + bloky = připraven");
        assertFalse(readiness.wellArmed(), "bez luku není dobře vyzbrojen");
    }

    @Test
    void kamennyMecNestaci() {
        Material[] main = {Material.COBBLESTONE, Material.COBBLESTONE, Material.COBBLESTONE,
                Material.COBBLESTONE, Material.COBBLESTONE, Material.COBBLESTONE,
                Material.COBBLESTONE, Material.COBBLESTONE, Material.BREAD};
        EndReadiness readiness = EndReadiness.assess(
                snapshot(ironArmor(4), main, Material.STONE_SWORD, Material.BREAD));
        assertFalse(readiness.expeditionReady(), "s kamenným mečem se na draka nechodí");
    }

    @Test
    void lukASipyDelajiDobreVyzbrojeneho() {
        Material[] main = {Material.ARROW, Material.ARROW, Material.ARROW, Material.ARROW};
        EndReadiness readiness = EndReadiness.assess(
                snapshot(new Material[4], main, Material.BOW));
        assertTrue(readiness.hasBow());
        assertTrue(readiness.wellArmed(), "luk + ~16 šípů = krystaly jdou sestřelit");
    }
}
