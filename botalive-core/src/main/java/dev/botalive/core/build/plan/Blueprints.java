package dev.botalive.core.build.plan;

import dev.botalive.core.build.HouseBlueprint;
import dev.botalive.core.build.WellBlueprint;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Cardinal;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Legacy adaptéry: dnešní statické blueprinty ({@code HouseBlueprint},
 * {@code WellBlueprint}) obalené do {@link Blueprint} rozhraní beze změny
 * geometrie. Delegace na tytéž statické metody zaručuje bitovou paritu
 * s dosavadní stavbou (viz {@code BlueprintParityTest}); nový engine je tak
 * vede stejně, jen jednou smyčkou.
 */
public final class Blueprints {

    private static final Blueprint HOUSE = new HouseLegacy(false);
    private static final Blueprint GRANARY = new HouseLegacy(true);
    private static final Blueprint WELL = new WellLegacy();

    private Blueprints() {
    }

    /** @return blueprint běžného domku 4×4 (dnešní {@code HouseBlueprint}). */
    public static Blueprint house() {
        return HOUSE;
    }

    /** @return blueprint studny 3×3 (dnešní {@code WellBlueprint}). */
    public static Blueprint well() {
        return WELL;
    }

    /** @return blueprint sýpky (domek 4×4 s dvojtruhlou místo postele). */
    public static Blueprint granary() {
        return GRANARY;
    }

    // ================================================================= dům/sýpka

    /** Dům i sýpka sdílí půdorys {@code HouseBlueprint}; liší se jen vybavením. */
    private record HouseLegacy(boolean granary) implements Blueprint {

        @Override
        public List<PlacementCell> cells(BlockPos origin, Cardinal facing) {
            List<PlacementCell> result = new ArrayList<>();
            for (BlockPos pos : HouseBlueprint.placements(origin, facing)) {
                result.add(new PlacementCell(pos, BlockSpec.GENERIC));
            }
            return result;
        }

        @Override
        public List<BlockPos> clearVolume(BlockPos origin, Cardinal facing) {
            return HouseBlueprint.clearVolume(origin);
        }

        @Override
        public List<BlockPos> groundColumns(BlockPos origin, Cardinal facing) {
            return HouseBlueprint.groundColumns(origin);
        }

        @Override
        public List<FurnishCell> furnishing(BlockPos origin, Cardinal facing) {
            List<FurnishCell> steps = new ArrayList<>();
            steps.add(new FurnishCell(FurnishKind.DOOR,
                    HouseBlueprint.doorBottom(origin, facing)));
            if (granary) {
                // Sýpka: dvojtruhla místo postele (parita s CommunalBuildGoal).
                BlockPos chest = HouseBlueprint.bedSpot(origin, facing);
                steps.add(new FurnishCell(FurnishKind.CHEST, chest));
                steps.add(new FurnishCell(FurnishKind.CHEST, chestNeighbor(origin, facing, chest)));
            } else {
                steps.add(new FurnishCell(FurnishKind.BED,
                        HouseBlueprint.bedSpot(origin, facing)));
            }
            steps.add(new FurnishCell(FurnishKind.TORCH,
                    HouseBlueprint.torchSpot(origin, facing)));
            return steps;
        }

        @Override
        public BlockPos standPoint(BlockPos origin, Cardinal facing) {
            return HouseBlueprint.standPoint(origin, facing);
        }

        @Override
        public Optional<BlockPos> doorCell(BlockPos origin, Cardinal facing) {
            return Optional.of(HouseBlueprint.doorBottom(origin, facing));
        }

        @Override
        public int blocksNeeded() {
            return HouseBlueprint.blocksNeeded();
        }

        @Override
        public boolean standExact() {
            // Sýpka se staví přesně z vnitřku (jako dřív CommunalBuildGoal
            // STEP_IN); dům se staví, kam bot na parcele dojde (tolerantně).
            return granary;
        }

        /**
         * Druhá půlka dvojtruhly: vnitřní soused truhly, který není
         * stanovištěm stavitele ani místem pochodně (parita s privátní
         * {@code CommunalBuildGoal.chestNeighbor}).
         */
        private static BlockPos chestNeighbor(BlockPos origin, Cardinal facing, BlockPos chest) {
            BlockPos stand = HouseBlueprint.standPoint(origin, facing);
            BlockPos torch = HouseBlueprint.torchSpot(origin, facing);
            for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                BlockPos candidate = chest.offset(d[0], 0, d[1]);
                int lx = candidate.x() - origin.x();
                int lz = candidate.z() - origin.z();
                if (lx >= 1 && lx <= HouseBlueprint.SIZE - 2
                        && lz >= 1 && lz <= HouseBlueprint.SIZE - 2
                        && !candidate.equals(stand) && !candidate.equals(torch)) {
                    return candidate;
                }
            }
            return chest.offset(1, 0, 0);
        }
    }

    // ===================================================================== studna

    /** Studna: symetrický věnec 3×3, staví se ze středu šachty (parita s CommunalBuildGoal). */
    private record WellLegacy() implements Blueprint {

        @Override
        public List<PlacementCell> cells(BlockPos origin, Cardinal facing) {
            List<PlacementCell> result = new ArrayList<>();
            for (BlockPos pos : WellBlueprint.placements(origin)) {
                result.add(new PlacementCell(pos, BlockSpec.GENERIC));
            }
            return result;
        }

        @Override
        public List<BlockPos> clearVolume(BlockPos origin, Cardinal facing) {
            return WellBlueprint.clearVolume(origin);
        }

        @Override
        public List<BlockPos> groundColumns(BlockPos origin, Cardinal facing) {
            return WellBlueprint.groundColumns(origin);
        }

        @Override
        public List<FurnishCell> furnishing(BlockPos origin, Cardinal facing) {
            return List.of(new FurnishCell(FurnishKind.TORCH, WellBlueprint.torchSpot(origin)));
        }

        @Override
        public BlockPos standPoint(BlockPos origin, Cardinal facing) {
            // Stavitel stojí DO šachty (střed věnce), ne u severní hrany:
            // odtud dosáhne na celý věnec i pochodeň (CommunalBuildGoal.wellCenter).
            return origin.offset(WellBlueprint.SIZE / 2, 0, WellBlueprint.SIZE / 2);
        }

        @Override
        public Optional<BlockPos> doorCell(BlockPos origin, Cardinal facing) {
            return Optional.empty();
        }

        @Override
        public int blocksNeeded() {
            return WellBlueprint.blocksNeeded();
        }

        @Override
        public boolean standExact() {
            return true; // stavitel musí stát ve středu šachty
        }
    }

    /** Predikát pro vybavení daného druhu (jedno místo pravdy o materiálech). */
    public static java.util.function.Predicate<Material> itemFor(FurnishKind kind) {
        return switch (kind) {
            case DOOR -> m -> m.name().endsWith("_DOOR");
            case TORCH -> m -> m == Material.TORCH;
            case BED -> m -> m.name().endsWith("_BED");
            case CHEST -> m -> m == Material.CHEST;
        };
    }
}
