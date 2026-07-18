package dev.botalive.core.social;

import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.memory.MemoryRecord;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy výběru drbů – co z paměti stojí za řeč a komu.
 */
class SocialGraphTest {

    private static final UUID TELLER = new UUID(0, 1);
    private static final UUID LISTENER = new UUID(0, 2);
    private static final UUID THIEF = new UUID(0, 3);

    private static MemoryRecord record(MemoryKind kind, UUID subject, double importance) {
        return new MemoryRecord(0, TELLER, kind, "world", 0, 64, 0, subject,
                Map.of(), importance, 0, 0);
    }

    @Test
    void mistaSeSdilejiVolne() {
        var shareable = SocialGraph.shareable(
                List.of(record(MemoryKind.VILLAGE, null, 0.8),
                        record(MemoryKind.MINE, null, 0.5)),
                List.of(), 0.0, LISTENER);
        assertEquals(2, shareable.size(), "místa se říkají i cizím");
    }

    @Test
    void bezvyznamneVzpominkyNestojiZaRec() {
        var shareable = SocialGraph.shareable(
                List.of(record(MemoryKind.DANGER, null, 0.1)),
                List.of(), 1.0, LISTENER);
        assertTrue(shareable.isEmpty(), "slabé drby se dál nešíří (řetěz vyhasíná)");
    }

    @Test
    void pomluvySeSdilejiJenSKamarady() {
        List<MemoryRecord> grudges = List.of(record(MemoryKind.ENEMY, THIEF, 0.8));
        assertTrue(SocialGraph.shareable(List.of(), grudges, 0.1, LISTENER).isEmpty(),
                "cizímu se pomluvy neříkají");
        assertEquals(1, SocialGraph.shareable(List.of(), grudges, 0.5, LISTENER).size(),
                "kamarád se dozví, kdo krade");
    }

    @Test
    void pomluvaOPosluchaciSeMuNerika() {
        List<MemoryRecord> grudges = List.of(record(MemoryKind.ENEMY, LISTENER, 0.8));
        assertTrue(SocialGraph.shareable(List.of(), grudges, 1.0, LISTENER).isEmpty(),
                "„ty krades“ není drb, ale konflikt – tudy nejde");
    }

    @Test
    void slabaZastNeniPomluva() {
        List<MemoryRecord> grudges = List.of(record(MemoryKind.ENEMY, THIEF, 0.3));
        assertTrue(SocialGraph.shareable(List.of(), grudges, 1.0, LISTENER).isEmpty(),
                "pomluva chce silný zážitek, ne starou oděrku");
    }

    @Test
    void bezManageruNeniNikdoBot() {
        SocialGraph graph = new SocialGraph();
        assertFalse(graph.isBot(TELLER), "před attach() se všichni berou jako hráči");
    }
}
