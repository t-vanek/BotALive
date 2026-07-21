package dev.botalive.api.memory;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Dlouhodobá paměť jednoho bota.
 *
 * <p>Implementace je thread-safe: čte i zapisuje se z AI vlákna bota, ukládání do
 * databáze probíhá asynchronně (write-behind). Po restartu serveru se paměť načte
 * zpět a bot naváže na svou historii.</p>
 */
public interface BotMemory {

    /**
     * Uloží novou vzpomínku, případně oživí (aktualizuje) blízkou vzpomínku stejného druhu.
     *
     * @param kind       kategorie
     * @param world      svět
     * @param x          blok X
     * @param y          blok Y
     * @param z          blok Z
     * @param subject    subjekt vzpomínky, může být {@code null}
     * @param data       doplňková data, může být prázdné
     * @param importance důležitost 0–1
     */
    void remember(MemoryKind kind, String world, int x, int y, int z,
                  UUID subject, Map<String, String> data, double importance);

    /**
     * Jako {@link #remember(MemoryKind, String, int, int, int, UUID, Map, double)},
     * ale s řetězcovým id kategorie – umožňuje cizím pluginům vlastní druhy
     * vzpomínek (viz {@link MemoryKindRegistry}). Vestavěný název (např.
     * {@code "CHEST"}) se namapuje na příslušný {@link MemoryKind}; jiné id se
     * uloží jako {@link MemoryKind#PLUGIN} se zachovaným id.
     *
     * @param kindId     id kategorie (vestavěný název enumu nebo pluginové id)
     * @param world      svět
     * @param x          blok X
     * @param y          blok Y
     * @param z          blok Z
     * @param subject    subjekt vzpomínky, může být {@code null}
     * @param data       doplňková data
     * @param importance důležitost 0–1
     */
    void remember(String kindId, String world, int x, int y, int z,
                  UUID subject, Map<String, String> data, double importance);

    /**
     * @param kind kategorie
     * @return všechny vzpomínky dané kategorie (nemodifikovatelný seznam)
     */
    List<MemoryRecord> recall(MemoryKind kind);

    /**
     * @param kindId id kategorie (vestavěný název enumu nebo pluginové id)
     * @return všechny vzpomínky dané kategorie
     */
    List<MemoryRecord> recall(String kindId);

    /**
     * Najde vzpomínku dané kategorie nejbližší k bodu.
     *
     * @param kind  kategorie
     * @param world svět, ve kterém se hledá
     * @param x     blok X
     * @param y     blok Y
     * @param z     blok Z
     * @return nejbližší vzpomínka, nebo prázdno
     */
    Optional<MemoryRecord> recallNearest(MemoryKind kind, String world, int x, int y, int z);

    /**
     * @param kindId id kategorie (vestavěný název enumu nebo pluginové id)
     * @param world  svět, ve kterém se hledá
     * @param x      blok X
     * @param y      blok Y
     * @param z      blok Z
     * @return nejbližší vzpomínka, nebo prázdno
     */
    Optional<MemoryRecord> recallNearest(String kindId, String world, int x, int y, int z);

    /**
     * @param subject UUID hráče/entity
     * @return vzpomínky vázané na daný subjekt (např. je to nepřítel?)
     */
    List<MemoryRecord> recallAbout(UUID subject);

    /**
     * Smaže vzpomínky dané kategorie.
     *
     * @param kind kategorie ke smazání
     */
    void forget(MemoryKind kind);

    /**
     * Smaže vzpomínky dané kategorie podle id.
     *
     * @param kindId id kategorie (vestavěný název enumu nebo pluginové id)
     */
    void forget(String kindId);

    /**
     * Smaže vzpomínky dané kategorie, které vyhoví filtru – např. při
     * stěhování zapomenout jen starý dům, ale ne kotvu spawnu.
     *
     * @param kind   kategorie
     * @param filter smaže se to, pro co filtr vrátí {@code true}
     */
    void forgetIf(MemoryKind kind, java.util.function.Predicate<MemoryRecord> filter);

    /**
     * Smaže vzpomínky dané kategorie, které vyhoví filtru.
     *
     * @param kindId id kategorie (vestavěný název enumu nebo pluginové id)
     * @param filter smaže se to, pro co filtr vrátí {@code true}
     */
    void forgetIf(String kindId, java.util.function.Predicate<MemoryRecord> filter);

    /**
     * @return celkový počet vzpomínek v paměti
     */
    int size();
}
