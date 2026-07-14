package dev.botalive.api.bot;

import dev.botalive.api.economy.BotWallet;
import dev.botalive.api.memory.BotMemory;
import dev.botalive.api.personality.Personality;

import java.util.UUID;

/**
 * Jeden autonomní AI hráč.
 *
 * <p>Bot je skutečný Minecraft klient připojený k serveru přes síťový protokol –
 * není to NPC. Má vlastní identitu, osobnost, paměť, inventář a rozhodování.
 * Všechny metody tohoto rozhraní jsou thread-safe.</p>
 */
public interface Bot {

    /**
     * @return UUID bota (stabilní napříč restarty, odvozené offline-mode schématem ze jména)
     */
    UUID id();

    /**
     * @return herní jméno bota
     */
    String name();

    /**
     * @return aktuální fáze životního cyklu
     */
    BotLifecycleState state();

    /**
     * @return nemutabilní momentka stavu (pozice, zdraví, cíl, ...)
     */
    BotSnapshot snapshot();

    /**
     * @return osobnost bota (nemutabilní)
     */
    Personality personality();

    /**
     * @return dlouhodobá paměť bota
     */
    BotMemory memory();

    /**
     * @return peněženka bota (ekonomika)
     */
    BotWallet wallet();

    /**
     * Pozastaví AI bota – bot zůstane připojený, ale nehýbe se a nerozhoduje.
     */
    void pause();

    /**
     * Obnoví AI bota po {@link #pause()}.
     */
    void resume();

    /**
     * @return {@code true} pokud je AI pozastavena
     */
    boolean paused();

    /**
     * Vynutí konkrétní AI cíl (např. z příkazu {@code /botalive goal}).
     * Vynucený cíl má přednost, dokud sám neskončí nebo není zrušen.
     *
     * @param goalId id cíle, nebo {@code null} pro zrušení vynucení
     * @return {@code true} pokud cíl existuje a byl vynucen
     */
    boolean forceGoal(String goalId);

    /**
     * Nechá bota promluvit do chatu (prochází humanizací – zpoždění, překlepy).
     *
     * @param message text zprávy
     */
    void say(String message);

    /**
     * Odpojí bota od serveru. Bot lze později znovu připojit správcem botů.
     *
     * @param reason lidsky čitelný důvod (do logu)
     */
    void disconnect(String reason);
}
