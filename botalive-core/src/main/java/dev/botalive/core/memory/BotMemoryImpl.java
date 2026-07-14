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
 */
public final class BotMemoryImpl implements BotMemory {

    /** Vzdálenost (bloky), do které se vzpomínka stejného druhu sloučí. */
    private static final double MERGE_DISTANCE_SQ = 8 * 8;

    private final UUID botId;
    private final BotRepository repository;
    private final List<MemoryRecord> records = new ArrayList<>();

    /**
     * @param botId      UUID vlastníka
     * @param repository repozitář pro persistenci
     * @param loaded     vzpomínky načtené z databáze při spawnu
     */
    public BotMemoryImpl(UUID botId, BotRepository repository, List<MemoryRecord> loaded) {
        this.botId = botId;
        this.repository = repository;
        this.records.addAll(loaded);
    }

    @Override
    public synchronized void remember(MemoryKind kind, String world, int x, int y, int z,
                                      UUID subject, Map<String, String> data, double importance) {
        long now = System.currentTimeMillis();

        // Sloučení s existující blízkou vzpomínkou stejného druhu a subjektu.
        for (int i = 0; i < records.size(); i++) {
            MemoryRecord existing = records.get(i);
            if (existing.kind() != kind
                    || !Objects.equals(existing.world(), world)
                    || !Objects.equals(existing.subject(), subject)) {
                continue;
            }
            if (existing.distanceSquared(x, y, z) <= MERGE_DISTANCE_SQ) {
                double boosted = Math.min(1.0, Math.max(existing.importance(), importance) + 0.05);
                MemoryRecord updated = new MemoryRecord(existing.id(), botId, kind, world,
                        existing.x(), existing.y(), existing.z(), subject,
                        data.isEmpty() ? existing.data() : data,
                        boosted, existing.createdAt(), now);
                records.set(i, updated);
                if (existing.id() > 0) {
                    repository.touchMemory(existing.id(), boosted, now);
                }
                return;
            }
        }

        MemoryRecord record = new MemoryRecord(0, botId, kind, world, x, y, z, subject,
                Map.copyOf(data), Math.min(1.0, importance), now, now);
        records.add(record);
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
        return records.stream().filter(r -> r.kind() == kind).toList();
    }

    @Override
    public synchronized Optional<MemoryRecord> recallNearest(MemoryKind kind, String world,
                                                             int x, int y, int z) {
        return records.stream()
                .filter(r -> r.kind() == kind && Objects.equals(r.world(), world))
                .min(Comparator.comparingDouble(r -> r.distanceSquared(x, y, z)));
    }

    @Override
    public synchronized List<MemoryRecord> recallAbout(UUID subject) {
        return records.stream().filter(r -> Objects.equals(r.subject(), subject)).toList();
    }

    @Override
    public synchronized void forget(MemoryKind kind) {
        records.removeIf(r -> r.kind() == kind);
        repository.deleteMemories(botId, kind);
    }

    @Override
    public synchronized int size() {
        return records.size();
    }
}
