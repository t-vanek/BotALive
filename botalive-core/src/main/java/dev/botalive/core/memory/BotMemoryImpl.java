package dev.botalive.core.memory;

import dev.botalive.api.memory.BotMemory;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.memory.MemoryRecord;
import dev.botalive.core.persistence.BotRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Dlouhodobá paměť bota – in-memory index s write-behind persistencí.
 *
 * <p>Vzpomínky drží v paměti (bot jich má stovky, ne miliony) a každou změnu
 * asynchronně propisuje do databáze přes {@link BotRepository}. Blízké
 * vzpomínky stejné kategorie se slučují („oživení" existující vzpomínky), aby
 * paměť nerostla donekonečna – např. opakovaná návštěva stejné truhly jen
 * zvyšuje důležitost záznamu.</p>
 *
 * <p>Kategorie se identifikuje řetězcovým id ({@link MemoryRecord#kindId()}):
 * u vestavěných druhů je to název {@link MemoryKind}, cizí druhy pluginů se
 * ukládají jako {@link MemoryKind#PLUGIN} se skutečným id v datech. Vestavěné
 * i řetězcové varianty {@link BotMemory} tak sdílejí jednu cestu.</p>
 *
 * <p>Vztahové vzpomínky (FRIEND/ENEMY) podléhají časovému rozpadu
 * ({@link RelationDecay}) a registrované cizí kategorie svému rozpadu z registru
 * ({@link MemoryKindRegistryImpl}): čtení vrací efektivní (rozpadlou) důležitost
 * a oživení z ní vychází – uložená hodnota zůstává „platná k času oživení".</p>
 */
public final class BotMemoryImpl implements BotMemory {

    /** Vzdálenost (bloky), do které se vzpomínka stejné kategorie sloučí. */
    private static final double MERGE_DISTANCE_SQ = 8 * 8;

    private final UUID botId;
    private final BotRepository repository;
    private final RelationDecay decay;
    /** Registr cizích kategorií (kvůli jejich rozpadu); může být {@code null}. */
    private final MemoryKindRegistryImpl kindRegistry;
    private final List<MemoryRecord> records = new ArrayList<>();
    /** Zdroj času (nahraditelný v testech – rozpad stojí na uplynulém čase). */
    private java.util.function.LongSupplier clock = System::currentTimeMillis;

    /**
     * @param botId      UUID vlastníka
     * @param repository repozitář pro persistenci ({@code null} = bez persistence, testy)
     * @param loaded     vzpomínky načtené z databáze při spawnu
     */
    public BotMemoryImpl(UUID botId, BotRepository repository, List<MemoryRecord> loaded) {
        this(botId, repository, loaded, RelationDecay.OFF, null);
    }

    /**
     * @param botId      UUID vlastníka
     * @param repository repozitář pro persistenci ({@code null} = bez persistence, testy)
     * @param loaded     vzpomínky načtené z databáze při spawnu
     * @param decay      časový rozpad vztahových vzpomínek
     */
    public BotMemoryImpl(UUID botId, BotRepository repository, List<MemoryRecord> loaded,
                         RelationDecay decay) {
        this(botId, repository, loaded, decay, null);
    }

    /**
     * @param botId        UUID vlastníka
     * @param repository   repozitář pro persistenci ({@code null} = bez persistence)
     * @param loaded       vzpomínky načtené z databáze při spawnu
     * @param decay        časový rozpad vztahových vzpomínek
     * @param kindRegistry registr cizích kategorií (rozpad; může být {@code null})
     */
    public BotMemoryImpl(UUID botId, BotRepository repository, List<MemoryRecord> loaded,
                         RelationDecay decay, MemoryKindRegistryImpl kindRegistry) {
        this.botId = botId;
        this.repository = repository;
        this.decay = decay;
        this.kindRegistry = kindRegistry;
        this.records.addAll(loaded);
    }

    /** Nahradí zdroj času (jen testy). */
    synchronized void clock(java.util.function.LongSupplier newClock) {
        this.clock = newClock;
    }

    @Override
    public synchronized void remember(MemoryKind kind, String world, int x, int y, int z,
                                      UUID subject, Map<String, String> data, double importance) {
        remember(kind.name(), world, x, y, z, subject, data, importance);
    }

    @Override
    public synchronized void remember(String kindId, String world, int x, int y, int z,
                                      UUID subject, Map<String, String> data, double importance) {
        long now = clock.getAsLong();
        MemoryKind enumKind = toEnumKind(kindId);
        String normId = enumKind == MemoryKind.PLUGIN ? kindId.trim() : enumKind.name();
        Map<String, String> effectiveData = data;
        if (enumKind == MemoryKind.PLUGIN) {
            Map<String, String> withId = new HashMap<>(data);
            withId.put(MemoryRecord.PLUGIN_KIND_KEY, normId);
            effectiveData = withId;
        }

        // Sloučení s existující blízkou vzpomínkou stejné kategorie a subjektu.
        for (int i = 0; i < records.size(); i++) {
            MemoryRecord existing = records.get(i);
            if (!existing.kindId().equalsIgnoreCase(normId)
                    || !Objects.equals(existing.world(), world)
                    || !Objects.equals(existing.subject(), subject)) {
                continue;
            }
            if (existing.distanceSquared(x, y, z) <= MERGE_DISTANCE_SQ) {
                // Oživení vychází z rozpadlé hodnoty – neudržovaný vztah se
                // nevrátí skokem na starou sílu, musí se vybudovat znovu.
                double base = effectiveImportance(existing, now);
                double boosted = Math.min(1.0, Math.max(base, importance) + 0.05);
                MemoryRecord updated = new MemoryRecord(existing.id(), botId, enumKind, world,
                        existing.x(), existing.y(), existing.z(), subject,
                        effectiveData.isEmpty() ? existing.data() : Map.copyOf(effectiveData),
                        boosted, existing.createdAt(), now);
                records.set(i, updated);
                if (existing.id() > 0 && repository != null) {
                    repository.touchMemory(existing.id(), boosted, now);
                }
                return;
            }
        }

        MemoryRecord record = new MemoryRecord(0, botId, enumKind, world, x, y, z, subject,
                Map.copyOf(effectiveData), Math.min(1.0, importance), now, now);
        records.add(record);
        if (repository == null) {
            return;
        }
        // Po INSERTu doplnit přidělené id (kvůli pozdějším touch/update operacím).
        repository.insertMemory(record).thenAccept(saved -> {
            synchronized (this) {
                int index = records.indexOf(record);
                if (index >= 0) {
                    records.set(index, saved);
                }
            }
        });
    }

    @Override
    public synchronized List<MemoryRecord> recall(MemoryKind kind) {
        return recall(kind.name());
    }

    @Override
    public synchronized List<MemoryRecord> recall(String kindId) {
        long now = clock.getAsLong();
        String norm = kindId.trim();
        return records.stream().filter(r -> r.kindId().equalsIgnoreCase(norm))
                .map(r -> decayed(r, now)).toList();
    }

    @Override
    public synchronized Optional<MemoryRecord> recallNearest(MemoryKind kind, String world,
                                                             int x, int y, int z) {
        return recallNearest(kind.name(), world, x, y, z);
    }

    @Override
    public synchronized Optional<MemoryRecord> recallNearest(String kindId, String world,
                                                             int x, int y, int z) {
        String norm = kindId.trim();
        return records.stream()
                .filter(r -> r.kindId().equalsIgnoreCase(norm) && Objects.equals(r.world(), world))
                .min(Comparator.comparingDouble(r -> r.distanceSquared(x, y, z)))
                .map(r -> decayed(r, clock.getAsLong()));
    }

    @Override
    public synchronized List<MemoryRecord> recallAbout(UUID subject) {
        long now = clock.getAsLong();
        return records.stream().filter(r -> Objects.equals(r.subject(), subject))
                .map(r -> decayed(r, now)).toList();
    }

    /** Efektivní důležitost vzpomínky (rozpad vztahů i cizích kategorií při čtení). */
    private double effectiveImportance(MemoryRecord record, long now) {
        if (record.kind() == MemoryKind.PLUGIN) {
            if (kindRegistry != null) {
                Optional<dev.botalive.api.memory.MemoryKindDefinition> def =
                        kindRegistry.byId(record.kindId());
                if (def.isPresent()) {
                    return def.get().effective(record.importance(), record.updatedAt(), now);
                }
            }
            return record.importance();
        }
        return decay.effective(record.kind(), record.importance(), record.updatedAt(), now);
    }

    /** Kopie záznamu s efektivní důležitostí, nebo tentýž záznam beze změny. */
    private MemoryRecord decayed(MemoryRecord record, long now) {
        double effective = effectiveImportance(record, now);
        if (effective == record.importance()) {
            return record;
        }
        return new MemoryRecord(record.id(), record.botId(), record.kind(), record.world(),
                record.x(), record.y(), record.z(), record.subject(), record.data(),
                effective, record.createdAt(), record.updatedAt());
    }

    @Override
    public synchronized void forget(MemoryKind kind) {
        forget(kind.name());
    }

    @Override
    public synchronized void forget(String kindId) {
        String norm = kindId.trim();
        MemoryKind enumKind = toEnumKind(kindId);
        // Vestavěné druhy lze smazat hromadně přes sloupec kind; cizí kategorie
        // sdílejí kind=PLUGIN, proto se mažou po jedné podle id (jinak by hromadné
        // DELETE WHERE kind='PLUGIN' smazalo i cizí kategorie ostatních pluginů).
        if (enumKind != MemoryKind.PLUGIN) {
            records.removeIf(r -> r.kindId().equalsIgnoreCase(norm));
            if (repository != null) {
                repository.deleteMemories(botId, enumKind);
            }
            return;
        }
        var iterator = records.iterator();
        while (iterator.hasNext()) {
            MemoryRecord record = iterator.next();
            if (record.kindId().equalsIgnoreCase(norm)) {
                iterator.remove();
                if (record.id() > 0 && repository != null) {
                    repository.deleteMemory(record.id());
                }
            }
        }
    }

    @Override
    public synchronized void forgetIf(MemoryKind kind,
                                      java.util.function.Predicate<MemoryRecord> filter) {
        forgetIf(kind.name(), filter);
    }

    @Override
    public synchronized void forgetIf(String kindId,
                                      java.util.function.Predicate<MemoryRecord> filter) {
        String norm = kindId.trim();
        var iterator = records.iterator();
        while (iterator.hasNext()) {
            MemoryRecord record = iterator.next();
            if (record.kindId().equalsIgnoreCase(norm) && filter.test(record)) {
                iterator.remove();
                if (record.id() > 0 && repository != null) {
                    repository.deleteMemory(record.id());
                }
            }
        }
    }

    @Override
    public synchronized int size() {
        return records.size();
    }

    /** Vestavěný enum pro id (case-insensitive), jinak {@link MemoryKind#PLUGIN}. */
    private static MemoryKind toEnumKind(String kindId) {
        try {
            return MemoryKind.valueOf(kindId.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return MemoryKind.PLUGIN;
        }
    }
}
