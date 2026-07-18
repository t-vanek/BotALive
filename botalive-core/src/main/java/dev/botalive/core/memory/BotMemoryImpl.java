package dev.botalive.core.memory;

import dev.botalive.api.memory.BotMemory;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.memory.MemoryRecord;
import dev.botalive.core.persistence.BotRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Dlouhodobá paměť bota – in-memory index s write-behind persistencí.
 *
 * <p>Vzpomínky drží v paměti (bot jich má stovky, ne miliony) a každou změnu
 * asynchronně propisuje do databáze přes {@link BotRepository}. Blízké
 * vzpomínky stejného druhu se slučují („oživení" existující vzpomínky), aby
 * paměť nerostla donekonečna – např. opakovaná návštěva stejné truhly jen
 * zvyšuje důležitost záznamu.</p>
 *
 * <p>Vztahové vzpomínky (FRIEND/ENEMY) podléhají časovému rozpadu
 * ({@link RelationDecay}): čtení vrací efektivní (rozpadlou) důležitost a
 * oživení z ní vychází – uložená hodnota zůstává „platná k času oživení",
 * takže se rozpad nikdy nesčítá dvakrát a nevyžaduje žádné průběžné zápisy.</p>
 */
public final class BotMemoryImpl implements BotMemory {

    /** Vzdálenost (bloky), do které se vzpomínka stejného druhu sloučí. */
    private static final double MERGE_DISTANCE_SQ = 8 * 8;

    private final UUID botId;
    private final BotRepository repository;
    private final RelationDecay decay;
    private final List<MemoryRecord> records = new ArrayList<>();
    /** Zdroj času (nahraditelný v testech – rozpad stojí na uplynulém čase). */
    private java.util.function.LongSupplier clock = System::currentTimeMillis;

    /**
     * @param botId      UUID vlastníka
     * @param repository repozitář pro persistenci ({@code null} = bez persistence, testy)
     * @param loaded     vzpomínky načtené z databáze při spawnu
     */
    public BotMemoryImpl(UUID botId, BotRepository repository, List<MemoryRecord> loaded) {
        this(botId, repository, loaded, RelationDecay.OFF);
    }

    /**
     * @param botId      UUID vlastníka
     * @param repository repozitář pro persistenci ({@code null} = bez persistence, testy)
     * @param loaded     vzpomínky načtené z databáze při spawnu
     * @param decay      časový rozpad vztahových vzpomínek
     */
    public BotMemoryImpl(UUID botId, BotRepository repository, List<MemoryRecord> loaded,
                         RelationDecay decay) {
        this.botId = botId;
        this.repository = repository;
        this.decay = decay;
        this.records.addAll(loaded);
    }

    /** Nahradí zdroj času (jen testy). */
    synchronized void clock(java.util.function.LongSupplier newClock) {
        this.clock = newClock;
    }

    @Override
    public synchronized void remember(MemoryKind kind, String world, int x, int y, int z,
                                      UUID subject, Map<String, String> data, double importance) {
        long now = clock.getAsLong();

        // Sloučení s existující blízkou vzpomínkou stejného druhu a subjektu.
        for (int i = 0; i < records.size(); i++) {
            MemoryRecord existing = records.get(i);
            if (existing.kind() != kind
                    || !Objects.equals(existing.world(), world)
                    || !Objects.equals(existing.subject(), subject)) {
                continue;
            }
            if (existing.distanceSquared(x, y, z) <= MERGE_DISTANCE_SQ) {
                // Oživení vychází z rozpadlé hodnoty – neudržovaný vztah se
                // nevrátí skokem na starou sílu, musí se vybudovat znovu.
                double base = decay.effective(kind, existing.importance(),
                        existing.updatedAt(), now);
                double boosted = Math.min(1.0, Math.max(base, importance) + 0.05);
                MemoryRecord updated = new MemoryRecord(existing.id(), botId, kind, world,
                        existing.x(), existing.y(), existing.z(), subject,
                        data.isEmpty() ? existing.data() : data,
                        boosted, existing.createdAt(), now);
                records.set(i, updated);
                if (existing.id() > 0 && repository != null) {
                    repository.touchMemory(existing.id(), boosted, now);
                }
                return;
            }
        }

        MemoryRecord record = new MemoryRecord(0, botId, kind, world, x, y, z, subject,
                Map.copyOf(data), Math.min(1.0, importance), now, now);
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
        long now = clock.getAsLong();
        return records.stream().filter(r -> r.kind() == kind)
                .map(r -> decayed(r, now)).toList();
    }

    @Override
    public synchronized Optional<MemoryRecord> recallNearest(MemoryKind kind, String world,
                                                             int x, int y, int z) {
        return records.stream()
                .filter(r -> r.kind() == kind && Objects.equals(r.world(), world))
                .min(Comparator.comparingDouble(r -> r.distanceSquared(x, y, z)))
                .map(r -> decayed(r, clock.getAsLong()));
    }

    @Override
    public synchronized List<MemoryRecord> recallAbout(UUID subject) {
        long now = clock.getAsLong();
        return records.stream().filter(r -> Objects.equals(r.subject(), subject))
                .map(r -> decayed(r, now)).toList();
    }

    /** Kopie záznamu s efektivní důležitostí (rozpad vztahů při čtení). */
    private MemoryRecord decayed(MemoryRecord record, long now) {
        double effective = decay.effective(record.kind(), record.importance(),
                record.updatedAt(), now);
        if (effective == record.importance()) {
            return record;
        }
        return new MemoryRecord(record.id(), record.botId(), record.kind(), record.world(),
                record.x(), record.y(), record.z(), record.subject(), record.data(),
                effective, record.createdAt(), record.updatedAt());
    }

    @Override
    public synchronized void forget(MemoryKind kind) {
        records.removeIf(r -> r.kind() == kind);
        if (repository != null) {
            repository.deleteMemories(botId, kind);
        }
    }

    @Override
    public synchronized void forgetIf(MemoryKind kind,
                                      java.util.function.Predicate<MemoryRecord> filter) {
        var iterator = records.iterator();
        while (iterator.hasNext()) {
            MemoryRecord record = iterator.next();
            if (record.kind() == kind && filter.test(record)) {
                iterator.remove();
                if (record.id() > 0) {
                    repository.deleteMemory(record.id());
                }
            }
        }
    }

    @Override
    public synchronized int size() {
        return records.size();
    }
}
