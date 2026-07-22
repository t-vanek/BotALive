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
    private static final Blueprint MARKET_STALL = new MarketStallLegacy();
    private static final Blueprint TOWN_HALL = new TownHallLegacy();
    private static final Blueprint CHURCH = new ChurchLegacy();

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

    /** @return blueprint tržiště (zastřešený pult 3×3 s truhlou; odemyká město). */
    public static Blueprint marketStall() {
        return MARKET_STALL;
    }

    /** @return blueprint radnice (zděný sál 5×5 s plochou střechou; prestižní stavba města). */
    public static Blueprint townHall() {
        return TOWN_HALL;
    }

    /** @return blueprint kostela (obdélníková loď 5×7 s plochou střechou; prestižní stavba města). */
    public static Blueprint church() {
        return CHURCH;
    }

    /**
     * Blueprint účelné řemeslné dílny: půdorys domku {@link HouseBlueprint}
     * (bouda s dveřmi a pochodní), uvnitř místo postele pracovní stanice.
     * Řemeslník ji pak najde stejným skenem/pamětí jako jakoukoli stanici.
     *
     * @param station   hlavní pracovní stanice (pec, udírna, ponk…) na {@code bedSpot}
     * @param secondary vedlejší stanice (kovářský stůl, řezák…) na {@code sideSpot};
     *                  {@code null} = jen hlavní stanice
     * @return blueprint dílny
     */
    public static Blueprint workshop(Material station, Material secondary) {
        return new WorkshopLegacy(station, secondary);
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

    // ==================================================================== tržiště

    /** Tržiště: zastřešený pult 3×3 – čtyři rohové sloupky, plná střecha, truhla. */
    private record MarketStallLegacy() implements Blueprint {

        private static final int SIZE = 3;
        private static final int POST_HEIGHT = 3;

        private static boolean isCorner(int x, int z) {
            return (x == 0 || x == SIZE - 1) && (z == 0 || z == SIZE - 1);
        }

        @Override
        public List<PlacementCell> cells(BlockPos origin, Cardinal facing) {
            List<PlacementCell> result = new ArrayList<>();
            // Rohové sloupky.
            for (int y = 0; y < POST_HEIGHT; y++) {
                for (int x = 0; x < SIZE; x++) {
                    for (int z = 0; z < SIZE; z++) {
                        if (isCorner(x, z)) {
                            result.add(new PlacementCell(origin.offset(x, y, z), BlockSpec.GENERIC));
                        }
                    }
                }
            }
            // Plná střecha.
            for (int x = 0; x < SIZE; x++) {
                for (int z = 0; z < SIZE; z++) {
                    result.add(new PlacementCell(origin.offset(x, POST_HEIGHT, z), BlockSpec.GENERIC));
                }
            }
            return result;
        }

        @Override
        public List<BlockPos> clearVolume(BlockPos origin, Cardinal facing) {
            List<BlockPos> result = new ArrayList<>();
            for (int x = 0; x < SIZE; x++) {
                for (int y = 0; y <= POST_HEIGHT; y++) {
                    for (int z = 0; z < SIZE; z++) {
                        result.add(origin.offset(x, y, z));
                    }
                }
            }
            return result;
        }

        @Override
        public List<BlockPos> groundColumns(BlockPos origin, Cardinal facing) {
            List<BlockPos> result = new ArrayList<>();
            for (int x = 0; x < SIZE; x++) {
                for (int z = 0; z < SIZE; z++) {
                    result.add(origin.offset(x, -1, z));
                }
            }
            return result;
        }

        @Override
        public List<FurnishCell> furnishing(BlockPos origin, Cardinal facing) {
            // Truhla na zboží u čelní hrany pultu (mezi rohovými sloupky).
            return List.of(new FurnishCell(FurnishKind.CHEST, origin.offset(SIZE / 2, 0, 0)));
        }

        @Override
        public BlockPos standPoint(BlockPos origin, Cardinal facing) {
            return origin.offset(SIZE / 2, 0, SIZE / 2);
        }

        @Override
        public Optional<BlockPos> doorCell(BlockPos origin, Cardinal facing) {
            return Optional.empty();
        }

        @Override
        public int blocksNeeded() {
            return 4 * POST_HEIGHT + SIZE * SIZE;
        }

        @Override
        public boolean standExact() {
            return true; // malý pult – staví se přesně ze středu
        }
    }

    // ==================================================================== radnice

    /**
     * Radnice: prestižní sál 5×5 – obvodové zdi vysoké 3, plochá střecha,
     * dveře orientované k návsi. Bloky role {@code GENERIC} (jako sýpka nebo
     * tržiště), staví se z jednoho stanoviště ve středu (na roh střechy je
     * ~3,4 &lt; dosah 4,3). Neposouvá stupeň sídla – je čistě prestižní.
     */
    private record TownHallLegacy() implements Blueprint {

        private static final int SIZE = 5;
        private static final int WALL_HEIGHT = 3;

        private static boolean isPerimeter(int x, int z) {
            return x == 0 || x == SIZE - 1 || z == 0 || z == SIZE - 1;
        }

        /** Střed stěny (okenní štěrbina ve výšce očí) – ne roh; z každé strany jedna. */
        private static boolean isWindow(int x, int z) {
            boolean xWall = x == 0 || x == SIZE - 1;
            boolean zWall = z == 0 || z == SIZE - 1;
            if (xWall == zWall) {
                return false; // roh (obě strany) nebo vnitřek (žádná)
            }
            return xWall ? z == SIZE / 2 : x == SIZE / 2;
        }

        /** Spodní buňka dveří na straně, kterou se radnice dívá k návsi. */
        private static BlockPos doorBottom(BlockPos origin, Cardinal facing) {
            return origin.offset(SIZE / 2 + facing.dx() * (SIZE / 2), 0,
                    SIZE / 2 + facing.dz() * (SIZE / 2));
        }

        @Override
        public List<PlacementCell> cells(BlockPos origin, Cardinal facing) {
            BlockPos door = doorBottom(origin, facing);
            List<PlacementCell> result = new ArrayList<>();
            // Obvodové zdi mimo dvoublokový otvor dveří.
            for (int y = 0; y < WALL_HEIGHT; y++) {
                for (int x = 0; x < SIZE; x++) {
                    for (int z = 0; z < SIZE; z++) {
                        if (!isPerimeter(x, z)) {
                            continue;
                        }
                        BlockPos pos = origin.offset(x, y, z);
                        if (y < 2 && pos.x() == door.x() && pos.z() == door.z()) {
                            continue; // otvor dveří (y=0,1)
                        }
                        if (y == 1 && isWindow(x, z)) {
                            continue; // okenní štěrbina uprostřed stěny
                        }
                        result.add(new PlacementCell(pos, BlockSpec.GENERIC));
                    }
                }
            }
            // Plná plochá střecha.
            for (int x = 0; x < SIZE; x++) {
                for (int z = 0; z < SIZE; z++) {
                    result.add(new PlacementCell(origin.offset(x, WALL_HEIGHT, z), BlockSpec.GENERIC));
                }
            }
            return result;
        }

        @Override
        public List<BlockPos> clearVolume(BlockPos origin, Cardinal facing) {
            List<BlockPos> result = new ArrayList<>();
            for (int x = 0; x < SIZE; x++) {
                for (int y = 0; y <= WALL_HEIGHT; y++) {
                    for (int z = 0; z < SIZE; z++) {
                        result.add(origin.offset(x, y, z));
                    }
                }
            }
            return result;
        }

        @Override
        public List<BlockPos> groundColumns(BlockPos origin, Cardinal facing) {
            List<BlockPos> result = new ArrayList<>();
            for (int x = 0; x < SIZE; x++) {
                for (int z = 0; z < SIZE; z++) {
                    result.add(origin.offset(x, -1, z));
                }
            }
            return result;
        }

        @Override
        public List<FurnishCell> furnishing(BlockPos origin, Cardinal facing) {
            // Dveře k návsi + jedna pochodeň uvnitř (u zdi, při každé orientaci).
            return List.of(
                    new FurnishCell(FurnishKind.DOOR, doorBottom(origin, facing)),
                    new FurnishCell(FurnishKind.TORCH, origin.offset(1, 1, 1)));
        }

        @Override
        public BlockPos standPoint(BlockPos origin, Cardinal facing) {
            return origin.offset(SIZE / 2, 0, SIZE / 2);
        }

        @Override
        public Optional<BlockPos> doorCell(BlockPos origin, Cardinal facing) {
            return Optional.of(doorBottom(origin, facing));
        }

        @Override
        public int blocksNeeded() {
            // −2 dveře (2 buňky) − 3 okna (3 stěny mimo dveřní).
            return 4 * (SIZE - 1) * WALL_HEIGHT - 5 + SIZE * SIZE;
        }

        @Override
        public boolean standExact() {
            return true; // celý sál se staví přesně ze středu
        }
    }

    // ==================================================================== kostel

    /**
     * Kostel: obdélníková loď 5×7 s vysokými zdmi (4) a plochou střechou,
     * dveře orientované k návsi. Bloky role {@code GENERIC}; větší než radnice,
     * proto se staví z <b>více stanovišť</b> – planner ho rozdělí a
     * {@code BuildSession} přechází mezi vnitřními buňkami podlahy. Prestižní
     * stavba města – neposouvá stupeň.
     */
    private record ChurchLegacy() implements Blueprint {

        private static final int WIDTH = 5;
        private static final int DEPTH = 7;
        private static final int WALL_HEIGHT = 4;

        private static boolean isPerimeter(int x, int z) {
            return x == 0 || x == WIDTH - 1 || z == 0 || z == DEPTH - 1;
        }

        /** Střed stěny (okenní štěrbina ve výšce očí) – ne roh; z každé strany jedna. */
        private static boolean isWindow(int x, int z) {
            boolean xWall = x == 0 || x == WIDTH - 1;
            boolean zWall = z == 0 || z == DEPTH - 1;
            if (xWall == zWall) {
                return false; // roh (obě strany) nebo vnitřek (žádná)
            }
            return xWall ? z == DEPTH / 2 : x == WIDTH / 2;
        }

        /** Spodní buňka dveří na straně, kterou se kostel dívá k návsi. */
        private static BlockPos doorBottom(BlockPos origin, Cardinal facing) {
            return origin.offset(WIDTH / 2 + facing.dx() * (WIDTH / 2), 0,
                    DEPTH / 2 + facing.dz() * (DEPTH / 2));
        }

        @Override
        public List<PlacementCell> cells(BlockPos origin, Cardinal facing) {
            BlockPos door = doorBottom(origin, facing);
            List<PlacementCell> result = new ArrayList<>();
            for (int y = 0; y < WALL_HEIGHT; y++) {
                for (int x = 0; x < WIDTH; x++) {
                    for (int z = 0; z < DEPTH; z++) {
                        if (!isPerimeter(x, z)) {
                            continue;
                        }
                        BlockPos pos = origin.offset(x, y, z);
                        if (y < 2 && pos.x() == door.x() && pos.z() == door.z()) {
                            continue; // otvor dveří (y=0,1)
                        }
                        if (y == 1 && isWindow(x, z)) {
                            continue; // okenní štěrbina uprostřed stěny
                        }
                        result.add(new PlacementCell(pos, BlockSpec.GENERIC));
                    }
                }
            }
            for (int x = 0; x < WIDTH; x++) {
                for (int z = 0; z < DEPTH; z++) {
                    result.add(new PlacementCell(origin.offset(x, WALL_HEIGHT, z), BlockSpec.GENERIC));
                }
            }
            return result;
        }

        @Override
        public List<BlockPos> clearVolume(BlockPos origin, Cardinal facing) {
            List<BlockPos> result = new ArrayList<>();
            for (int x = 0; x < WIDTH; x++) {
                for (int y = 0; y <= WALL_HEIGHT; y++) {
                    for (int z = 0; z < DEPTH; z++) {
                        result.add(origin.offset(x, y, z));
                    }
                }
            }
            return result;
        }

        @Override
        public List<BlockPos> groundColumns(BlockPos origin, Cardinal facing) {
            List<BlockPos> result = new ArrayList<>();
            for (int x = 0; x < WIDTH; x++) {
                for (int z = 0; z < DEPTH; z++) {
                    result.add(origin.offset(x, -1, z));
                }
            }
            return result;
        }

        @Override
        public List<FurnishCell> furnishing(BlockPos origin, Cardinal facing) {
            // Dveře k návsi + jedna pochodeň uvnitř (u zdi, při každé orientaci).
            return List.of(
                    new FurnishCell(FurnishKind.DOOR, doorBottom(origin, facing)),
                    new FurnishCell(FurnishKind.TORCH, origin.offset(1, 1, 1)));
        }

        @Override
        public BlockPos standPoint(BlockPos origin, Cardinal facing) {
            return origin.offset(WIDTH / 2, 0, DEPTH / 2);
        }

        @Override
        public Optional<BlockPos> doorCell(BlockPos origin, Cardinal facing) {
            return Optional.of(doorBottom(origin, facing));
        }

        @Override
        public int blocksNeeded() {
            // −2 dveře (2 buňky) − 3 okna (3 stěny mimo dveřní).
            return (2 * WIDTH + 2 * DEPTH - 4) * WALL_HEIGHT - 5 + WIDTH * DEPTH;
        }

        @Override
        public boolean standExact() {
            return false; // velká loď – staví se z více vnitřních stanovišť
        }
    }

    // ==================================================================== dílna

    /**
     * Účelná řemeslná dílna: sdílí půdorys {@link HouseBlueprint} (bouda
     * s dveřmi), místo postele nese hlavní pracovní stanici a volitelně
     * vedlejší. Staví se přesně z vnitřku ({@code standExact}) jako sýpka –
     * odtud stavitel dosáhne na obě vnitřní stanice i pochodeň.
     */
    private record WorkshopLegacy(Material station, Material secondary) implements Blueprint {

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
            steps.add(new FurnishCell(FurnishKind.STATION,
                    HouseBlueprint.bedSpot(origin, facing), station));
            if (secondary != null) {
                steps.add(new FurnishCell(FurnishKind.STATION,
                        HouseBlueprint.sideSpot(origin, facing), secondary));
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
            return true; // stanice se osazují přesně z vnitřku (jako sýpka)
        }
    }

    /** Predikát pro vybavení daného druhu (jedno místo pravdy o materiálech). */
    public static java.util.function.Predicate<Material> itemFor(FurnishKind kind) {
        return switch (kind) {
            case DOOR -> m -> m.name().endsWith("_DOOR");
            case TORCH -> m -> m == Material.TORCH;
            case BED -> m -> m.name().endsWith("_BED");
            case CHEST -> m -> m == Material.CHEST;
            case STATION -> throw new IllegalArgumentException(
                    "STATION nese materiál v FurnishCell.material() – použij ho místo itemFor");
        };
    }
}
