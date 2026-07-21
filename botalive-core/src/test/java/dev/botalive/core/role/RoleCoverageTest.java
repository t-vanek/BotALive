package dev.botalive.core.role;

import dev.botalive.api.role.BotRole;
import dev.botalive.core.personality.PersonalityGenerator;
import dev.botalive.core.util.BotRandom;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.util.EnumSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Každá profese musí být „živá“ – mít profil utility i startovní výbavu.
 *
 * <p>Vzniklo z konkrétní chyby: role RYBÁŘ existovala, ale {@code FishGoal} se
 * nikdy nespustil (prut nebylo kde vzít), takže profese byla jen popisek.
 * Tyhle testy hlídají, aby se to u nově přidaných rolí neopakovalo.</p>
 */
class RoleCoverageTest {

    /** Cíle registrované v mozku – proti nim se ověřuje, že profil neukazuje do prázdna. */
    private static final Set<String> REGISTERED_GOALS = Set.of(
            "home", "stash", "granary", "sleep", "mine", "craft", "smelt", "farm", "explore",
            "wander", "idle", "smith", "compost", "maintain", "buy", "dragon-fight",
            "communal-build", "hunt", "nether", "pvp", "brew", "drink", "camp",
            "reconcile", "minecart", "collect", "deliver-work", "repair", "shelter",
            "rob", "end-outer", "steal", "guard", "combat", "house", "end-harvest",
            "socialize", "eat", "share", "escape", "wither-fight", "survive",
            "bodyguard", "recover", "sell", "follow", "boat", "war-raid",
            "creeper-dodge", "trade", "tame", "breed", "shear", "fish", "enchant",
            "end-travel", "end-return");

    @ParameterizedTest
    @EnumSource(value = BotRole.class, names = "NONE", mode = EnumSource.Mode.EXCLUDE)
    void kazdaRoleMaAlesponJedenZesilenyCil(BotRole role) {
        boolean hasBoost = REGISTERED_GOALS.stream()
                .anyMatch(goal -> RoleProfiles.weight(role, goal) > 1.0);
        assertTrue(hasBoost, "role " + role + " nemá v profilu žádný cíl – byla by dekorace");
    }

    @ParameterizedTest
    @EnumSource(value = BotRole.class, names = "NONE", mode = EnumSource.Mode.EXCLUDE)
    void profilRoleNeukazujeNaNeexistujiciCil(BotRole role) {
        for (String goal : RoleProfiles.profileGoals(role)) {
            assertTrue(REGISTERED_GOALS.contains(goal),
                    "role " + role + " zesiluje neznámý cíl '" + goal + "'");
        }
    }

    @ParameterizedTest
    @EnumSource(BotRole.class)
    void zadnaRoleNetlumiZakladniPotreby(BotRole role) {
        // Role vychyluje, nikdy nevypíná – jinak by bot mohl přestat jíst.
        for (String goal : REGISTERED_GOALS) {
            assertTrue(RoleProfiles.weight(role, goal) >= 1.0,
                    "role " + role + " tlumí cíl '" + goal + "'");
        }
    }

    @Test
    void vyberRoliPokryjeVsechnyProfeseNapricPovahami() {
        // Profese, kterou nikdo nikdy nedostane, je mrtvý kód.
        Set<BotRole> seen = EnumSet.noneOf(BotRole.class);
        for (long seed = 0; seed < 4000; seed++) {
            seen.add(RolePicker.pick(PersonalityGenerator.generate(seed), new BotRandom(seed)));
        }
        for (BotRole role : BotRole.values()) {
            assertTrue(seen.contains(role), "roli " + role + " nikdy nikdo nedostane");
        }
    }

    @Test
    void pateroOsadyJeCastejsiNezExotika() {
        // Bez stavitelů a kopáčů vesnice nevznikne, takže musí být běžnější
        // než dobrodruh nebo zloděj.
        int core = 0;
        int rare = 0;
        for (long seed = 0; seed < 3000; seed++) {
            BotRole role = RolePicker.pick(PersonalityGenerator.generate(seed),
                    new BotRandom(seed));
            if (role == BotRole.BUILDER || role == BotRole.MINER) {
                core++;
            } else if (role == BotRole.THIEF || role == BotRole.ADVENTURER) {
                rare++;
            }
        }
        assertTrue(core > rare,
                "páteř osady (" + core + ") musí být častější než exotika (" + rare + ")");
        assertFalse(rare == 0, "exotické role se musí objevovat aspoň občas");
    }
}
