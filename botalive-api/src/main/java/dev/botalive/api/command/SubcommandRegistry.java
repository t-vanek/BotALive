package dev.botalive.api.command;

import java.util.List;

/**
 * Registr podpříkazů {@code /botalive} pro cizí pluginy.
 *
 * <p>Umožňuje přidat vlastní podpříkazy (a jejich tab-complete) bez zásahu do
 * jádra. Vestavěné podpříkazy mají přednost a jejich jména jsou vyhrazená.
 * Plugin by se měl při vypnutí ({@code onDisable}) odregistrovat přes
 * {@link #unregister(String)}.</p>
 */
public interface SubcommandRegistry {

    /**
     * Zaregistruje podpříkaz.
     *
     * @param subcommand podpříkaz
     * @throws IllegalArgumentException pokud je jméno prázdné, vyhrazené
     *         vestavěnému podpříkazu, nebo už registrované
     */
    void register(BotSubcommand subcommand);

    /**
     * Odregistruje podpříkaz podle jména.
     *
     * @param name jméno podpříkazu (case-insensitive)
     * @return {@code true} pokud byl registrován
     */
    boolean unregister(String name);

    /**
     * @return jména všech registrovaných (cizích) podpříkazů
     */
    List<String> registeredNames();
}
