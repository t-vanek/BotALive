package dev.botalive.core.build.plan;

import dev.botalive.core.build.HouseBlueprint;
import dev.botalive.core.build.WellBlueprint;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Cardinal;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Legacy adaptéry ({@link Blueprints}) musí vydat bitově stejnou geometrii
 * jako dosavadní statické blueprinty – jinak by engine stavěl jinak než dnes.
 * Kontroluje se pro všechny orientace a několik originů.
 */
class BlueprintParityTest {

    private static final BlockPos[] ORIGINS = {
            new BlockPos(0, 64, 0),
            new BlockPos(-13, 70, 41),
            new BlockPos(100, 63, -100),
    };

    private static Set<BlockPos> posSet(List<PlacementCell> cells) {
        return cells.stream().map(PlacementCell::pos).collect(Collectors.toSet());
    }

    @Test
    void houseCellsMatchLegacyForEveryFacing() {
        Blueprint house = Blueprints.house();
        for (BlockPos origin : ORIGINS) {
            for (Cardinal facing : Cardinal.values()) {
                List<BlockPos> legacy = HouseBlueprint.placements(origin, facing);
                List<BlockPos> engine = house.cells(origin, facing).stream()
                        .map(PlacementCell::pos).toList();
                assertEquals(legacy, engine,
                        "pořadí i pozice bloků domu se musí shodovat (" + facing + ")");
                assertEquals(HouseBlueprint.clearVolume(origin),
                        house.clearVolume(origin, facing), "clearVolume domu");
                assertEquals(HouseBlueprint.groundColumns(origin),
                        house.groundColumns(origin, facing), "groundColumns domu");
                assertEquals(HouseBlueprint.standPoint(origin, facing),
                        house.standPoint(origin, facing), "stanoviště domu");
                assertEquals(HouseBlueprint.blocksNeeded(), house.blocksNeeded());
            }
        }
    }

    @Test
    void wellCellsMatchLegacyAndStandInShaft() {
        Blueprint well = Blueprints.well();
        for (BlockPos origin : ORIGINS) {
            for (Cardinal facing : Cardinal.values()) {
                assertEquals(WellBlueprint.placements(origin),
                        well.cells(origin, facing).stream().map(PlacementCell::pos).toList(),
                        "pozice věnce studny");
                assertEquals(WellBlueprint.clearVolume(origin),
                        well.clearVolume(origin, facing), "clearVolume studny");
                assertEquals(WellBlueprint.groundColumns(origin),
                        well.groundColumns(origin, facing), "groundColumns studny");
                // Stanoviště je STŘED šachty (parita s CommunalBuildGoal.wellCenter),
                // ne severní hrana WellBlueprint.standPoint.
                assertEquals(origin.offset(WellBlueprint.SIZE / 2, 0, WellBlueprint.SIZE / 2),
                        well.standPoint(origin, facing), "stavitel stojí ve středu věnce");
                assertTrue(well.doorCell(origin, facing).isEmpty(), "studna nemá dveře");
            }
        }
    }

    @Test
    void granarySharesHouseShellWithChestFurnishing() {
        Blueprint granary = Blueprints.granary();
        BlockPos origin = ORIGINS[0];
        Cardinal facing = Cardinal.NORTH;
        // Skořápka je totožná s domem.
        assertEquals(HouseBlueprint.placements(origin, facing),
                granary.cells(origin, facing).stream().map(PlacementCell::pos).toList(),
                "sýpka sdílí skořápku domu");
        // Vybavení: dveře + dvě truhly + pochodeň, žádná postel.
        List<FurnishCell> furnish = granary.furnishing(origin, facing);
        long doors = furnish.stream().filter(f -> f.kind() == FurnishKind.DOOR).count();
        long chests = furnish.stream().filter(f -> f.kind() == FurnishKind.CHEST).count();
        long torches = furnish.stream().filter(f -> f.kind() == FurnishKind.TORCH).count();
        long beds = furnish.stream().filter(f -> f.kind() == FurnishKind.BED).count();
        assertEquals(1, doors, "dveře");
        assertEquals(2, chests, "dvojtruhla");
        assertEquals(1, torches, "pochodeň");
        assertEquals(0, beds, "sýpka nemá postel");
        // Truhly jsou dvě různé sousední buňky uvnitř 2×2 interiéru.
        List<BlockPos> chestPos = furnish.stream().filter(f -> f.kind() == FurnishKind.CHEST)
                .map(FurnishCell::pos).toList();
        assertEquals(2, Set.copyOf(chestPos).size(), "truhly nesmí splývat");
        assertEquals(1, chestPos.get(0).distanceSquared(chestPos.get(1)),
                "dvojtruhla musí sousedit");
    }

    @Test
    void houseFurnishingIsDoorBedTorch() {
        List<FurnishCell> furnish = Blueprints.house()
                .furnishing(ORIGINS[0], Cardinal.SOUTH);
        assertEquals(Set.of(FurnishKind.DOOR, FurnishKind.BED, FurnishKind.TORCH),
                furnish.stream().map(FurnishCell::kind).collect(Collectors.toSet()));
        assertEquals(HouseBlueprint.doorBottom(ORIGINS[0], Cardinal.SOUTH),
                Blueprints.house().doorCell(ORIGINS[0], Cardinal.SOUTH).orElseThrow());
    }
}
