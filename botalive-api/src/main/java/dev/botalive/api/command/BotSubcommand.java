package dev.botalive.api.command;

import org.bukkit.command.CommandSender;

import java.util.List;

/**
 * Podpříkaz {@code /botalive} dodaný cizím pluginem.
 *
 * <p>Registruje se přes {@link SubcommandRegistry}. Kořenový příkaz nejdřív
 * zkouší vestavěné podpříkazy; neznámé jméno pak hledá v registru a předá mu
 * řízení. Argumenty, které handler dostane, <b>nezahrnují</b> jméno podpříkazu:
 * {@code /botalive greet Alice} vyvolá {@code execute(sender, ["Alice"])}.</p>
 *
 * <p><b>Vlákno.</b> Volá se na hlavním (herním) vlákně jako každý Bukkit
 * příkaz – z handleru se smí sahat na Bukkit API. Dlouhé operace delegujte
 * asynchronně.</p>
 */
public interface BotSubcommand {

    /**
     * @return jméno podpříkazu (bez mezer, case-insensitive) – např.
     *         {@code "greet"} pro {@code /botalive greet}. Nesmí kolidovat
     *         s vestavěným podpříkazem.
     */
    String name();

    /**
     * @return Bukkit oprávnění nutné ke spuštění, nebo {@code null} pokud
     *         podpříkaz nevyžaduje žádné zvláštní oprávnění (dostupný každému,
     *         kdo smí {@code /botalive}). Výchozí {@code null}.
     */
    default String permission() {
        return null;
    }

    /**
     * @return krátký popis do nápovědy ({@code /botalive}). Výchozí prázdný.
     */
    default String description() {
        return "";
    }

    /**
     * Vykoná podpříkaz.
     *
     * @param sender kdo příkaz zadal
     * @param args   argumenty za jménem podpříkazu (může být prázdné pole)
     */
    void execute(CommandSender sender, String[] args);

    /**
     * Doplní návrhy pro tab-complete.
     *
     * @param sender kdo doplňuje
     * @param args   argumenty za jménem podpříkazu; poslední prvek je právě
     *               psaný token
     * @return návrhy (může být prázdné). Výchozí prázdný seznam.
     */
    default List<String> tabComplete(CommandSender sender, String[] args) {
        return List.of();
    }
}
