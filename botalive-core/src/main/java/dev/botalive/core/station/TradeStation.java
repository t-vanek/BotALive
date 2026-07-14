package dev.botalive.core.station;

import dev.botalive.core.ai.BotContext;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Stanice obchodu s vesničany – kontrakt mezi {@code TradeGoal}
 * a implementací (server-side {@code TradeService} / paketová
 * {@code PacketTradeStation}). Cíl vesničana osloví sám (interact paket).
 */
public interface TradeStation {

    /**
     * Výsledek obchodování.
     *
     * @param trades         počet provedených obchodů
     * @param emeraldsGained získané smaragdy (prodej)
     * @param foodBought     nakoupené jídlo (kusy)
     */
    record TradeReport(int trades, int emeraldsGained, int foodBought) {

        /** Prázdný výsledek. */
        public static final TradeReport EMPTY = new TradeReport(0, 0, 0);
    }

    /**
     * Provede až {@code maxTrades} obchodů s vesničanem.
     *
     * @param ctx        kontext bota (stojí vedle vesničana)
     * @param villagerId UUID vesničana
     * @param maxTrades  strop počtu obchodů v jedné návštěvě
     * @return future s výsledkem (EMPTY při nedostupnosti)
     */
    CompletableFuture<TradeReport> trade(BotContext ctx, UUID villagerId, int maxTrades);
}
