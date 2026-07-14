package dev.botalive.core.station;

import dev.botalive.core.ai.BotContext;

import java.util.concurrent.CompletableFuture;

/**
 * Stanice enchantování – kontrakt mezi {@code EnchantGoal} a implementací
 * (server-side {@code EnchantService} / paketová {@code PacketEnchantStation}).
 */
public interface EnchantStation {

    /**
     * Výsledek enchantování.
     *
     * @param enchanted   název očarovaného předmětu, nebo {@code null}
     * @param levelsSpent utracené XP levely
     */
    record EnchantReport(String enchanted, int levelsSpent) {

        /** Nic se nepovedlo. */
        public static final EnchantReport EMPTY = new EnchantReport(null, 0);
    }

    /**
     * Očaruje vhodný kus výbavy v inventáři bota.
     *
     * @param ctx kontext bota (u otevřeného enchantovacího stolu)
     * @return future s výsledkem (EMPTY pokud není co/za co očarovat)
     */
    CompletableFuture<EnchantReport> enchantBest(BotContext ctx);
}
