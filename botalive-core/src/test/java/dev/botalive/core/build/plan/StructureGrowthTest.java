package dev.botalive.core.build.plan;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Cardinal;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Strukturální růst domu: <b>aditivně napřed, demolice vnitřku naposled</b>.
 * Klíčový invariant je nepromokavost – dokud se dostavuje nový plášť, nic se
 * nebourá (starý krov drží), a teprve když plášť stojí, odklidí se přesně staré
 * strukturální bloky, které se staly vnitřními (ne nový plášť, ne vybavení).
 */
class StructureGrowthTest {

    private static final BlockPos OLD_ORIGIN = new BlockPos(0, 64, 0);
    // Dům 5×5 roste na 7×7 se zachovaným středem (center-fixed):
    // starý střed = origin+2 = (2,64,2); nový roh = střed − 3 = (−1,64,−1).
    private static final BlockPos NEW_ORIGIN = new BlockPos(-1, 64, -1);
    private static final HouseGenerator OLD = new HouseGenerator(5, 3);
    private static final HouseGenerator NEW = new HouseGenerator(7, 4);

    @Test
    void originMathKeepsCenterFixed() {
        // Pojistka výpočtu originu v MaintainHomeGoal.maybeGrow: střed (a tím
        // i stanoviště a klíč HOME paměti) se růstem nehýbe.
        assertEquals(OLD.standPoint(OLD_ORIGIN, Cardinal.NORTH),
                NEW.standPoint(NEW_ORIGIN, Cardinal.NORTH), "střed domu je invariantní");
    }

    @Test
    void additionsFirstNoDemolitionUntilShellStands() {
        // Svět: stojí jen starý 5×5 dům.
        Set<Long> solid = cellSet(OLD, OLD_ORIGIN);
        StructureGrowth.Plan plan = StructureGrowth.plan(OLD, OLD_ORIGIN, NEW, NEW_ORIGIN,
                Cardinal.NORTH, inSet(solid), 1000, 1000);

        assertFalse(plan.additions().isEmpty(), "nový (větší) plášť se přistavuje");
        assertTrue(plan.demolitions().isEmpty(),
                "dokud plášť nestojí, nic se nebourá (dům zůstává zakrytý)");
        assertFalse(plan.shellComplete(), "plášť ještě nestojí");
        // Přídavky jsou jen buňky, které ještě nestojí.
        for (PlacementCell add : plan.additions()) {
            assertFalse(solid.contains(add.pos().asLong()), "přidává se jen nepostavené");
        }
    }

    @Test
    void demolishesExactlyOldInteriorOnceShellComplete() {
        // Svět: stojí starý dům I celý nový plášť.
        Set<Long> solid = cellSet(OLD, OLD_ORIGIN);
        solid.addAll(cellSet(NEW, NEW_ORIGIN));
        StructureGrowth.Plan plan = StructureGrowth.plan(OLD, OLD_ORIGIN, NEW, NEW_ORIGIN,
                Cardinal.NORTH, inSet(solid), 1000, 1000);

        assertTrue(plan.additions().isEmpty(), "plášť stojí – nic k přidání");
        assertTrue(plan.shellComplete(), "plášť je hotový");
        assertFalse(plan.demolitions().isEmpty(), "odklidit starý vnitřek");

        Set<Long> newCells = cellSet(NEW, NEW_ORIGIN);
        Set<Long> oldCells = cellSet(OLD, OLD_ORIGIN);
        for (BlockPos demo : plan.demolitions()) {
            assertFalse(newCells.contains(demo.asLong()), "nikdy nebourat nový plášť");
            assertTrue(oldCells.contains(demo.asLong()), "demolice je jen starý strukturální blok");
        }
    }

    @Test
    void partialShellStillDefersDemolition() {
        // Svět: starý dům + jen ČÁST nového pláště (polovina nových buněk).
        Set<Long> solid = cellSet(OLD, OLD_ORIGIN);
        List<PlacementCell> newCells = NEW.cells(NEW_ORIGIN, Cardinal.NORTH);
        for (int i = 0; i < newCells.size() / 2; i++) {
            solid.add(newCells.get(i).pos().asLong());
        }
        StructureGrowth.Plan plan = StructureGrowth.plan(OLD, OLD_ORIGIN, NEW, NEW_ORIGIN,
                Cardinal.NORTH, inSet(solid), 1000, 1000);
        // Invariant: dokud zbývá aspoň jeden přídavek, demolice je prázdná.
        assertFalse(plan.additions().isEmpty());
        assertTrue(plan.demolitions().isEmpty(), "demolice až po celém plášti");
    }

    @Test
    void idempotentOnceFullyGrown() {
        // Svět: stojí jen nový dům (starý vnitřek už odklizen).
        Set<Long> solid = cellSet(NEW, NEW_ORIGIN);
        StructureGrowth.Plan plan = StructureGrowth.plan(OLD, OLD_ORIGIN, NEW, NEW_ORIGIN,
                Cardinal.NORTH, inSet(solid), 1000, 1000);
        assertTrue(plan.done(), "dorostlý dům: nic k přidání ani k odklizení (idempotence)");
    }

    // ------------------------------------------------------------------ pomocné

    private static Set<Long> cellSet(HouseGenerator gen, BlockPos origin) {
        Set<Long> set = new HashSet<>();
        for (PlacementCell c : gen.cells(origin, Cardinal.NORTH)) {
            set.add(c.pos().asLong());
        }
        return set;
    }

    private static Predicate<BlockPos> inSet(Set<Long> solid) {
        return p -> solid.contains(p.asLong());
    }
}
