package dev.botalive.core.ai.goals;

import dev.botalive.core.ai.BotContext;
import dev.botalive.core.build.Enclosure;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Cardinal;

import java.util.Set;

/**
 * Throttlovaná cache ohodnocení bariéry pro utility cíle. {@code utility(...)}
 * běží každých pár ticků a plný sken obvodu ({@link Enclosure#assess}) by byl
 * marnotratný – proto se přepočítá nejvýš jednou za {@link #RECHECK_TICKS} a
 * jen když ho cíl zavolá (tj. když je bot blízko a geometrie je známá). Jinak
 * vrátí poslední známý stav (levné každý tick).
 */
final class RepairAssessor {

    /** Jak často nejvýš přeskenovat obvod (ticky; ~5 s při 20 tps). */
    private static final int RECHECK_TICKS = 100;

    private Enclosure.Assessment cached = Enclosure.Assessment.EMPTY;
    private int cooldownTicks;

    /**
     * Vrátí (throttlovaně přepočítané) ohodnocení obvodu bariéry.
     *
     * @param ctx   kontext bota
     * @param min   roh oblasti (min XZ)
     * @param max   roh oblasti (max XZ)
     * @param yHint výška staveniště
     * @param gates strany s brankou
     * @return poslední ohodnocení (čerstvé po uplynutí RECHECK)
     */
    Enclosure.Assessment assess(BotContext ctx, BlockPos min, BlockPos max, int yHint,
                                Set<Cardinal> gates) {
        cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
        if (cooldownTicks <= 0 && ctx.worldView() != null) {
            cached = Enclosure.assess(ctx.worldView(), min, max, yHint, gates);
            cooldownTicks = RECHECK_TICKS;
        }
        return cached;
    }
}
