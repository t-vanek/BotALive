package dev.botalive.core.nether;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Cardinal;
import dev.botalive.core.world.BlockTraits;
import dev.botalive.core.world.WorldView;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

/**
 * Geometrie nether portálu – čistá třída po vzoru {@code HouseBlueprint}.
 *
 * <p>Bot staví <b>plný rám 4×5 včetně rohů (14 obsidiánu)</b>: bez rohů by
 * horní řada neměla při pokládání žádného pevného souseda a musely by se
 * stavět dočasné opěrné bloky. S rohy má každý blok v pořadí
 * {@link #framePlacements} oporu v už položeném sousedovi nebo v zemi.</p>
 *
 * <p>Souřadný systém: {@code base} je <b>vnitřní spodní roh</b> – buňka, kde
 * bot stojí nohama, když do portálu vstoupí (u osy X ta se západnější,
 * u osy Z ta severnější). Rám se staví „na zem": spodní řada obsidiánu leží
 * na povrchu, {@code base.y()} je tedy o 2 výš než horní hrana terénu.
 * Vnitřek je 2×3 ({@code base} + osa, výška 3).</p>
 */
public final class PortalBlueprint {

    /** Počet obsidiánu na plný rám 4×5 (s rohy). */
    public static final int OBSIDIAN_NEEDED = 14;

    /** Šířka vnitřku podél osy. */
    public static final int INNER_WIDTH = 2;

    /** Výška vnitřku. */
    public static final int INNER_HEIGHT = 3;

    private PortalBlueprint() {
    }

    /**
     * Bloky rámu v pořadí stavby: spodní řada (opora = zem), sloupky zdola
     * nahoru (opora = blok pod nimi), horní řada od krajů ke středu
     * (opora = vršek sloupku, pak soused ve vlastní řadě).
     *
     * @param base  vnitřní spodní roh portálu
     * @param axisX {@code true} = vnitřek se táhne po ose X, jinak po ose Z
     * @return pořadí pokládky 14 bloků rámu
     */
    public static List<BlockPos> framePlacements(BlockPos base, boolean axisX) {
        List<BlockPos> order = new ArrayList<>(OBSIDIAN_NEEDED);
        // Spodní řada: -1..2 podél osy, o blok níž.
        for (int a = -1; a <= 2; a++) {
            order.add(along(base, axisX, a, -1));
        }
        // Sloupky po stranách, zdola nahoru.
        for (int y = 0; y < INNER_HEIGHT; y++) {
            order.add(along(base, axisX, -1, y));
        }
        for (int y = 0; y < INNER_HEIGHT; y++) {
            order.add(along(base, axisX, 2, y));
        }
        // Horní řada: rohy (sedí na sloupcích), pak střed (opora v sousedech).
        order.add(along(base, axisX, -1, INNER_HEIGHT));
        order.add(along(base, axisX, 2, INNER_HEIGHT));
        order.add(along(base, axisX, 0, INNER_HEIGHT));
        order.add(along(base, axisX, 1, INNER_HEIGHT));
        return order;
    }

    /**
     * @param base  vnitřní spodní roh portálu
     * @param axisX orientace vnitřku
     * @return 6 vnitřních buněk (2×3), kde po zapálení vzniknou portálové bloky
     */
    public static List<BlockPos> interior(BlockPos base, boolean axisX) {
        List<BlockPos> cells = new ArrayList<>(INNER_WIDTH * INNER_HEIGHT);
        for (int a = 0; a < INNER_WIDTH; a++) {
            for (int y = 0; y < INNER_HEIGHT; y++) {
                cells.add(along(base, axisX, a, y));
            }
        }
        return cells;
    }

    /**
     * Blok spodní řady, na jehož horní plochu se křesadlem zapaluje – oheň
     * vznikne ve vnitřku a rám se aktivuje.
     *
     * @param base vnitřní spodní roh portálu
     * @return spodní obsidián pod buňkou {@code base}
     */
    public static BlockPos igniteSupport(BlockPos base) {
        return base.down();
    }

    /**
     * Místo, kde bot při stavbě a zapalování stojí: na zemi před rámem,
     * uprostřed šířky, o blok stranou kolmo na osu.
     *
     * @param base  vnitřní spodní roh portálu
     * @param axisX orientace vnitřku
     * @return pozice nohou stavitele
     */
    public static BlockPos standPoint(BlockPos base, boolean axisX) {
        // Nohy stavitele jsou na úrovni spodní řady rámu (rám stojí na zemi).
        return axisX
                ? new BlockPos(base.x(), base.y() - 1, base.z() + 1)
                : new BlockPos(base.x() + 1, base.y() - 1, base.z());
    }

    /**
     * Vstupní buňka portálu pro průchod: {@code base} (spodní vnitřní roh).
     *
     * @param base vnitřní spodní roh portálu
     * @return buňka, do které bot vejde nohama
     */
    public static BlockPos entryCell(BlockPos base) {
        return base;
    }

