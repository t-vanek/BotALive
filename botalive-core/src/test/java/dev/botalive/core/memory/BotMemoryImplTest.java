package dev.botalive.core.memory;

import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.memory.MemoryRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy rozpadu vztahů v paměti – přátelství i zášť bez oživování slábnou.
 *
 * <p>Rozpad je derivovaná hodnota (počítá se při čtení), takže se nesmí
 * sečíst dvakrát a oživení musí vycházet z rozpadlé hodnoty – neudržovaný
 * vztah se nevrací skokem na starou sílu.</p>
 */
class BotMemoryImplTest {

    private static final long DAY_MS = 24L * 60 * 60 * 1000;
    private static final UUID BOT = new UUID(0, 1);
    private static final UUID FRIEND = new UUID(0, 2);
    private static final UUID ENEMY = new UUID(0, 3);

    /** Decay jako v defaultní konfiguraci. */
    private static final RelationDecay DECAY = new RelationDecay(true, 0.01, 0.03, 0.1);

    private final long[] nowMs = {1_000_000_000L};

    private BotMemoryImpl memory(RelationDecay decay) {
        BotMemoryImpl memory = new BotMemoryImpl(BOT, null, List.of(), decay);
        memory.clock(() -> nowMs[0]);
        return memory;
    }

    @Test
    void pratelstviBezKontaktuSlabne() {
        BotMemoryImpl memory = memory(DECAY);
        memory.remember(MemoryKind.FRIEND, "world", 0, 64, 0, FRIEND, Map.of(), 0.8);
        assertEquals(0.8, importance(memory, FRIEND), 1e-9, "čerstvý vztah se nerozpadá");

        nowMs[0] += 10 * DAY_MS;
        assertEquals(0.7, importance(memory, FRIEND), 1e-9, "-0.01 za den");
    }

    @Test
    void zastSeHojiRychlejiNezPratelstvi() {
        BotMemoryImpl memory = memory(DECAY);
        memory.remember(MemoryKind.FRIEND, "world", 0, 64, 0, FRIEND, Map.of(), 0.8);
        memory.remember(MemoryKind.ENEMY, "world", 0, 64, 0, ENEMY, Map.of(), 0.8);

        nowMs[0] += 7 * DAY_MS;
        double friend = importance(memory, FRIEND);
        double enemy = importance(memory, ENEMY);
        assertTrue(enemy < friend, "čas rány hojí – zášť mizí rychleji");
        // Krádež (0.8) po týdnu klesne pod práh roztržky (0.6).
        assertTrue(enemy < 0.6, "týden stará zášť už neblokuje vesnici: " + enemy);
    }

    @Test
    void rozpadNikdyNeklesnePodPodlahu() {
        BotMemoryImpl memory = memory(DECAY);
        memory.remember(MemoryKind.ENEMY, "world", 0, 64, 0, ENEMY, Map.of(), 0.9);
        nowMs[0] += 365 * DAY_MS;
        assertEquals(0.1, importance(memory, ENEMY), 1e-9, "podlaha drží");
    }

    @Test
    void slabyVztahSeNaPodlahuNezveda() {
        BotMemoryImpl memory = memory(DECAY);
        memory.remember(MemoryKind.FRIEND, "world", 0, 64, 0, FRIEND, Map.of(), 0.05);
        nowMs[0] += 30 * DAY_MS;
        assertEquals(0.05, importance(memory, FRIEND), 1e-9,
                "podlaha nesmí slabé vztahy posilovat");
    }

    @Test
    void ozivenVztahVychaziZRozpadleHodnoty() {
        BotMemoryImpl memory = memory(DECAY);
        memory.remember(MemoryKind.FRIEND, "world", 0, 64, 0, FRIEND, Map.of(), 0.8);
        nowMs[0] += 20 * DAY_MS; // efektivně 0.6
        memory.remember(MemoryKind.FRIEND, "world", 0, 64, 0, FRIEND, Map.of(), 0.3);
        // max(0.6, 0.3) + 0.05 – ne max(0.8, 0.3) + 0.05.
        assertEquals(0.65, importance(memory, FRIEND), 1e-9,
                "oživení staví na rozpadlé hodnotě");
        // Oživení resetuje hodiny rozpadu – hned po něm se nic neztrácí.
        assertEquals(0.65, importance(memory, FRIEND), 1e-9);
    }

    @Test
    void mistniVzpominkySeNerozpadaji() {
        BotMemoryImpl memory = memory(DECAY);
        memory.remember(MemoryKind.VILLAGE, "world", 100, 64, 100, null, Map.of(), 0.8);
        nowMs[0] += 100 * DAY_MS;
        assertEquals(0.8, memory.recall(MemoryKind.VILLAGE).getFirst().importance(), 1e-9,
                "rozpad platí jen pro vztahy (FRIEND/ENEMY)");
    }

    @Test
    void vypnutyRozpadNicNemeni() {
        BotMemoryImpl memory = memory(RelationDecay.OFF);
        memory.remember(MemoryKind.FRIEND, "world", 0, 64, 0, FRIEND, Map.of(), 0.8);
        nowMs[0] += 100 * DAY_MS;
        assertEquals(0.8, importance(memory, FRIEND), 1e-9);
    }

    private static double importance(BotMemoryImpl memory, UUID subject) {
        List<MemoryRecord> records = memory.recallAbout(subject);
        assertEquals(1, records.size());
        return records.getFirst().importance();
    }
}
