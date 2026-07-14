package dev.botalive.api.ai;

import dev.botalive.api.bot.Bot;

import java.util.List;
import java.util.function.Function;

/**
 * Registr továren AI cílů.
 *
 * <p>Každý bot dostává vlastní instance cílů (cíle si drží stav), proto se
 * registrují továrny, ne instance. Cizí pluginy mohou přidávat vlastní cíle –
 * mozek bota je automaticky zahrne do rozhodování.</p>
 */
public interface GoalRegistry {

    /**
     * Zaregistruje továrnu cíle.
     *
     * @param goalId  stabilní id cíle; musí být unikátní
     * @param factory továrna vytvářející instanci cíle pro konkrétního bota
     * @throws IllegalArgumentException pokud je id již registrováno
     */
    void register(String goalId, Function<Bot, Goal> factory);

    /**
     * Odregistruje cíl (nové instance nevzniknou; běžící se doběhnou).
     *
     * @param goalId id cíle
     * @return {@code true} pokud byl registrován
     */
    boolean unregister(String goalId);

    /**
     * @return id všech registrovaných cílů
     */
    List<String> registeredIds();

    /**
     * Vytvoří sadu instancí všech registrovaných cílů pro daného bota.
     *
     * @param bot bot, pro kterého se instance vytvářejí
     * @return nové instance cílů
     */
    List<Goal> instantiateAll(Bot bot);
}