    /**
     * Je na dané pozici kompletní obsidiánový rám (14 bloků, s rohy i bez –
     * rohy se nekontrolují, vanilla je nevyžaduje)?
     *
     * @param world pohled na svět
     * @param base  kandidát vnitřního spodního rohu
     * @param axisX orientace vnitřku
     * @return {@code true} pokud spodní/horní řada a oba sloupky jsou obsidián
     */
    public static boolean isFrame(WorldView world, BlockPos base, boolean axisX) {
        for (int a = 0; a < INNER_WIDTH; a++) {
            if (world.materialAt(along(base, axisX, a, -1)) != Material.OBSIDIAN
                    || world.materialAt(along(base, axisX, a, INNER_HEIGHT)) != Material.OBSIDIAN) {
                return false;
            }
        }
        for (int y = 0; y < INNER_HEIGHT; y++) {
            if (world.materialAt(along(base, axisX, -1, y)) != Material.OBSIDIAN
                    || world.materialAt(along(base, axisX, 2, y)) != Material.OBSIDIAN) {
                return false;
            }
        }
        return true;
    }

    /**
     * @param world pohled na svět
     * @param base  vnitřní spodní roh
     * @param axisX orientace vnitřku
     * @return {@code true} pokud je vnitřek zaplněný portálovými bloky (zapálený)
     */
    public static boolean isLit(WorldView world, BlockPos base, boolean axisX) {
        for (BlockPos cell : interior(base, axisX)) {
            if (world.materialAt(cell) != Material.NETHER_PORTAL) {
                return false;
            }
        }
        return true;
    }

    /**
     * @param world pohled na svět
     * @param base  vnitřní spodní roh
     * @param axisX orientace vnitřku
     * @return {@code true} pokud je vnitřek průchozí (vzduch/portál) – nic
     *         nepřekáží zapálení
     */
    public static boolean interiorClear(WorldView world, BlockPos base, boolean axisX) {
        for (BlockPos cell : interior(base, axisX)) {
            Material material = world.materialAt(cell);
            if (material != Material.NETHER_PORTAL && material != Material.FIRE
                    && !world.traitsAt(cell).passable()) {
                return false;
            }
        }
        return true;
    }

    /**
     * Najde místo pro stavbu portálu poblíž dané pozice: rovný pruh pevné
     * země pod celým rámem a volný prostor pro rám i stavitele.
     *
     * @param world  pohled na svět
     * @param around střed hledání (typicky pozice bota)
     * @param radius poloměr hledání (bloky)
     * @return vnitřní spodní roh vhodného místa, nebo {@code null}
     */
    public static BlockPos findBuildSite(WorldView world, BlockPos around, int radius) {
        for (int r = 2; r <= radius; r += 2) {
            for (Cardinal dir : Cardinal.values()) {
                BlockPos ground = surfaceAt(world, around.offset(dir.dx() * r, 0, dir.dz() * r));
                if (ground == null) {
                    continue;
                }
                // base = 2 nad povrchovým blokem (spodní řada rámu leží na zemi).
                BlockPos base = ground.offset(0, 2, 0);
                if (siteUsable(world, base, true)) {
                    return base;
                }
                if (siteUsable(world, base, false)) {
                    return base;
                }
            }
        }
        return null;
    }

    /**
     * Ověří stavební místo: pevná bezpečná zem pod všemi čtyřmi sloupci
     * spodní řady i pod stavitelem a volný (průchozí) prostor 4×5 pro rám.
     *
     * @param world pohled na svět
     * @param base  kandidát vnitřního spodního rohu (2 nad zemí)
     * @param axisX orientace vnitřku
     * @return {@code true} pokud se tu dá rám postavit
     */
    public static boolean siteUsable(WorldView world, BlockPos base, boolean axisX) {
        // Zem pod spodní řadou (a nic nebezpečného v ní).
        for (int a = -1; a <= 2; a++) {
            BlockTraits ground = world.traitsAt(along(base, axisX, a, -2));
            if (!ground.solid() || ground.hazard()) {
                return false;
            }
        }
        // Celá plocha rámu 4×5 musí být průchozí (vzduch, tráva…) – žádná
        // láva, voda, stěna ani nenačtený chunk.
        for (int a = -1; a <= 2; a++) {
            for (int y = -1; y <= INNER_HEIGHT; y++) {
                BlockTraits cell = world.traitsAt(along(base, axisX, a, y));
                if (!cell.passable() || cell.liquid() || cell.hazard()) {
                    return false;
                }
            }
        }
        // Stavitel potřebuje pevnou zem a dva bloky vzduchu.
        BlockPos stand = standPoint(base, axisX);
        return world.traitsAt(stand.down()).solid()
                && world.traitsAt(stand).passable()
                && world.traitsAt(stand.up()).passable();
    }

    /**
     * Pomocná osa: převod (podél, výška) → světové souřadnice.
     *
     * @param base  vnitřní spodní roh
     * @param axisX orientace vnitřku
     * @param a     posun podél osy portálu (-1 až 2)
     * @param y     posun na výšku (-1 spodní řada, 3 horní řada)
     * @return světová pozice
     */
    static BlockPos along(BlockPos base, boolean axisX, int a, int y) {
        return axisX
                ? base.offset(a, y, 0)
                : base.offset(0, y, a);
    }

    /** Najde povrch: pevný blok se dvěma bloky vzduchu nad sebou. */
    private static BlockPos surfaceAt(WorldView world, BlockPos start) {
        for (int dy = 4; dy >= -4; dy--) {
            BlockPos ground = start.offset(0, dy, 0);
            if (world.traitsAt(ground).solid()
                    && world.traitsAt(ground.up()).passable()
                    && world.traitsAt(ground.offset(0, 2, 0)).passable()) {
                return ground;
            }
        }
        return null;
    }
}
