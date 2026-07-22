package dev.botalive.core.ai.goals;

import dev.botalive.core.ai.BotContext;
import dev.botalive.core.tasks.BotTask;
import dev.botalive.core.tasks.MineBlockTask;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.WorldView;

import org.bukkit.Material;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.function.Predicate;

/**
 * Shánění materiálu na opravu bariéry: najde v okolí nejbližší odkrytý zdroj
 * (klády pro plot, kámen pro hradbu) a vytěží ho; drop se sebere přiblížením.
 * Sestra {@link BarrierWorker}/{@link DecorWorker} – jedna chůze, těžba, pauzy.
 *
 * <p>Bez zdroje v dosahu ({@link #RADIUS}) hlásí {@link State#EXHAUSTED} a cíl
 * opravu odloží (zkusí později z jiného místa). Nesahá na globální těžbu –
 * shánění je omezené na okolí a rozpočet.</p>
 */
final class BarrierGather {

    /** Stav ticku shánění. */
    enum State {
        /** Pracuje se (chůze / těžba). */
        WORKING,
        /** V dosahu není žádný zdroj – shánění se vzdává. */
        EXHAUSTED
    }

    /** Poloměr skenu zdrojů (bloky). */
    private static final int RADIUS = 10;
    /** Dojít blízko, ať se vytěžený drop sebere (≈ 2 bloky). */
    private static final int REACH_SQ = 6;
    /** Strop ticků chůze k jednomu zdroji. */
    private static final int NAV_BUDGET = 200;

    private final Predicate<Material> want;
    private final Deque<BlockPos> candidates = new ArrayDeque<>();
    private BotTask current;
    private BlockPos target;
    private int navTicks;

    /**
     * @param want predikát zdrojového materiálu (klády / kámen)
     */
    BarrierGather(Predicate<Material> want) {
        this.want = want;
    }

    /**
     * Jeden tick shánění.
     *
     * @param ctx kontext bota
     * @return {@link State#WORKING} dokud shání, {@link State#EXHAUSTED} když v
     *         okolí nic není
     */
    State tick(BotContext ctx) {
        if (current != null) {
            if (current.tick(ctx)) {
                current = null;
                target = null;
            }
            return State.WORKING;
        }
        WorldView world = ctx.worldView();
        if (world == null) {
            return State.EXHAUSTED;
        }
        if (target == null) {
            if (candidates.isEmpty()) {
                candidates.addAll(scanSources(world, ctx.position().toBlockPos(), want, RADIUS));
            }
            target = candidates.poll();
            if (target == null) {
                return State.EXHAUSTED; // v dosahu nic
            }
            navTicks = 0;
        }
        if (ctx.position().toBlockPos().distanceSquared(target) > REACH_SQ) {
            ctx.navigator().navigateTo(ctx.position(), target);
            if (!ctx.navigator().navigating() || ++navTicks > NAV_BUDGET) {
                target = null; // nedostupný zdroj – zkus další kandidát
            }
            return State.WORKING;
        }
        ctx.navigator().stop();
        current = new MineBlockTask(target);
        return State.WORKING;
    }

    /** Zruší rozdělané shánění (přerušení cíle). */
    void cancel(BotContext ctx) {
        if (current != null) {
            current.cancel(ctx);
            current = null;
        }
        candidates.clear();
        ctx.navigator().stop();
    }

    /**
     * Nejbližší odkryté zdroje vyhovující predikátu v okolí (čistá funkce –
     * testovatelné). „Odkrytý" = má aspoň jednoho průchozího souseda (dá se k
     * němu dostat), aby se nekopalo do plné skály.
     *
     * @param world  pohled na svět
     * @param center střed hledání
     * @param want   predikát materiálu
     * @param radius poloměr (bloky; ±4 na výšku)
     * @return zdroje seřazené od nejbližšího (může být prázdné)
     */
    static List<BlockPos> scanSources(WorldView world, BlockPos center,
                                      Predicate<Material> want, int radius) {
        List<BlockPos> found = new ArrayList<>();
        if (world == null) {
            return found;
        }
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    Material material = world.materialAt(pos);
                    if (material != null && want.test(material) && exposed(world, pos)) {
                        found.add(pos);
                    }
                }
            }
        }
        found.sort(java.util.Comparator.comparingDouble(p -> p.distanceSquared(center)));
        return found;
    }

    /** Má blok aspoň jednoho průchozího souseda (dá se k němu dostat/vytěžit)? */
    private static boolean exposed(WorldView world, BlockPos p) {
        return world.traitsAt(p.up()).passable() || world.traitsAt(p.down()).passable()
                || world.traitsAt(p.offset(1, 0, 0)).passable()
                || world.traitsAt(p.offset(-1, 0, 0)).passable()
                || world.traitsAt(p.offset(0, 0, 1)).passable()
                || world.traitsAt(p.offset(0, 0, -1)).passable();
    }
}
