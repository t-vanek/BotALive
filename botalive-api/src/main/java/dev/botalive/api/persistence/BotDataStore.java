package dev.botalive.api.persistence;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Perzistentní key-value úložiště pro data cizích pluginů, vázané na bota.
 *
 * <p>Umožňuje pluginu ukládat vlastní stav vázaný na životní cyklus bota, aniž
 * by sahal na schéma jádra. Klíčem je trojice <b>bot + namespace + key</b>;
 * {@code namespace} volte stabilní a unikátní (např. jméno pluginu), aby se
 * pluginy navzájem nepřepisovaly. Data se <b>smažou spolu s botem</b> při
 * {@code /botalive remove ... purge}.</p>
 *
 * <p><b>Vlákno.</b> Všechny operace běží asynchronně na DB vlákně (herní ani AI
 * vlákna nikdy nečekají na disk) a vrací {@link CompletableFuture}. Zápisy
 * z pohledu volajícího neblokují.</p>
 */
public interface BotDataStore {

    /**
     * Uloží (nebo přepíše) hodnotu.
     *
     * @param botId     UUID bota
     * @param namespace jmenný prostor pluginu
     * @param key       klíč v rámci jmenného prostoru
     * @param value     hodnota
     * @return future dokončený po zápisu
     */
    CompletableFuture<Void> put(UUID botId, String namespace, String key, String value);

    /**
     * @param botId     UUID bota
     * @param namespace jmenný prostor
     * @param key       klíč
     * @return future s hodnotou, nebo prázdno pokud klíč není uložen
     */
    CompletableFuture<Optional<String>> get(UUID botId, String namespace, String key);

    /**
     * @param botId     UUID bota
     * @param namespace jmenný prostor
     * @return future se všemi páry {@code key → value} daného jmenného prostoru
     *         (prázdná mapa pokud nic není)
     */
    CompletableFuture<Map<String, String>> getNamespace(UUID botId, String namespace);

    /**
     * Smaže jeden klíč.
     *
     * @param botId     UUID bota
     * @param namespace jmenný prostor
     * @param key       klíč
     * @return future dokončený po smazání (i když klíč neexistoval)
     */
    CompletableFuture<Void> remove(UUID botId, String namespace, String key);
}
