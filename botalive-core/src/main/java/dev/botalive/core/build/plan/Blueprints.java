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
    private static final Blueprint TOWN_HALL = new CivicHall(5, 5, 5, false);
    private static final Blueprint CHURCH = new CivicHall(5, 7, 6, false);
    private static final Blueprint BELL_TOWER = new BellTower();

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

    /** @return blueprint zvonice (otevřená zvonička 3×3 se zvonem; prestižní stavba města). */
    public static Blueprint bellTower() {
        return BELL_TOWER;
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

    // ============================================================== civilní sály

    /**
     * Civilní sál sídla (radnice, kostel) – parametrická obdélníková zděná
     * stavba {@code width×depth} s plochou střechou, dveřmi orientovanými
     * k návsi a okenními štěrbinami uprostřed ostatních stěn (od výšky očí po
     * předposlední řadu, takže vyšší zdi mají vyšší okna). Bloky role
     * {@code GENERIC} jako sýpka/tržiště. Prestižní stavby města – neposouvají
     * stupeň. Parametry drží vrstvu laditelnou: malý sál ({@code standExact})
     * se postaví z jednoho stanoviště, větší přes multi-standpoint planneru.
     *
     * @param width      šířka (X); okno je ve středu stěn kolmých na X
     * @param depth      hloubka (Z)
     * @param wallHeight výška zdí (≥ 3)
     * @param standExact staví se přesně ze středu (malý sál), nebo z více míst
     */
    private record CivicHall(int width, int depth, int wallHeight, boolean standExact)
            implements Blueprint {

        private boolean isPerimeter(int x, int z) {
            return x == 0 || x == width - 1 || z == 0 || z == depth - 1;
        }

        /** Střed stěny (okenní štěrbina) – ne roh; z každé strany jedna. */
        private boolean isWindow(int x, int z) {
            boolean xWall = x == 0 || x == width - 1;
            boolean zWall = z == 0 || z == depth - 1;
            if (xWall == zWall) {
                return false; // roh (obě strany) nebo vnitřek (žádná)
            }
            return xWall ? z == depth / 2 : x == width / 2;
        }

        /** Spodní buňka dveří na straně, kterou se sál dívá k návsi. */
        private BlockPos doorBottom(BlockPos origin, Cardinal facing) {
            return origin.offset(width / 2 + facing.dx() * (width / 2), 0,
                    depth / 2 + facing.dz() * (depth / 2));
        }

        @Override
        public List<PlacementCell> cells(BlockPos origin, Cardinal facing) {
            BlockPos door = doorBottom(origin, facing);
            List<PlacementCell> result = new ArrayList<>();
            // Obvodové zdi mimo otvor dveří (2 buňky) a okenní štěrbiny.
            for (int y = 0; y < wallHeight; y++) {
                for (int x = 0; x < width; x++) {
                    for (int z = 0; z < depth; z++) {
                        if (!isPerimeter(x, z)) {
                            continue;
                        }
                        BlockPos pos = origin.offset(x, y, z);
                        boolean doorColumn = pos.x() == door.x() && pos.z() == door.z();
                        if (doorColumn && y < 2) {
                            continue; // otvor dveří (y=0,1)
                        }
                        if (!doorColumn && isWindow(x, z) && y >= 1 && y <= wallHeight - 2) {
                            continue; // okno: od očí po předposlední řadu (sokl a překlad zůstanou)
                        }
                        result.add(new PlacementCell(pos, BlockSpec.GENERIC));
                    }
                }
            }
            // Plná plochá střecha.
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
                    result.add(new PlacementCell(origin.offset(x, wallHeight, z), BlockSpec.GENERIC));
                }
            }
            return result;
        }

        @Override
        public List<BlockPos> clearVolume(BlockPos origin, Cardinal facing) {
            List<BlockPos> result = new ArrayList<>();
            for (int x = 0; x < width; x++) {
                for (int y = 0; y <= wallHeight; y++) {
                    for (int z = 0; z < depth; z++) {
                        result.add(origin.offset(x, y, z));
                    }
                }
            }
            return result;
        }

        @Override
        public List<BlockPos> groundColumns(BlockPos origin, Cardinal facing) {
            List<BlockPos> result = new ArrayList<>();
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < depth; z++) {
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
            return origin.offset(width / 2, 0, depth / 2);
        }

        @Override
        public Optional<BlockPos> doorCell(BlockPos origin, Cardinal facing) {
            return Optional.of(doorBottom(origin, facing));
        }

        @Override
        public int blocksNeeded() {
            int perimeter = 2 * width + 2 * depth - 4;
            int windows = 3 * (wallHeight - 2); // 3 stěny mimo dveřní, od očí nahoru
            return perimeter * wallHeight - 2 /* dveře */ - windows + width * depth;
        }
    }

    // =================================================================== zvonice

    /**
     * Zvonice – vyšší věž sídla (prestižní stavba města). Zděná dutá šachta
     * 3×3 s dveřmi k návsi, nahoře otevřené zvonové patro (čtyři rohové
     * sloupky, mezi nimi je vidět dovnitř) a plochý baldachýn; pod baldachýnem
     * visí zvon. Bloky role {@code GENERIC} jako ostatní civilní stavby.
     *
     * <p>Věž je vyšší než dosah ze země (baldachýn v {@code y=6}): horní patro
     * postaví stavitel z <b>vyvýšeného pilířového stanoviště</b> ({@link
     * BuildPlanner} lešení), které si vypilíruje ve volné šachtě a po dostavbě
     * odklidí. Zvon visí v {@code y=5} – z podlahy dosažitelný ({@code
     * ~3,9 ≤ 4,3}), takže se osadí jako každé vybavení. Věž tak výrazně
     * převyšuje sály (radnice v3, kostel v4) a v siluetě sídla je nepřehlédnutelná.</p>
     */
    private record BellTower() implements Blueprint {

        private static final int SIZE = 3;
        private static final int WALL_HEIGHT = 5; // šachta y=0..4 (dutá, s dveřmi)
        private static final int POST_Y = 5;      // rohové sloupky zvonového patra
        private static final int CANOPY_Y = 6;    // plochý baldachýn
        private static final int BELL_Y = 5;      // zvon visí pod baldachýnem (z podlahy dosažitelný)

        private static boolean isPerimeter(int x, int z) {
            return x == 0 || x == SIZE - 1 || z == 0 || z == SIZE - 1;
        }

        private static boolean isCorner(int x, int z) {
            return (x == 0 || x == SIZE - 1) && (z == 0 || z == SIZE - 1);
        }

        /** Spodní buňka dveří na straně k návsi. */
        private static BlockPos doorBottom(BlockPos origin, Cardinal facing) {
            return origin.offset(SIZE / 2 + facing.dx() * (SIZE / 2), 0,
                    SIZE / 2 + facing.dz() * (SIZE / 2));
        }

        @Override
        public List<PlacementCell> cells(BlockPos origin, Cardinal facing) {
            BlockPos door = doorBottom(origin, facing);
            List<PlacementCell> result = new ArrayList<>();
            // Základna: obvodové zdi mimo otvor dveří (2 buňky y=0,1).
            for (int y = 0; y < WALL_HEIGHT; y++) {
                for (int x = 0; x < SIZE; x++) {
                    for (int z = 0; z < SIZE; z++) {
                        if (!isPerimeter(x, z)) {
                            continue;
                        }
                        BlockPos pos = origin.offset(x, y, z);
                        if (pos.x() == door.x() && pos.z() == door.z() && y < 2) {
                            continue; // otvor dveří
                        }
                        result.add(new PlacementCell(pos, BlockSpec.GENERIC));
                    }
                }
            }
            // Zvonové patro: čtyři rohové sloupky (mezi nimi je otevřeno).
            for (int x = 0; x < SIZE; x++) {
                for (int z = 0; z < SIZE; z++) {
                    if (isCorner(x, z)) {
                        result.add(new PlacementCell(origin.offset(x, POST_Y, z), BlockSpec.GENERIC));
                    }
                }
            }
            // Plochý baldachýn.
            for (int x = 0; x < SIZE; x++) {
                for (int z = 0; z < SIZE; z++) {
                    result.add(new PlacementCell(origin.offset(x, CANOPY_Y, z), BlockSpec.GENERIC));
                }
            }
            return result;
        }

        @Override
        public List<BlockPos> clearVolume(BlockPos origin, Cardinal facing) {
            List<BlockPos> result = new ArrayList<>();
            for (int x = 0; x < SIZE; x++) {
                for (int y = 0; y <= CANOPY_Y; y++) {
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
            // Dveře k návsi a zvon visící ve středu zvonového patra pod baldachýnem.
            return List.of(
                    new FurnishCell(FurnishKind.DOOR, doorBottom(origin, facing)),
                    new FurnishCell(FurnishKind.BELL, origin.offset(SIZE / 2, BELL_Y, SIZE / 2)));
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
            int perimeter = 4 * SIZE - 4;          // 8 obvodových buněk 3×3
            int base = perimeter * WALL_HEIGHT - 2; // − otvor dveří (2)
            return base + 4 /* sloupky */ + SIZE * SIZE /* baldachýn */;
        }

        @Override
        public boolean standExact() {
            return true; // úzká šachta – staví se přesně ze středového sloupce (i z pilíře)
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
            case LANTERN -> m -> m == Material.LANTERN;
            case BED -> m -> m.name().endsWith("_BED");
            case CHEST -> m -> m == Material.CHEST;
            case BELL -> m -> m == Material.BELL;
            case STATION -> throw new IllegalArgumentException(
                    "STATION nese materiál v FurnishCell.material() – použij ho místo itemFor");
        };
    }
}
