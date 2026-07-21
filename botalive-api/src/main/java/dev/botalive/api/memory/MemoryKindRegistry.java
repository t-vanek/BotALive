package dev.botalive.api.memory;

import java.util.Collection;
import java.util.Optional;

/**
 * Registr kategorií vzpomínek pro cizí pluginy.
 *
 * <p>Vestavěné kategorie ({@link MemoryKind}) jsou dané; cizí plugin může přidat
 * vlastní přes {@link #register(MemoryKindDefinition)} a získat tak vlastní druh
 * vzpomínek s časovým rozpadem. Ukládat a číst vzpomínky vlastní kategorie lze
 * i bez registrace (přes řetězcové varianty {@link BotMemory}); registrace jen
 * doplní chování navíc (rozpad, podlaha).</p>
 */
public interface MemoryKindRegistry {

    /**
     * Zaregistruje kategorii vzpomínek.
     *
     * @param definition definice kategorie
     * @throws IllegalArgumentException pokud id koliduje s vestavěným
     *         {@link MemoryKind} nebo je již registrováno
     */
    void register(MemoryKindDefinition definition);

    /**
     * @param id id kategorie
     * @return {@code true} pokud byla registrována
     */
    boolean unregister(String id);

    /**
     * @param id id kategorie
     * @return definice, pokud existuje
     */
    Optional<MemoryKindDefinition> byId(String id);

    /**
     * @return všechny registrované kategorie
     */
    Collection<MemoryKindDefinition> all();
}
