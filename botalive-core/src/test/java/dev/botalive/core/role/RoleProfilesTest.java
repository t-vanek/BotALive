package dev.botalive.core.role;

import dev.botalive.api.role.BotRole;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy profilů rolí.
 */
class RoleProfilesTest {

    @Test
    void noneMaVseNeutralni() {
        for (String goal : new String[]{"mine", "farm", "hunt", "fish", "smelt", "enchant"}) {
            assertEquals(1.0, RoleProfiles.weight(BotRole.NONE, goal));
        }
        assertEquals(1.0, RoleProfiles.weight(null, "mine"));
    }

    @Test
    void roleZvysujeSvouHlavniCinnost() {
        assertTrue(RoleProfiles.weight(BotRole.MINER, "mine") >= 2.0);
        assertTrue(RoleProfiles.weight(BotRole.HUNTER, "hunt") >= 2.0);
        assertTrue(RoleProfiles.weight(BotRole.FISHERMAN, "fish") >= 2.0);
        assertTrue(RoleProfiles.weight(BotRole.BLACKSMITH, "smelt") >= 2.0);
        assertTrue(RoleProfiles.weight(BotRole.ENCHANTER, "enchant") >= 2.0);
        assertTrue(RoleProfiles.weight(BotRole.TRADER, "trade") >= 2.0);
        assertTrue(RoleProfiles.weight(BotRole.FARMER, "farm") >= 2.0);
        assertTrue(RoleProfiles.weight(BotRole.BUILDER, "shelter") >= 2.0);
        assertTrue(RoleProfiles.weight(BotRole.LUMBERJACK, "mine") >= 2.0);
    }

    @Test
    void roleNevypinaOstatniCinnosti() {
        // Role je zaměření, ne klec – cíle mimo profil zůstávají na 1.0.
        for (BotRole role : BotRole.values()) {
            assertEquals(1.0, RoleProfiles.weight(role, "eat"), "role " + role);
            assertEquals(1.0, RoleProfiles.weight(role, "survive"), "role " + role);
            assertEquals(1.0, RoleProfiles.weight(role, "socialize"), "role " + role);
        }
    }

    @Test
    void drevorubecPreferujeDrevo() {
        assertTrue(RoleProfiles.prefersLogs(BotRole.LUMBERJACK));
        for (BotRole role : BotRole.values()) {
            if (role != BotRole.LUMBERJACK) {
                assertTrue(!RoleProfiles.prefersLogs(role), "role " + role);
            }
        }
    }
}
