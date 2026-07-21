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

    // -------------------------------------------------- cizí kategorie (Memory SPI)

    @Test
    void ciziKategorieSeUlozicteAIzoluje() {
        MemoryKindRegistryImpl registry = new MemoryKindRegistryImpl();
        BotMemoryImpl memory = new BotMemoryImpl(BOT, null, List.of(), RelationDecay.OFF, registry);
        memory.remember("myplugin:shrine", "world", 10, 64, 10, null, Map.of("k", "v"), 0.7);
        memory.remember("myplugin:altar", "world", 20, 64, 20, null, Map.of(), 0.5);

        List<MemoryRecord> shrines = memory.recall("myplugin:shrine");
        assertEquals(1, shrines.size());
        assertEquals("myplugin:shrine", shrines.getFirst().kindId());
        assertEquals(MemoryKind.PLUGIN, shrines.getFirst().kind());
        assertEquals("v", shrines.getFirst().data().get("k"));
        // Různé cizí druhy se nemíchají, ani s vestavěnými.
        assertEquals(1, memory.recall("myplugin:altar").size());
        assertTrue(memory.recall("myplugin:none").isEmpty());
        assertTrue(memory.recall(MemoryKind.CHEST).isEmpty());
    }

    @Test
    void ciziKategorieSeRozpadaPodleRegistru() {
        MemoryKindRegistryImpl registry = new MemoryKindRegistryImpl();
        registry.register(new dev.botalive.api.memory.MemoryKindDefinition("myplugin:grudge", 0.1, 0.2));
        BotMemoryImpl memory = new BotMemoryImpl(BOT, null, List.of(), RelationDecay.OFF, registry);
        memory.clock(() -> nowMs[0]);

        memory.remember("myplugin:grudge", "world", 0, 64, 0, null, Map.of(), 0.9);
        assertEquals(0.9, memory.recall("myplugin:grudge").getFirst().importance(), 1e-9);
        nowMs[0] += 3 * DAY_MS;
        assertEquals(0.6, memory.recall("myplugin:grudge").getFirst().importance(), 1e-9, "-0.1/den");
        nowMs[0] += 100 * DAY_MS;
        assertEquals(0.2, memory.recall("myplugin:grudge").getFirst().importance(), 1e-9, "podlaha drží");
    }

    @Test
    void forgetCiziKategorieNechaOstatni() {
        BotMemoryImpl memory = new BotMemoryImpl(BOT, null, List.of(), RelationDecay.OFF,
                new MemoryKindRegistryImpl());
        memory.remember("myplugin:a", "world", 0, 64, 0, null, Map.of(), 0.5);
        memory.remember("myplugin:b", "world", 0, 64, 0, null, Map.of(), 0.5);

        memory.forget("myplugin:a");
        assertTrue(memory.recall("myplugin:a").isEmpty());
        assertEquals(1, memory.recall("myplugin:b").size(), "cizí kategorie se nemažou hromadně");
    }

    private static double importance(BotMemoryImpl memory, UUID subject) {
        List<MemoryRecord> records = memory.recallAbout(subject);
        assertEquals(1, records.size());
        return records.getFirst().importance();
    }
}
