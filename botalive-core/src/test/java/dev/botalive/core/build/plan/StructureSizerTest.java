package dev.botalive.core.build.plan;

import dev.botalive.core.settlement.SettlementTier;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Jednotný zdroj pravdy o velikosti staveb: rozměr roste s prosperitou sídla,
 * moduluje ho osobnost, strop drží konfigurace. Invarianty (lichost šířky,
 * meze výšky) jsou pojistkou, že {@link HouseGenerator} dostane platné parametry.
 */
class StructureSizerTest {

    private static final int WIDTH_CAP = 9;
    private static final int MIN_H = 3;
    private static final int MAX_H = 5;

    @Test
    void houseWidthGrowsWithTierAndShrinksWithLaziness() {
        double diligent = 0.2;
        assertEquals(5, StructureSizer.houseWidth(SettlementTier.OSADA, diligent, 7));
        assertEquals(7, StructureSizer.houseWidth(SettlementTier.VESNICE, diligent, 7));
        assertEquals(7, StructureSizer.houseWidth(SettlementTier.MESTO, diligent, 7));
        assertEquals(9, StructureSizer.houseWidth(SettlementTier.MESTO, diligent, 9));
        // Líný bydlí skromně i ve městě.
        assertEquals(5, StructureSizer.houseWidth(SettlementTier.MESTO, 0.9, 9));
        // Bez sídla = osada.
        assertEquals(5, StructureSizer.houseWidth(null, diligent, 9));
    }

    @Test
    void houseWidthAlwaysOddAndAtLeastFive() {
        for (SettlementTier tier : SettlementTier.values()) {
            for (double lz : new double[]{0.1, 0.5, 0.9}) {
                for (int cap : new int[]{5, 6, 7, 8, 9, 11}) {
                    int w = StructureSizer.houseWidth(tier, lz, cap);
                    assertTrue(w >= 5, "aspoň 5: " + w);
                    assertTrue(w % 2 == 1, "lichý: " + w);
                    assertTrue(w <= Math.max(5, cap), "pod stropem: " + w + " cap " + cap);
                }
            }
        }
    }

    @Test
    void wallHeightGrowsWithTierWithinBounds() {
        double neutral = 0.5;
        // Osada = spodní mez (zpětná kompatibilita s dnešní pevnou výškou 3).
        assertEquals(MIN_H, StructureSizer.houseWallHeight(SettlementTier.OSADA, neutral, MIN_H, MAX_H));
        assertEquals(MIN_H + 1, StructureSizer.houseWallHeight(SettlementTier.VESNICE, neutral, MIN_H, MAX_H));
        assertEquals(MIN_H + 2, StructureSizer.houseWallHeight(SettlementTier.MESTO, neutral, MIN_H, MAX_H));
        // Pracovitý o patro výš (ale ne nad strop); líný o patro níž.
        assertEquals(MAX_H, StructureSizer.houseWallHeight(SettlementTier.MESTO, 0.1, MIN_H, MAX_H));
        assertEquals(MIN_H + 1, StructureSizer.houseWallHeight(SettlementTier.MESTO, 0.9, MIN_H, MAX_H));
        assertEquals(MIN_H - 1, StructureSizer.houseWallHeight(SettlementTier.OSADA, 0.9, MIN_H, MAX_H));
    }

    @Test
    void wallHeightNeverBelowTwoNorAboveMax() {
        for (SettlementTier tier : SettlementTier.values()) {
            for (double lz : new double[]{0.1, 0.5, 0.9}) {
                int h = StructureSizer.houseWallHeight(tier, lz, 2, MAX_H);
                assertTrue(h >= 2, "aspoň 2: " + h);
                assertTrue(h <= MAX_H, "pod stropem: " + h);
            }
        }
    }

    @Test
    void houseIsSquareAndMonotonicInProsperity() {
        double neutral = 0.5;
        StructureSize osada = StructureSizer.house(SettlementTier.OSADA, neutral, WIDTH_CAP, MIN_H, MAX_H);
        StructureSize vesnice = StructureSizer.house(SettlementTier.VESNICE, neutral, WIDTH_CAP, MIN_H, MAX_H);
        StructureSize mesto = StructureSizer.house(SettlementTier.MESTO, neutral, WIDTH_CAP, MIN_H, MAX_H);
        assertEquals(osada.width(), osada.depth(), "dům je čtvercový");
        assertEquals(mesto.width(), mesto.depth(), "dům je čtvercový");
        // Prosperita nikdy nezmenší (monotonie po ose i po výšce).
        assertTrue(vesnice.exceeds(osada), "vesnice > osada");
        assertTrue(mesto.exceeds(vesnice), "město > vesnice");
    }

    @Test
    void scaledHallGrowsWithCityAndClampsToSpan() {
        // Vesnice: základní velikost; město: o krok větší (monotónní).
        StructureSize village = StructureSizer.scaledHall(5, 5, 5, SettlementTier.VESNICE, 9);
        StructureSize town = StructureSizer.scaledHall(5, 5, 5, SettlementTier.MESTO, 9);
        assertEquals(new StructureSize(5, 5, 5), village, "vesnice = základ");
        assertEquals(new StructureSize(7, 7, 6), town, "město o krok větší");
        // Span se přichytí pod strop (kostel 5×7 ve městě → hloubka nejvýš 9).
        StructureSize church = StructureSizer.scaledHall(5, 7, 6, SettlementTier.MESTO, 9);
        assertTrue(church.footprintSpan() <= 9, "span pod stropem");
        assertEquals(9, church.depth(), "hloubka přichycená na strop");
    }

    @Test
    void tierDelegationMatchesLegacy() {
        // HouseDesigner.tierFor deleguje sem – kontrakt zůstává.
        assertEquals(BuildTier.PROVISIONAL, StructureSizer.houseTier(SettlementTier.OSADA, 0.5));
        assertEquals(BuildTier.SOLID, StructureSizer.houseTier(SettlementTier.VESNICE, 0.5));
        assertEquals(BuildTier.REFINED, StructureSizer.houseTier(SettlementTier.MESTO, 0.5));
    }
}
