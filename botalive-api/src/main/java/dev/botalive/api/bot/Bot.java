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
     * @return profese bota (výchozí {@link dev.botalive.api.role.BotRole#NONE})
     */
    dev.botalive.api.role.BotRole role();

    /**
     * Nastaví profesi bota. Změna se persistuje a projeví se v nejbližším
     * rozhodovacím cyklu AI (role násobí užitečnost souvisejících cílů).
     *
     * @param newRole nová role
     */
    void role(dev.botalive.api.role.BotRole newRole);

    /**
     * @return dlouhodobá paměť bota
     */
    BotMemory memory();

    /**
     * @return peněženka bota (ekonomika)
     */
    BotWallet wallet();

    /**
     * Bezpečné akční rozhraní bota pro cizí AI cíle – navigace, pohled, akce,
     * inventář a vnímání světa bez závislosti na implementaci.
     *
     * <p>Vrací se stabilní instance vázaná na tohoto bota. Metody se volají
     * z tick vlákna bota (typicky z {@link dev.botalive.api.ai.Goal#tick(Bot)}).</p>
     *
     * @return řídicí rozhraní tohoto bota
     */
    BotControl control();

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
     * Teleportuje bota na zadanou lokaci (i mezi světy).
     *
     * <p>Teleport se provede server-side (bot je skutečný hráč) a klient bota
     * se plně resynchronizuje: přeruší se navigace, fyzika převezme novou
     * pozici z position paketu serveru a při změně světa se přepne pohled na
     * svět. Bezpečné z libovolného vlákna.</p>
     *
     * @param location cílová lokace
     * @return future s {@code true} při úspěchu ({@code false} pokud bot
     *         není online nebo teleport selhal)
     */
    java.util.concurrent.CompletableFuture<Boolean> teleport(org.bukkit.Location location);

    /**
     * Teleportuje bota k online hráči („přivolání bota").
     *
     * <p>Pozice hráče se čte na jeho vlákně (Folia-safe) a teleport proběhne
     * přes {@link #teleport(org.bukkit.Location)} včetně plného resyncu
     * klienta bota. Bezpečné z libovolného vlákna.</p>
     *
     * @param playerId UUID cílového hráče
     * @return future s {@code true} při úspěchu ({@code false} pokud hráč
     *         nebo bot nejsou online, nebo teleport selhal)
     */
    java.util.concurrent.CompletableFuture<Boolean> teleportToPlayer(UUID playerId);

    /**
     * Teleportuje online hráče k botovi.
     *
     * <p>Pozice bota se čte na vlákně jeho serverové entity a hráč se
     * teleportuje přes {@code teleportAsync} na svém vlákně (Folia-safe).
     * Bezpečné z libovolného vlákna.</p>
     *
     * @param playerId UUID hráče, který se má přenést
     * @return future s {@code true} při úspěchu ({@code false} pokud hráč
     *         nebo bot nejsou online, nebo teleport selhal)
     */
    java.util.concurrent.CompletableFuture<Boolean> teleportPlayerToBot(UUID playerId);

    /**
     * Odpojí bota od serveru. Bot lze později znovu připojit správcem botů.
     *
     * @param reason lidsky čitelný důvod (do logu)
     */
    void disconnect(String reason);
}
