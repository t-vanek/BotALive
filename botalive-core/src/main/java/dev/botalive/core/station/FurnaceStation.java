package dev.botalive.core.station;

import dev.botalive.core.ai.BotContext;
import dev.botalive.core.util.BlockPos;

import java.util.concurrent.CompletableFuture;

/**
 * Stanice pecí – kontrakt mezi {@code SmeltGoal} a implementací
 * (server-side {@code FurnaceService}).
 */
public interface FurnaceStation {

    /**
     * Výsledek vložení do pece.
     *
     * @param inserted vložené kusy surovin
     * @param fueled   vložené kusy paliva
     */
    record InsertReport(int inserted, int fueled) {

        /** Prázdný výsledek. */
        public static final InsertReport EMPTY = new InsertReport(0, 0);
    }

    /**
     * Vloží suroviny a palivo z inventáře bota do pece.
     *
     * @param ctx       kontext bota (stojí u otevřené pece)
     * @param worldName svět pece
     * @param pos       pozice pece
     * @return future s počty vložených kusů
     */
    CompletableFuture<InsertReport> insert(BotContext ctx, String worldName, BlockPos pos);

    /**
     * Vyzvedne hotové výsledky tavení.
     *
     * @param ctx       kontext bota (stojí u otevřené pece)
     * @param worldName svět pece
     * @param pos       pozice pece
     * @return future s počtem vyzvednutých kusů
     */
    CompletableFuture<Integer> collect(BotContext ctx, String worldName, BlockPos pos);
}
