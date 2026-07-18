package dev.botalive.core.ai;

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
 * Testy čtení znalostí o Endu z paměti (portály, trofeje, rozestup výprav).
 */
class EndKnowledgeTest {

    private static final UUID BOT = UUID.randomUUID();

    private static MemoryRecord record(MemoryKind kind, String world, int x, int y, int z,
                                       Map<String, String> data, long updatedAt) {
        return new MemoryRecord(1, BOT, kind, world, x, y, z, null, data, 0.8, updatedAt, updatedAt);
    }

    @Test
    void portalDoEnduPoznaPruchodIObjev() {
        // Průchod portálem: data "to" nese klíč cílového světa.
        assertTrue(EndKnowledge.isEndPortal(record(MemoryKind.PORTAL, "world", 0, 30, 0,
                Map.of("to", "minecraft:the_end"), 0)));
        // Objevený/admin portál: data "type" = end.
        assertTrue(EndKnowledge.isEndPortal(record(MemoryKind.PORTAL, "world", 0, 30, 0,
                Map.of("type", "end"), 0)));
        // Nether portál není portál do Endu.
        assertFalse(EndKnowledge.isEndPortal(record(MemoryKind.PORTAL, "world", 0, 30, 0,
                Map.of("to", "minecraft:the_nether"), 0)));
        // Jiné kategorie se nepočítají, ani se správnými daty.
        assertFalse(EndKnowledge.isEndPortal(record(MemoryKind.DANGER, "world", 0, 30, 0,
                Map.of("type", "end"), 0)));
    }

    @Test
    void nejblizsiPortalRespektujeSvet() {
        var far = record(MemoryKind.PORTAL, "world", 1000, 30, 0, Map.of("type", "end"), 0);
        var near = record(MemoryKind.PORTAL, "world", 100, 30, 0, Map.of("type", "end"), 0);
        var otherWorld = record(MemoryKind.PORTAL, "world_b", 10, 30, 0, Map.of("type", "end"), 0);
        var found = EndKnowledge.nearestEndPortal(List.of(far, near, otherWorld), "world", 0, 0);
        assertTrue(found.isPresent());
        assertEquals(100, found.get().x(), "vybírá se nejbližší portál ve správném světě");
        assertTrue(EndKnowledge.nearestEndPortal(List.of(otherWorld), "world", 0, 0).isEmpty());
    }

    @Test
    void rozestupVypravSeMeriOdPruchodu() {
        long now = 1_000_000_000L;
        var visit = record(MemoryKind.PORTAL, "world", 0, 30, 0,
                Map.of("to", "minecraft:the_end"), now - 60_000);
        assertTrue(EndKnowledge.recentEndVisit(List.of(visit), now, 90_000));
        assertFalse(EndKnowledge.recentEndVisit(List.of(visit), now, 30_000));
        // Objevený portál (bez průchodu) rozestup nespouští.
        var seen = record(MemoryKind.PORTAL, "world", 0, 30, 0, Map.of("type", "end"), now);
        assertFalse(EndKnowledge.recentEndVisit(List.of(seen), now, 90_000));
    }

    @Test
    void drakovaTrofej() {
        assertFalse(EndKnowledge.dragonSlain(List.of()));
        assertTrue(EndKnowledge.dragonSlain(List.of(
                record(MemoryKind.TROPHY, "the_end", 0, 64, 0, Map.of("type", "dragon"), 0))));
    }

    @Test
    void znalostPortaluNapricSvety() {
        var otherWorld = record(MemoryKind.PORTAL, "world_b", 10, 30, 0, Map.of("type", "end"), 0);
        assertTrue(EndKnowledge.knowsEndPortal(List.of(otherWorld)),
                "pro ambici stačí portál v libovolném světě");
        assertFalse(EndKnowledge.knowsEndPortal(List.of()));
    }
}
