package dev.botalive.core.testutil;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.BlockTraits;
import dev.botalive.core.world.WorldView;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;

import java.util.HashMap;
import java.util.Map;

/**
 * Syntetický svět pro testy pathfindingu a fyziky.
 *
 * <p>Výchozí stav: rovná pevná podlaha na {@code floorY}, nad ní vzduch.
 * Jednotlivé bloky lze přepsat pomocí {@link #set}.</p>
 */
public final class FakeWorldView implements WorldView {

    /** Pevný blok. */
    public static final BlockTraits SOLID = BlockTraits.simple(false, true, false, false, false, false, false, false, false);

    /** Láva (hazard). */
    public static final BlockTraits HAZARD = BlockTraits.simple(false, false, true, false, false, true, false, false, false);

    /** Voda. */
    public static final BlockTraits WATER = BlockTraits.simple(false, false, true, false, false, false, false, false, false);

    /** Žebřík/liána – průchozí a šplhatelný, bez kolize. */
    public static final BlockTraits CLIMBABLE = BlockTraits.simple(true, false, false, true, false, false, false, false, false);

    /** Měkký dopad – pevný blok tlumící pád (seno, slime). */
    public static final BlockTraits SOFT = BlockTraits.simple(false, true, false, false, false, false, false, true, false);

    /** Prašan (powder snow) – hustý sníh, entity se boří; hazard (mrznutí). */
    public static final BlockTraits POWDER = BlockTraits.simple(false, false, false, false, false, true, false, false, true);

    /** Spodní deska (slab) – pochozí plocha v půlce bloku. */
    public static final BlockTraits SLAB_BOTTOM = SOLID.withBoxes(new double[]{0, 0, 0, 1, 0.5, 1});

    /** Horní deska (slab) – kolize v horní půlce bloku. */
    public static final BlockTraits SLAB_TOP = SOLID.withBoxes(new double[]{0, 0.5, 0, 1, 1, 1});

    /** Plot/zídka – 1,5 bloku vysoká překážka, nedá se přeskočit ani na ni vystoupit. */
    public static final BlockTraits FENCE = SOLID.withBoxes(BlockTraits.TALL_BOXES);

    /** Schody (stoupají na +X): podstava + zábradlí na zadní polovině. */
    public static final BlockTraits STAIR_EAST = SOLID.withBoxes(
            new double[]{0, 0, 0, 1, 0.5, 1, 0.5, 0.5, 0, 1, 1, 1});

    /** Pavučina – bez kolize, ale pathfinding se jí vyhýbá a fyzika v ní vázne. */
    public static final BlockTraits WEB = new BlockTraits(true, false, false, false, false, false,
            false, false, false, 0, 1.0, BlockTraits.DEFAULT_SLIPPERINESS, true, false,
            BlockTraits.NO_BOXES);

    /** Led – kluzký povrch. */
    public static final BlockTraits ICE = new BlockTraits(false, true, false, false, false, false,
            false, false, false, 1.0, 1.0, 0.98, false, false, BlockTraits.FULL_BOXES);

    /** Soul sand – pevný blok se zpomalenou chůzí. */
    public static final BlockTraits SOUL_SAND = new BlockTraits(false, true, false, false, false, false,
            false, false, false, 1.0, 0.4, BlockTraits.DEFAULT_SLIPPERINESS, false, false,
            BlockTraits.FULL_BOXES);

    /** Zavřené dveře – fyzicky blokují, pathfinding prochází přes interakci. */
    public static final BlockTraits DOOR_CLOSED = new BlockTraits(false, false, false, false, true, false,
            true, false, false, 1.0, 1.0, BlockTraits.DEFAULT_SLIPPERINESS, false, false,
            BlockTraits.FULL_BOXES);

    /** Otevřené dveře – průchozí, bez interakce. */
    public static final BlockTraits DOOR_OPEN = new BlockTraits(true, false, false, false, false, false,
            true, false, false, 0, 1.0, BlockTraits.DEFAULT_SLIPPERINESS, false, false,
            BlockTraits.NO_BOXES);

    /** Šest vrstev sněhu – pochozí plocha v 0.625 bloku. */
    public static final BlockTraits SNOW_SIX = SOLID.withBoxes(new double[]{0, 0, 0, 1, 0.625, 1});

    /** Vzduch – pro přepsání dřívějšího override (např. díra ve zdi). */
    public static final BlockTraits AIRLIKE = BlockTraits.AIR;

    private final int floorY;
    private final Map<Long, BlockTraits> overrides = new HashMap<>();
    private dev.botalive.core.world.Dimension dimension = dev.botalive.core.world.Dimension.OVERWORLD;

    /**
     * @param floorY výška horní hrany podlahy (bloky na floorY jsou pevné)
     */
    public FakeWorldView(int floorY) {
        this.floorY = floorY;
    }

    /**
     * Přepíše blok.
     *
     * @param x      blok X
     * @param y      blok Y
     * @param z      blok Z
     * @param traits vlastnosti
     * @return this (řetězení)
     */
    public FakeWorldView set(int x, int y, int z, BlockTraits traits) {
        overrides.put(new BlockPos(x, y, z).asLong(), traits);
        return this;
    }

    /**
     * Postaví pevný sloupec od {@code yFrom} do {@code yTo} včetně.
     */
    public FakeWorldView wall(int x, int yFrom, int yTo, int z) {
        for (int y = yFrom; y <= yTo; y++) {
            set(x, y, z, SOLID);
        }
        return this;
    }

    @Override
    public Material materialAt(BlockPos pos) {
        return traitsAt(pos).solid() ? Material.STONE : Material.AIR;
    }

    @Override
    public BlockData blockDataAt(BlockPos pos) {
        return null;
    }

    @Override
    public BlockTraits traitsAt(BlockPos pos) {
        BlockTraits override = overrides.get(pos.asLong());
        if (override != null) {
            return override;
        }
        return pos.y() <= floorY ? SOLID : BlockTraits.AIR;
    }

    @Override
    public boolean isAvailable(BlockPos pos) {
        return true;
    }

    @Override
    public void prefetch(BlockPos center, int radiusChunks) {
        // syntetický svět je vždy „načtený"
    }

    @Override
    public String worldName() {
        return "fake";
    }

    @Override
    public dev.botalive.core.world.Dimension dimension() {
        return dimension;
    }

    /**
     * Přepne dimenzi syntetického světa (testy End chování).
     *
     * @param dimension dimenze
     * @return this (řetězení)
     */
    public FakeWorldView dimension(dev.botalive.core.world.Dimension dimension) {
        this.dimension = dimension;
        return this;
    }
}
