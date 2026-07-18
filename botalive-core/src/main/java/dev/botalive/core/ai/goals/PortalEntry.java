package dev.botalive.core.ai.goals;

import dev.botalive.core.ai.BotContext;
import dev.botalive.core.physics.MoveInput;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.WorldView;
import org.bukkit.Material;

/**
 * Vstup do end portálu – mechanika sdílená výpravou ({@link EndTravelGoal})
 * a návratem ({@link EndReturnGoal}).
 *
 * <p>A* na portálový blok záměrně nevede (nemá podlahu), takže vstup má dvě
 * části: navigace na <b>nástupní bod</b> (rám portálu s částečnou podlahou,
 * u zapuštěných portálů obyčejný okraj) a odtud jeden vědomý krok dovnitř.
 * Naslepo se k portálu nepochoduje – přístup přes celou síň řeší pathfinding
 * (vyhne se lávovému příkopu strongholdu) a poslední krok má vlastní
 * kontrolu lávy před nosem. Průchod se zvenčí pozná změnou dimenze.</p>
 */
final class PortalEntry {

    /** Kolik ticků smí vstup trvat, než se vzdá (míření, docházka na rám). */
    static final int DEFAULT_BUDGET_TICKS = 300;

    /** Výsledek jednoho ticku vstupu. */
    enum Result { WALKING, GAVE_UP }

    private final BlockPos portal;
    private final BlockPos standPoint;
    private int ticks;

    /**
     * @param world  svět bota
     * @param portal blok END_PORTAL, do kterého se vstupuje
     */
    PortalEntry(WorldView world, BlockPos portal) {
        this.portal = portal;
        this.standPoint = findStandPoint(world, portal);
    }

    /**
     * Jeden tick vstupu do portálu.
     *
     * @param ctx         kontext bota
     * @param budgetTicks časový rozpočet celého vstupu
     * @return {@link Result#WALKING} dokud má smysl pokračovat
     */
    Result tick(BotContext ctx, int budgetTicks) {
        if (++ticks > budgetTicks) {
            return Result.GAVE_UP;
        }
        Vec3 pos = ctx.position();
        Vec3 target = portal.center();
        if (target.sub(pos).horizontalLength() > 1.6) {
            // Daleko od portálu: jedině navigací na nástupní bod – žádný
            // slepý pochod (stronghold má mezi vchodem a schody lávu).
            if (standPoint == null) {
                return Result.GAVE_UP;
            }
            if (standPoint.center().sub(pos).horizontalLength() > 1.1) {
                ctx.navigator().navigateTo(pos, standPoint);
                return Result.WALKING;
            }
        }
        // Poslední krok ze sousedství dovnitř. EdgeGuard by ho vetoval
        // (portálový blok nemá podlahu) – je to vědomý krok; láva před
        // nosem ho ale zastaví i tady.
        ctx.navigator().stop();
        Vec3 direction = target.sub(pos).horizontal();
        if (direction.horizontalLength() > 1.0E-3) {
            BlockPos ahead = pos.add(direction.normalized().mul(0.9)).toBlockPos();
            if (ctx.worldView().traitsAt(ahead).hazard()) {
                return Result.GAVE_UP;
            }
        }
        ctx.humanizer().lookAt(pos.add(0, 1.62, 0), target);
        if (direction.horizontalLength() > 1.0E-3) {
            ctx.requestMove(MoveInput.walk(direction));
        }
        return Result.WALKING;
    }

    /**
     * Nástupní bod u portálu: sousední buňka, na které se dá stát – typicky
     * rám portálu (částečná podlaha 13/16), u portálů v podlaze obyčejný
     * okraj. Z ní se do portálu vchází jedním krokem.
     */
    static BlockPos findStandPoint(WorldView world, BlockPos portal) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int[] d : dirs) {
            BlockPos cell = portal.offset(d[0], 0, d[1]);
            var traits = world.traitsAt(cell);
            // Rám portálu: buňka s částečnou podlahou, nad ní volno.
            if (traits.floorHeight() > 0 && traits.floorHeight() <= 1.01
                    && world.traitsAt(cell.up()).passable()) {
                return cell;
            }
            // Portál zapuštěný v podlaze: volná buňka s pevným podkladem.
            if (traits.passable() && world.traitsAt(cell.down()).solid()) {
                return cell;
            }
        }
        return null;
    }

    /**
     * Najde blok výstupního portálu v okolí (fontána uprostřed ostrova).
     * Existuje jen po smrti draka – slouží i jako důkaz vítězství.
     *
     * @param world  svět bota
     * @param around střed hledání (typicky (0, y bota, 0))
     * @return blok END_PORTAL, nebo {@code null}
     */
    static BlockPos findExitPortal(WorldView world, BlockPos around) {
        for (int dx = -8; dx <= 8; dx++) {
            for (int dy = -6; dy <= 6; dy++) {
                for (int dz = -8; dz <= 8; dz++) {
                    BlockPos p = around.offset(dx, dy, dz);
                    if (world.materialAt(p) == Material.END_PORTAL) {
                        return p;
                    }
                }
            }
        }
        return null;
    }
}
