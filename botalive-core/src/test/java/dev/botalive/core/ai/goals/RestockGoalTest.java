package dev.botalive.core.ai.goals;

import dev.botalive.core.ai.BotNeeds;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Rozhodovací jádro zpětného odběru ze skladu ({@link RestockGoal#restockPlan}
 * a {@link RestockGoal#utilityFor}). Tick smyčka (chůze, otevírání truhly,
 * přesun) běží jen naživo; co a kolik brát je čistá funkce nad {@link BotNeeds}.
 */
class RestockGoalTest {

    /** Potřeby: tier krumpáče, má pochodně, má železo, počet stavebních bloků. */
    private static BotNeeds needs(int pickTier, boolean torches, boolean iron, int blocks) {
        return new BotNeeds(pickTier, false, false, torches, false, false, iron,
                blocks, true, false, 0);
    }

    @Test
    void kovarBezZelezaChceZelezoAUhli() {
        // Kamenný krumpáč, bez železa a pochodní, dům má → komodity, žádné bloky.
        RestockGoal.RestockPlan plan = RestockGoal.restockPlan(needs(3, false, false, 100), false);
        assertEquals(8, plan.wants().get(Material.RAW_IRON));
        assertEquals(8, plan.wants().get(Material.IRON_INGOT));
        assertEquals(16, plan.wants().get(Material.COAL));
        assertEquals(0, plan.blockCap(), "má dům – bloky nebere");
        assertFalse(plan.isEmpty());
    }

    @Test
    void bezdomovecChceBlokyNaDum() {
        // Vše ostatní má, ale nemá dům → dobere si jen stavební bloky do 64.
        RestockGoal.RestockPlan plan = RestockGoal.restockPlan(needs(1, true, true, 10), true);
        assertTrue(plan.wants().isEmpty(), "komodity nechybí");
        assertEquals(54, plan.blockCap(), "dobrat do 64, má 10");
    }

    @Test
    void bezdomovecSDostatkemBlokuNebere() {
        RestockGoal.RestockPlan plan = RestockGoal.restockPlan(needs(1, true, true, 64), true);
        assertTrue(plan.isEmpty(), "má dost bloků i vše ostatní");
    }

    @Test
    void kdoMaVseNebereNic() {
        RestockGoal.RestockPlan plan = RestockGoal.restockPlan(needs(4, true, true, 100), false);
        assertTrue(plan.isEmpty());
        assertEquals(0, plan.deficitScore());
    }

    @Test
    void slabyKrumpacNesahaPoZeleze() {
        // Dřevěný krumpáč (tier 1) – železo by stejně nevytěžil, tak ho ani nebere.
        RestockGoal.RestockPlan plan = RestockGoal.restockPlan(needs(1, true, false, 100), false);
        assertFalse(plan.wants().containsKey(Material.RAW_IRON), "tier < 3 → žádné železo");
    }

    @Test
    void deficitScoreSecitaStropy() {
        // Železo 8+8, uhlí 16, bloky min(64, 64-40)=24 → 56.
        RestockGoal.RestockPlan plan = RestockGoal.restockPlan(needs(3, false, false, 40), true);
        assertEquals(8 + 8 + 16 + 24, plan.deficitScore());
    }

    @Test
    void utilityVyzadujeSkladMistoIDeficit() {
        assertEquals(0.0, RestockGoal.utilityFor(false, true, 20), "bez skladu");
        assertEquals(0.0, RestockGoal.utilityFor(true, false, 20), "bez místa v batohu");
        assertEquals(0.0, RestockGoal.utilityFor(true, true, 0), "bez deficitu");
        assertTrue(RestockGoal.utilityFor(true, true, 20) > 0);
    }

    @Test
    void utilityZustavaVNizkemPasmu() {
        // I obří deficit nesmí přebít přežití/boj/jídlo (~30+) – je to pomocný úkon.
        assertTrue(RestockGoal.utilityFor(true, true, 500) < 15,
                "odběr zůstává v nízkém pásmu");
    }

    @Test
    void vetsiDeficitTahneSilneji() {
        assertTrue(RestockGoal.utilityFor(true, true, 40)
                > RestockGoal.utilityFor(true, true, 10));
    }
}
