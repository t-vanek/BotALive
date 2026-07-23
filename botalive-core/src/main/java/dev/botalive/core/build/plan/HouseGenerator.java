package dev.botalive.core.build.plan;

import dev.botalive.core.build.HouseBlueprint;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Cardinal;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Parametrický generátor domu: čtvercový půdorys {@code width} (lichý – čistý
 * střed pro stavitele), kamenná základová obruba, prkenné zdi s dřevěnými
 * nárožími a skleněnými okny, valbová střecha z plných bloků stupňovitě do
 * špičky. Materiály dodává {@link Palette} podle rolí; geometrie je čistá
 * a jednotkově testovatelná.
 *
 * <p>Celá stavba je dosažitelná z jednoho stanoviště ve středu (hlídá
 * reach-invariant test), takže ji {@code BuildSession} postaví beze změny
 * jako legacy domky – jen větší a rozmanitější. Větší půdorysy (7×7+) budou
 * chtít víc stanovišť (bot na vlastní stavbě), to je samostatný krok.</p>
 */
public final class HouseGenerator implements Blueprint {

    private final int width;
    private final int wallHeight;
    private final boolean roofPeaked;
    private final BuildTier tier;

    /**
     * Dům s plnou jehlanovou střechou do špičky (stupeň {@link BuildTier#SOLID}).
     *
     * @param width      šířka i hloubka půdorysu (lichá, ≥ 5)
     * @param wallHeight výška zdí (≥ 2)
     */
    public HouseGenerator(int width, int wallHeight) {
        this(width, wallHeight, true, BuildTier.SOLID);
    }

    /**
     * @param width      šířka i hloubka půdorysu (lichá, ≥ 5)
     * @param wallHeight výška zdí (≥ 2)
     * @param roofPeaked plná jehlanová střecha do špičky, nebo valba s plochým
     *                   vrcholem (o stupeň nižší) – variace vzhledu
     */
    public HouseGenerator(int width, int wallHeight, boolean roofPeaked) {
        this(width, wallHeight, roofPeaked, BuildTier.SOLID);
    }

    /**
     * @param width      šířka i hloubka půdorysu (lichá, ≥ 5)
     * @param wallHeight výška zdí (≥ 2)
     * @param roofPeaked plná jehlanová střecha do špičky, nebo valba s plochým
     *                   vrcholem (o stupeň nižší) – variace vzhledu
     * @param tier       stavební stupeň – reprezentativní ({@link BuildTier#REFINED})
     *                   dostane navíc komín; nižší stupně mají prostou geometrii
     */
    public HouseGenerator(int width, int wallHeight, boolean roofPeaked, BuildTier tier) {
        if (width < 5 || width % 2 == 0) {
            throw new IllegalArgumentException("width musí být liché a ≥ 5: " + width);
        }
        if (wallHeight < 2) {
            throw new IllegalArgumentException("wallHeight ≥ 2: " + wallHeight);
        }
        this.width = width;
        this.wallHeight = wallHeight;
        this.roofPeaked = roofPeaked;
        this.tier = tier;
    }

    private int center() {
        return width / 2;
    }

    /** Nejvyšší souřadnice zdi (poslední zděná vrstva). */
    private int wallTop() {
        return wallHeight - 1;
    }

    /** Počet střešních stupňů: plná do špičky, nebo o stupeň nižší (plochý vrchol). */
    private int roofSteps() {
        int full = (width - 1) / 2;
        return roofPeaked ? full : Math.max(1, full - 1);
    }

    @Override
    public List<PlacementCell> cells(BlockPos origin, Cardinal facing) {
        List<PlacementCell> result = new ArrayList<>();
        int c = center();
        // Základová obruba (y=0) a zdi (y=1..wallTop) po obvodu; dveřní otvor
        // (x=c, z=0, y=0..1) zůstává volný.
        for (int y = 0; y <= wallTop(); y++) {
            for (int x = 0; x < width; x++) {
                for (int z = 0; z < width; z++) {
                    if (!isPerimeter(x, z)) {
                        continue;
                    }
                    if (isDoor(x, z, y)) {
                        continue;
                    }
                    result.add(new PlacementCell(local(origin, x, y, z, facing),
                            BlockSpec.of(roleFor(x, z, y))));
                }
            }
        }
        // Valbová střecha: plné stupně od okrajů ke špičce, každý o 1 užší.
        for (int step = 0; step < roofSteps(); step++) {
            int y = wallTop() + 1 + step;
            int lo = step;
            int hi = width - 1 - step;
            for (int x = lo; x <= hi; x++) {
                for (int z = lo; z <= hi; z++) {
                    result.add(new PlacementCell(local(origin, x, y, z, facing),
                            BlockSpec.of(PaletteRole.ROOF)));
                }
            }
        }
        // Reprezentativní dům má komín: sloupec u nároží nad střechu.
        if (tier == BuildTier.REFINED) {
            for (int y = chimneyBase(); y <= chimneyTop(); y++) {
                result.add(new PlacementCell(local(origin, CHIMNEY_X, y, CHIMNEY_Z, facing),
                        BlockSpec.of(PaletteRole.ROOF)));
            }
        }
        return result;
    }

    /** Sloupec komína (u nároží, aby nekolidoval se stoupající valbou). */
    private static final int CHIMNEY_X = 1;
    private static final int CHIMNEY_Z = 1;

