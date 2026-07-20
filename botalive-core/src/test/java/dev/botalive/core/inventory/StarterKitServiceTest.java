package dev.botalive.core.inventory;

import dev.botalive.api.role.BotRole;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Obsah startovních kitů podle profese. */
class StarterKitServiceTest {

    @ParameterizedTest
    @EnumSource(BotRole.class)
    void kazdaRoleDostaneZakladNaPrezitiPrvniNoci(BotRole role) {
        Map<Material, Integer> kit = StarterKitService.contents(role);
        assertTrue(kit.containsKey(Material.STONE_SWORD), "zbraň proti mobům: " + role);
        assertTrue(kit.containsKey(Material.BREAD), "jídlo: " + role);
        assertTrue(kit.containsKey(Material.COBBLESTONE), "materiál na úkryt: " + role);
        assertTrue(kit.containsKey(Material.STONE_PICKAXE), "krumpáč: " + role);
    }

    @ParameterizedTest
    @EnumSource(BotRole.class)
    void pocty_jsou_kladne_a_vejdou_se_do_inventare(BotRole role) {
        Map<Material, Integer> kit = StarterKitService.contents(role);
        kit.forEach((material, count) -> {
            assertTrue(count > 0, "nekladný počet u " + material + " (" + role + ")");
            // 36 slotů inventáře; kit nesmí přetéct ani při stackování po 64
            assertTrue(count <= 128, "přemrštěný počet u " + material + " (" + role + ")");
        });
        assertTrue(kit.size() <= 12, "kit má příliš mnoho druhů: " + role);
    }

    @Test
    void rybarDostanePrut() {
        // Bez prutu FishGoal nikdy neprojde vstupní branou a role je dekorace.
        assertTrue(StarterKitService.contents(BotRole.FISHERMAN)
                .containsKey(Material.FISHING_ROD));
    }

    @Test
    void farmarDostaneOsivoAMotyku() {
        Map<Material, Integer> kit = StarterKitService.contents(BotRole.FARMER);
        assertTrue(kit.containsKey(Material.WHEAT_SEEDS));
        assertTrue(kit.containsKey(Material.STONE_HOE));
    }

    @Test
    void obchodnikDostaneSmaragdy() {
        assertTrue(StarterKitService.contents(BotRole.TRADER).containsKey(Material.EMERALD));
    }

    @Test
    void lovecDostaneLukISipy() {
        Map<Material, Integer> kit = StarterKitService.contents(BotRole.HUNTER);
        assertTrue(kit.containsKey(Material.BOW));
        assertTrue(kit.containsKey(Material.ARROW));
    }

    @Test
    void stavitelMaViceBlokuNezUniverzal() {
        int builder = StarterKitService.contents(BotRole.BUILDER).get(Material.COBBLESTONE);
        int none = StarterKitService.contents(BotRole.NONE).get(Material.COBBLESTONE);
        assertTrue(builder > none, "stavitel má začít stavět hned, ne až po těžbě");
        assertEquals(128, builder);
    }

    @Test
    void kopacDostaneVicPochodniNezZaklad() {
        int miner = StarterKitService.contents(BotRole.MINER).get(Material.TORCH);
        int none = StarterKitService.contents(BotRole.NONE).get(Material.TORCH);
        assertTrue(miner > none, "kopáč jde do tmy");
    }

    @Test
    void univerzalNemaProfesniPridavek() {
        assertEquals(7, StarterKitService.contents(BotRole.NONE).size());
    }

    @Test
    void kitNeobsahujeVybavuPreskakujiciRanouHru() {
        // Kit má první noc přežít, ne přeskočit progresi.
        for (BotRole role : BotRole.values()) {
            Map<Material, Integer> kit = StarterKitService.contents(role);
            assertFalse(kit.containsKey(Material.DIAMOND_PICKAXE), "diamant v kitu: " + role);
            assertFalse(kit.containsKey(Material.DIAMOND_SWORD), "diamant v kitu: " + role);
            assertFalse(kit.containsKey(Material.NETHERITE_INGOT), "netherit v kitu: " + role);
            assertFalse(kit.containsKey(Material.ENCHANTED_GOLDEN_APPLE), "notch apple: " + role);
            assertFalse(kit.containsKey(Material.ELYTRA), "elytra v kitu: " + role);
        }
    }
}
