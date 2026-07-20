package dev.botalive.core.nether;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.BlockTraits;
import dev.botalive.core.world.WorldView;

import java.util.List;

/**
 * Oltář witheru – geometrie „T" ze 4 soul sandů a 3 lebek wither skeletonů.
 *
 * <p>Stejná filozofie jako {@link PortalBlueprint}: čistá geometrie
 * s pořadím pokládky, ve kterém má každý blok oporu (pata na zemi, tělo na
 * patě, ramena o tělo), a s pravidlem, že <b>prostřední lebka jde poslední</b>
 * – dokončení hlavy withera probouzí (11 s růstu s nezranitelností je okno
 * na ústup stavitele). Stanoviště a staveniště se hledají s odstupem od
 * všeho, co nemá vybuchnout.</p>
 */
public final class WitherAltar {

    /** Kolik soul sandu oltář spotřebuje. */
    public static final int SOUL_SAND_NEEDED = 4;

    /** Kolik lebek wither skeletonů oltář spotřebuje. */
    public static final int SKULLS_NEEDED = 3;

    /** Světlá výška nad staveništěm (wither při růstu povyroste). */
    private static final int CLEARANCE = 5;

    private WitherAltar() {
    }

    /**
     * Pozice soul sandu v pořadí pokládky – každý blok má oporu v zemi
     * nebo v už položeném sousedovi (invariant hlídá test).
     *
     * @param base  pata oltáře (spodní blok „T", stojí na zemi)
     * @param axisX ramena podél osy X ({@code false} = podél Z)
     * @return pořadí pokládky soul sandu
     */
    public static List<BlockPos> sandPlacements(BlockPos base, boolean axisX) {
        int dx = axisX ? 1 : 0;
        int dz = axisX ? 0 : 1;
        BlockPos body = base.up();
        return List.of(
                base,                          // pata (na zemi)
                body,                          // tělo (na patě)
                body.offset(dx, 0, dz),        // rameno (o tělo)
                body.offset(-dx, 0, -dz));     // druhé rameno
    }

    /**
     * Bloky, na jejichž horní plochu přijdou lebky – <b>prostřední (tělo)
     * poslední</b>: jeho lebka hlavu dokončí a withera probudí.
     *
     * @param base  pata oltáře
     * @param axisX orientace ramen
     * @return pořadí pokládky lebek (kliká se na horní plochu)
     */
    public static List<BlockPos> skullSupports(BlockPos base, boolean axisX) {
        int dx = axisX ? 1 : 0;
        int dz = axisX ? 0 : 1;
        BlockPos body = base.up();
        return List.of(
                body.offset(dx, 0, dz),
                body.offset(-dx, 0, -dz),
                body);                         // střed poslední = spawn!
    }

    /**
     * Stanoviště stavitele: dva bloky od paty kolmo na ramena – na dosah
     * horní plochy těla (poslední lebka), ale mimo rostoucí „T".
     *
     * @param base  pata oltáře
     * @param axisX orientace ramen
     * @return pozice nohou stavitele
     */
    public static BlockPos standPoint(BlockPos base, boolean axisX) {
        return axisX
                ? new BlockPos(base.x(), base.y(), base.z() + 2)
                : new BlockPos(base.x() + 2, base.y(), base.z());
    }

    /**
     * Najde staveniště oltáře: rovná pevná zem 3×3, volný prostor do výšky
     * {@link #CLEARANCE} a žádná tekutina v okolí. Hledá se po prstencích
     * od {@code around} – stejné schéma jako {@link PortalBlueprint}.
     *
     * @param world  pohled na svět
     * @param around střed hledání
     * @param radius maximální poloměr
     * @return pata oltáře (buňka na zemi), nebo {@code null}
     */
    public static BlockPos findBuildSite(WorldView world, BlockPos around, int radius) {
        for (int r = 4; r <= radius; r += 3) {
            for (int dir = 0; dir < 8; dir++) {
                double angle = dir * Math.PI / 4;
                BlockPos probe = around.offset((int) Math.round(Math.cos(angle) * r), 0,
                        (int) Math.round(Math.sin(angle) * r));
                BlockPos ground = surfaceAt(world, probe);
                if (ground == null) {
                    continue;
                }
                BlockPos base = ground.up();
                if (siteUsable(world, base)) {
                    return base;
                }
            }
        }
        return null;
    }

    /**
     * Ověří staveniště: pevná bezpečná zem 3×3 pod oltářem a stavitelem,
     * volný prostor 3×3×{@value #CLEARANCE} nad ní.
     *
     * @param world pohled na svět
     * @param base  kandidát paty oltáře (buňka na zemi)
     * @return {@code true} pokud se tu dá oltář postavit
     */
    public static boolean siteUsable(WorldView world, BlockPos base) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                BlockTraits ground = world.traitsAt(base.offset(dx, -1, dz));
                if (!ground.solid() || ground.hazard()) {
                    return false;
                }
                for (int y = 0; y < CLEARANCE; y++) {
                    BlockTraits cell = world.traitsAt(base.offset(dx, y, dz));
                    if (!cell.passable() || cell.liquid() || cell.hazard()) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Nejbližší pevný povrch pod/nad sondou (±6 bloků). */
    private static BlockPos surfaceAt(WorldView world, BlockPos probe) {
        for (int dy = 4; dy >= -6; dy--) {
            BlockPos pos = probe.offset(0, dy, 0);
            BlockTraits at = world.traitsAt(pos);
            BlockTraits above = world.traitsAt(pos.up());
            if (at.solid() && !at.hazard() && above.passable()) {
                return pos;
            }
        }
        return null;
    }
}
