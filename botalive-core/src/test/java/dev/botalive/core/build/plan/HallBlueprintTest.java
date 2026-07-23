package dev.botalive.core.build.plan;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Cardinal;

import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Sizované zděné sály (sýpka/sklad, dílna, radnice, kostel) – parametrická
 * varianta {@code CivicHall}. Musí být <b>postavitelné</b> (opora při pokládce)
 * a nést správné vybavení podle využití; pod legacy velikostí drží dnešní pevné
 * stavby (parita).
 */
class HallBlueprintTest {

    private static final BlockPos ORIGIN = new BlockPos(0, 64, 0);

    @Test
    void legacySizeKeepsFixedBuildings() {
        // Prázdný / malý rozměr = dnešní pevná stavba (parita, už postavené stavby).
        assertSame(Blueprints.granary(), Blueprints.granary(null));
        assertSame(Blueprints.granary(), Blueprints.granary(new StructureSize(4, 4, 3)));
        assertSame(Blueprints.townHall(), Blueprints.townHall(null));
        assertSame(Blueprints.church(), Blueprints.church(new StructureSize(4, 4, 3)));
    }

    @Test
    void sizedGranaryHasDoubleChestAndBuilds() {
        var size = new StructureSize(7, 7, 4);
        var granary = Blueprints.granary(size);
        List<FurnishCell> furnish = granary.furnishing(ORIGIN, Cardinal.NORTH);
        long chests = furnish.stream().filter(f -> f.kind() == FurnishKind.CHEST).count();
        assertEquals(2, chests, "sýpka má dvojtruhlu");
        assertTrue(furnish.stream().anyMatch(f -> f.kind() == FurnishKind.DOOR), "má dveře");
        // Truhly jsou uvnitř (ne v obvodové zdi) a sousedí (dvojtruhla).
        List<BlockPos> chestPos = furnish.stream().filter(f -> f.kind() == FurnishKind.CHEST)
                .map(FurnishCell::pos).toList();
        assertEquals(1, Math.abs(chestPos.get(0).x() - chestPos.get(1).x())
                + Math.abs(chestPos.get(0).z() - chestPos.get(1).z()), "truhly sousedí");
        assertSupported(granary);
    }

    @Test
    void storageChestResolvesFromGeometry() {
        var size = new StructureSize(9, 9, 4);
        var granary = Blueprints.granary(size);
        Optional<BlockPos> chest = Blueprints.storageChest(granary, ORIGIN, Cardinal.NORTH);
        assertTrue(chest.isPresent(), "truhla se dopočítá z geometrie");
        // Uvnitř půdorysu (ne ve zdi ani mimo).
        int lx = chest.get().x() - ORIGIN.x();
        int lz = chest.get().z() - ORIGIN.z();
        assertTrue(lx >= 1 && lx <= 7 && lz >= 1 && lz <= 7, "truhla je uvnitř");
        // Legacy sýpka: truhla se dopočítá stejným helperem (parita s HouseBlueprint).
        assertTrue(Blueprints.storageChest(Blueprints.granary(), ORIGIN, Cardinal.NORTH).isPresent(),
                "i legacy sýpka má dopočitatelnou truhlu");
    }

    @Test
    void sizedWorkshopCarriesStationsAndBuilds() {
        var size = new StructureSize(7, 7, 4);
        var workshop = Blueprints.workshop(Material.FURNACE, Material.SMITHING_TABLE, size);
        List<FurnishCell> furnish = workshop.furnishing(ORIGIN, Cardinal.NORTH);
        long stations = furnish.stream().filter(f -> f.kind() == FurnishKind.STATION).count();
        assertEquals(2, stations, "hlavní + vedlejší stanice");
        assertTrue(furnish.stream().anyMatch(f -> f.kind() == FurnishKind.STATION
                && f.material() == Material.FURNACE), "hlavní stanice je pec");
        assertSupported(workshop);
    }

    @Test
    void sizedCivicHallScalesAndBuilds() {
        var big = Blueprints.townHall(new StructureSize(9, 9, 6));
        // Větší než legacy 5×5×5.
        assertTrue(big.blocksNeeded() > Blueprints.townHall().blocksNeeded(),
                "větší město = větší radnice");
        assertSupported(big);
    }

    /** Každý blok musí mít při pokládce oporu (jinak {@link BuildPlanner#order} vyhodí). */
    private static void assertSupported(dev.botalive.core.build.plan.Blueprint bp) {
        for (Cardinal facing : Cardinal.values()) {
            List<PlacementCell> ordered = BuildPlanner.order(
                    bp.cells(ORIGIN, facing), bp.groundColumns(ORIGIN, facing));
            Set<Long> solid = new HashSet<>();
            bp.groundColumns(ORIGIN, facing).forEach(g -> solid.add(g.asLong()));
            for (PlacementCell cell : ordered) {
                assertTrue(hasNeighbor(cell.pos(), solid),
                        "blok " + cell.pos() + " nemá oporu (" + facing + ")");
                solid.add(cell.pos().asLong());
            }
            assertFalse(ordered.isEmpty(), "sál má bloky");
        }
    }

    private static boolean hasNeighbor(BlockPos pos, Set<Long> solid) {
        return solid.contains(pos.down().asLong()) || solid.contains(pos.up().asLong())
                || solid.contains(pos.offset(1, 0, 0).asLong())
                || solid.contains(pos.offset(-1, 0, 0).asLong())
                || solid.contains(pos.offset(0, 0, 1).asLong())
                || solid.contains(pos.offset(0, 0, -1).asLong());
    }
}
