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

    /**
     * @param width      šířka i hloubka půdorysu (lichá, ≥ 5)
     * @param wallHeight výška zdí (≥ 2)
     */
    public HouseGenerator(int width, int wallHeight) {
        if (width < 5 || width % 2 == 0) {
            throw new IllegalArgumentException("width musí být liché a ≥ 5: " + width);
        }
        if (wallHeight < 2) {
            throw new IllegalArgumentException("wallHeight ≥ 2: " + wallHeight);
        }
        this.width = width;
        this.wallHeight = wallHeight;
    }

    private int center() {
        return width / 2;
    }

    /** Nejvyšší souřadnice zdi (poslední zděná vrstva). */
    private int wallTop() {
        return wallHeight - 1;
    }

    /** Počet střešních stupňů (do špičky). */
    private int roofSteps() {
        return (width - 1) / 2;
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
        return result;
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
        return List.of(
                new FurnishCell(FurnishKind.DOOR, local(origin, center(), 0, 0, facing)),
                new FurnishCell(FurnishKind.BED, local(origin, 1, 0, 1, facing)),
                new FurnishCell(FurnishKind.TORCH, local(origin, width - 2, 0, 1, facing)));
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
