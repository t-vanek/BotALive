package dev.botalive.api.bot;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Správce životního cyklu všech botů.
 *
 * <p>Vytváření je asynchronní – zahrnuje načtení/založení identity v databázi
 * a připojení klienta k serveru. Všechny metody jsou thread-safe.</p>
 */
public interface BotManager {

    /**
     * Vytvoří (nebo oživí z databáze) bota a připojí ho k serveru.
     *
     * @param spec specifikace nového bota
     * @return future dokončený, jakmile je bot naspawnovaný, nebo selže s důvodem
     */
    CompletableFuture<Bot> create(BotSpawnSpec spec);

    /**
     * Odpojí bota a odstraní ho ze serveru. Perzistentní data (paměť, osobnost)
     * zůstávají v databázi, pokud {@code purge} není {@code true}.
     *
     * @param botId UUID bota
     * @param purge {@code true} = smazat i data v databázi
     * @return future dokončený po odstranění; {@code false} pokud bot neexistoval
     */
    CompletableFuture<Boolean> remove(UUID botId, boolean purge);

    /**
     * @param botId UUID bota
     * @return bot, pokud existuje
     */
    Optional<Bot> byId(UUID botId);

    /**
     * @param name herní jméno (case-insensitive)
     * @return bot, pokud existuje
     */
    Optional<Bot> byName(String name);

    /**
     * @return všichni registrovaní boti (nemodifikovatelná kolekce)
     */
    Collection<Bot> all();

    /**
     * @return počet právě připojených botů
     */
    int onlineCount();
}
