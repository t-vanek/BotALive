package dev.botalive.core.ai;

import dev.botalive.api.memory.BotMemory;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.memory.MemoryRecord;

import java.util.List;
import java.util.Optional;

/**
 * Co bot ví o Endu – čtení PORTAL/TROPHY paměti na jednom místě.
 *
 * <p>PORTAL vzpomínky vznikají třemi cestami: průchodem portálu
 * ({@code BotImpl.onRespawn}, data {@code to} = klíč cílového světa),
 * spatřením portálových bloků při toulkách ({@code BotImpl}, data
 * {@code type=end}) a od admina ({@code /botalive end portal}). Drby je
 * navíc šíří mezi boty. Statické metody nad seznamy záznamů jsou čisté
 * a jednotkově testovatelné.</p>
 */
public final class EndKnowledge {

    /** Hodnota data {@code type} portálu do Endu. */
    public static final String TYPE_END = "end";

    private EndKnowledge() {
    }

    /**
     * Je záznam portálem do Endu? (průchod s cílem the_end, nebo nalezený/
     * zadaný portál s {@code type=end})
     *
     * @param record PORTAL záznam
     * @return {@code true} pro portál vedoucí do Endu
     */
    public static boolean isEndPortal(MemoryRecord record) {
        if (record.kind() != MemoryKind.PORTAL) {
            return false;
        }
        String to = record.data().get("to");
        if (to != null && dev.botalive.core.world.Dimension.fromWorldKey(to)
                == dev.botalive.core.world.Dimension.THE_END) {
            return true;
        }
        return TYPE_END.equals(record.data().get("type"));
    }

    /**
     * Nejbližší známý portál do Endu v daném světě.
     *
     * @param records PORTAL záznamy paměti
     * @param world   svět bota
     * @param x       pozice bota X
     * @param z       pozice bota Z
     * @return záznam portálu, nebo prázdno
     */
    public static Optional<MemoryRecord> nearestEndPortal(List<MemoryRecord> records,
                                                          String world, int x, int z) {
        return records.stream()
                .filter(EndKnowledge::isEndPortal)
                .filter(r -> world.equals(r.world()))
                .min(java.util.Comparator.comparingLong(r -> {
                    long dx = r.x() - x;
                    long dz = r.z() - z;
                    return dx * dx + dz * dz;
                }));
    }

    /**
     * Nejbližší známý portál do Endu ve světě bota (pohodlí nad pamětí).
     *
     * @param memory paměť bota
     * @param world  svět bota
     * @param x      pozice bota X
     * @param z      pozice bota Z
     * @return záznam portálu, nebo prázdno
     */
    public static Optional<MemoryRecord> nearestEndPortal(BotMemory memory,
                                                          String world, int x, int z) {
        return nearestEndPortal(memory.recall(MemoryKind.PORTAL), world, x, z);
    }

    /**
     * Zná bot nějaký portál do Endu? (kterýkoli svět – pro postup ambice)
     *
     * @param records PORTAL záznamy paměti
     * @return {@code true} pokud paměť nese aspoň jeden portál do Endu
     */
    public static boolean knowsEndPortal(List<MemoryRecord> records) {
        return records.stream().anyMatch(EndKnowledge::isEndPortal);
    }

    /**
     * Byl bot v Endu v posledních {@code windowMs}? (rozestup výprav)
     *
     * <p>Počítá se jen vlastní průchod ({@code to} bez gossip značky) –
     * z doslechu se rozestup výprav neměří.</p>
     *
     * @param records  PORTAL záznamy paměti
     * @param nowMs    aktuální čas (epoch ms)
     * @param windowMs okno (ms)
     * @return {@code true} pokud průchod do Endu proběhl uvnitř okna
     */
    public static boolean recentEndVisit(List<MemoryRecord> records, long nowMs, long windowMs) {
        return records.stream()
                .filter(r -> r.kind() == MemoryKind.PORTAL)
                .filter(r -> !"gossip".equals(r.data().get("via")))
                .filter(r -> {
                    String to = r.data().get("to");
                    return to != null && dev.botalive.core.world.Dimension.fromWorldKey(to)
                            == dev.botalive.core.world.Dimension.THE_END;
                })
                .anyMatch(r -> nowMs - r.updatedAt() < windowMs);
    }

    /**
     * Zabil už bot draka? (TROPHY {@code type=dragon})
     *
     * @param records TROPHY záznamy paměti
     * @return {@code true} pokud má bot dračí trofej
     */
    public static boolean dragonSlain(List<MemoryRecord> records) {
        return records.stream()
                .anyMatch(r -> r.kind() == MemoryKind.TROPHY
                        && "dragon".equals(r.data().get("type")));
    }

    /**
     * Zabil už bot draka? (pohodlí nad pamětí)
     *
     * @param memory paměť bota
     * @return {@code true} pokud má bot dračí trofej
     */
    public static boolean dragonSlain(BotMemory memory) {
        return dragonSlain(memory.recall(MemoryKind.TROPHY));
    }
}