    /** Nejvyšší y střešního bloku ve sloupci komína (odtud komín pokračuje výš). */
    private int chimneyBase() {
        int maxStep = Math.min(Math.min(CHIMNEY_X, CHIMNEY_Z),
                Math.min(width - 1 - CHIMNEY_X, width - 1 - CHIMNEY_Z));
        int cover = Math.min(maxStep, roofSteps() - 1);
        return wallTop() + 1 + cover + 1; // první blok nad střechou v tom sloupci
    }

    /** Vrchol komína – dva bloky nad špičkou střechy. */
    private int chimneyTop() {
        return wallTop() + roofSteps() + 2;
    }

    /** Role obvodového bloku: nároží = kmen, okno = sklo, základ = kámen, jinak zeď. */
    private PaletteRole roleFor(int x, int z, int y) {
        if (y == 0) {
            return PaletteRole.FOUNDATION;
        }
        if (isCorner(x, z)) {
            return PaletteRole.WALL_ACCENT;
        }
        if (y == 1 && isWindow(x, z)) {
            return PaletteRole.WINDOW;
        }
        return PaletteRole.WALL;
    }

    @Override
    public List<BlockPos> clearVolume(BlockPos origin, Cardinal facing) {
        List<BlockPos> result = new ArrayList<>();
        int top = wallTop() + roofSteps();
        for (int x = 0; x < width; x++) {
            for (int y = 0; y <= top; y++) {
                for (int z = 0; z < width; z++) {
                    result.add(local(origin, x, y, z, facing));
                }
            }
        }
        // Komín reprezentativního domu vyčnívá nad běžný objem – uvolnit i nad ním.
        if (tier == BuildTier.REFINED) {
            for (int y = top + 1; y <= chimneyTop(); y++) {
                result.add(local(origin, CHIMNEY_X, y, CHIMNEY_Z, facing));
            }
        }
        return result;
    }

    @Override
    public List<BlockPos> groundColumns(BlockPos origin, Cardinal facing) {
        List<BlockPos> result = new ArrayList<>();
        for (int x = 0; x < width; x++) {
            for (int z = 0; z < width; z++) {
                result.add(local(origin, x, -1, z, facing));
            }
        }
        return result;
    }

    @Override
    public List<FurnishCell> furnishing(BlockPos origin, Cardinal facing) {
        List<FurnishCell> result = new ArrayList<>();
        result.add(new FurnishCell(FurnishKind.DOOR, local(origin, center(), 0, 0, facing)));
        result.add(new FurnishCell(FurnishKind.BED, local(origin, 1, 0, 1, facing)));
        // Reprezentativní dům svítí lucernami (místo pochodně, a jednou navíc).
        FurnishKind light = tier == BuildTier.REFINED ? FurnishKind.LANTERN : FurnishKind.TORCH;
        result.add(new FurnishCell(light, local(origin, width - 2, 0, 1, facing)));
        if (tier == BuildTier.REFINED) {
            result.add(new FurnishCell(FurnishKind.LANTERN,
                    local(origin, 1, 0, width - 2, facing)));
        }
        return result;
    }

    @Override
    public BlockPos standPoint(BlockPos origin, Cardinal facing) {
        // Střed je pod rotací invariantní (čtverec) – odtud dosáhne na celou stavbu.
        return origin.offset(center(), 0, center());
    }

    @Override
    public Optional<BlockPos> doorCell(BlockPos origin, Cardinal facing) {
        return Optional.of(local(origin, center(), 0, 0, facing));
    }

    @Override
    public int blocksNeeded() {
        return cells(new BlockPos(0, 0, 0), Cardinal.NORTH).size();
    }

    @Override
    public boolean standExact() {
        return true; // z přesného středu je dosah na střechu nejlepší
    }

    // ----------------------------------------------------------------- geometrie

    private boolean isPerimeter(int x, int z) {
        return x == 0 || x == width - 1 || z == 0 || z == width - 1;
    }

    private boolean isCorner(int x, int z) {
        return (x == 0 || x == width - 1) && (z == 0 || z == width - 1);
    }

    /** Dveřní otvor 1×2 uprostřed čelní hrany (z=0 v základním natočení). */
    private boolean isDoor(int x, int z, int y) {
        return z == 0 && x == center() && y <= 1;
    }

    /** Okno: střed nečelní hrany (čelní hrana má dveře). */
    private boolean isWindow(int x, int z) {
        if (isCorner(x, z)) {
            return false;
        }
        boolean backMid = z == width - 1 && x == center();
        boolean leftMid = x == 0 && z == center();
        boolean rightMid = x == width - 1 && z == center();
        return backMid || leftMid || rightMid;
    }

    /**
     * Rotace lokální souřadnice čtvercového půdorysu podle natočení – stejný
     * princip jako {@link HouseBlueprint}, jen pro obecné {@code width}.
     */
    private BlockPos local(BlockPos origin, int x, int y, int z, Cardinal facing) {
        int wx;
        int wz;
        switch (facing) {
            case NORTH -> {
                wx = x;
                wz = z;
            }
            case SOUTH -> {
                wx = width - 1 - x;
                wz = width - 1 - z;
            }
            case WEST -> {
                wx = z;
                wz = x;
            }
            case EAST -> {
                wx = width - 1 - z;
                wz = x;
            }
            default -> throw new IllegalStateException();
        }
        return origin.offset(wx, y, wz);
    }
}
