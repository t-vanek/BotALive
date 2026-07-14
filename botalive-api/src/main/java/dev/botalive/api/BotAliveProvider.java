package dev.botalive.api;

/**
 * Statický držák instance {@link BotAliveApi}.
 *
 * <p>Plnění a čištění instance je výhradně v režii pluginu BotAlive během jeho
 * enable/disable fáze. Cizí pluginy instanci pouze čtou.</p>
 */
public final class BotAliveProvider {

    private static volatile BotAliveApi instance;

    private BotAliveProvider() {
    }

    /**
     * @return aktivní instance API
     * @throws IllegalStateException pokud plugin BotAlive není načtený/zapnutý
     */
    public static BotAliveApi get() {
        BotAliveApi api = instance;
        if (api == null) {
            throw new IllegalStateException("BotAlive není inicializován. Je plugin zapnutý a je v depends?");
        }
        return api;
    }

    /**
     * Interní – registruje API při startu pluginu.
     *
     * @param api instance API, nebo {@code null} při vypnutí pluginu
     */
    public static void register(BotAliveApi api) {
        instance = api;
    }
}
