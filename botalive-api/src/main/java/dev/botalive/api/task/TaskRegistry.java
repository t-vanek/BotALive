package dev.botalive.api.task;

import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Registr pojmenovaných {@link BotTask} pro cizí pluginy.
 *
 * <p>Umožňuje sdílet znovupoužitelné tasky pod jménem – jeden plugin task
 * zaregistruje, jiný (nebo tentýž) ho vytvoří přes {@link #create(String)}.
 * Task drží stav, proto se registrují <b>továrny</b> (nová instance na použití),
 * ne instance.</p>
 *
 * <p>Parametrizovaná vestavěná primitiva ({@code mineBlock}, {@code placeBlock},
 * {@code walkTo}) najdete přímo na {@link dev.botalive.api.bot.BotControl}.</p>
 */
public interface TaskRegistry {

    /**
     * Zaregistruje továrnu tasku.
     *
     * @param taskId  stabilní id (case-insensitive), musí být unikátní
     * @param factory továrna vytvářející novou instanci tasku
     * @throws IllegalArgumentException pokud je id prázdné nebo již registrované
     */
    void register(String taskId, Supplier<BotTask> factory);

    /**
     * @param taskId id tasku (case-insensitive)
     * @return {@code true} pokud byl registrován
     */
    boolean unregister(String taskId);

    /**
     * Vytvoří novou instanci registrovaného tasku.
     *
     * @param taskId id tasku (case-insensitive)
     * @return nová instance, nebo prázdno pokud id není registrováno
     */
    Optional<BotTask> create(String taskId);

    /**
     * @return id všech registrovaných tasků
     */
    List<String> registeredIds();
}
