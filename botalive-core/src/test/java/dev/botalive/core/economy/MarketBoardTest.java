package dev.botalive.core.economy;

import dev.botalive.core.util.BlockPos;
import org.bukkit.Material;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy tržiště – nabídky, zamlouvání, TTL. Bez botů a bez peněz
 * (převody řeší {@code settle} nad běžícím serverem).
 */
class MarketBoardTest {

    private static final String WORLD = "world";
    private static final BlockPos SPOT = new BlockPos(0, 64, 0);

    private long now;
    private MarketBoard board;

    private final UUID seller = new UUID(0, 1);
    private final UUID buyer = new UUID(0, 2);

    @BeforeEach
    void setUp() {
        now = 1_000_000L;
        board = new MarketBoard(() -> now);
    }

    @Test
    void nabidkaSeNajdeJenVDosahuASvete() {
        board.post(seller, "Pepa", WORLD, SPOT, Material.BREAD, 5, 10);
        assertTrue(board.findNearby(WORLD, SPOT.offset(10, 0, 0), 48, buyer,
                o -> true).isPresent());
        assertTrue(board.findNearby(WORLD, SPOT.offset(100, 0, 0), 48, buyer,
                o -> true).isEmpty());
        assertTrue(board.findNearby("nether", SPOT, 48, buyer, o -> true).isEmpty());
    }

    @Test
    void vlastniNabidkaSeNekupuje() {
        board.post(seller, "Pepa", WORLD, SPOT, Material.BREAD, 5, 10);
        assertTrue(board.findNearby(WORLD, SPOT, 48, seller, o -> true).isEmpty());
    }

    @Test
    void prvniZamluveniBere() {
        var offer = board.post(seller, "Pepa", WORLD, SPOT, Material.BREAD, 5, 10);
        assertTrue(board.claim(offer.id(), buyer, "Lucka"));
        assertFalse(board.claim(offer.id(), new UUID(0, 3), "Karel"),
                "zamluvená nabídka už není k mání");
        assertEquals(buyer, board.pendingDeal(seller).orElseThrow().buyer());
        // Zamluvená nabídka zmizela z nástěnky.
        assertTrue(board.findNearby(WORLD, SPOT, 48, new UUID(0, 3), o -> true).isEmpty());
    }

    @Test
    void dokonceniObchodUklidi() {
        var offer = board.post(seller, "Pepa", WORLD, SPOT, Material.BREAD, 5, 10);
        board.claim(offer.id(), buyer, "Lucka");
        board.completeDeal(seller);
        assertTrue(board.pendingDeal(seller).isEmpty());
        assertFalse(board.hasDeal(seller));
    }

    @Test
    void nabidkaVyprsi() {
        board.post(seller, "Pepa", WORLD, SPOT, Material.BREAD, 5, 10);
        now += 5 * 60_000;
        assertTrue(board.findNearby(WORLD, SPOT, 48, buyer, o -> true).isEmpty());
    }

    @Test
    void skomirajiciObchodVyprsi() {
        var offer = board.post(seller, "Pepa", WORLD, SPOT, Material.BREAD, 5, 10);
        board.claim(offer.id(), buyer, "Lucka");
        now += 2 * 60_000;
        assertTrue(board.pendingDeal(seller).isEmpty(), "kupec nedošel – obchod padá");
    }

    @Test
    void novaNabidkaNahrazujeStarou() {
        board.post(seller, "Pepa", WORLD, SPOT, Material.BREAD, 5, 10);
        board.post(seller, "Pepa", WORLD, SPOT, Material.COAL, 8, 12);
        var found = board.findNearby(WORLD, SPOT, 48, buyer, o -> true).orElseThrow();
        assertEquals(Material.COAL, found.material());
    }
}
