package dev.botalive.core.tasks;

import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Testy geometrie pokládky žebříku.
 *
 * <p>Žebřík se přichytává na svislou stěnu – klíčové je zvolit správnou stranu
 * bloku (tu přivrácenou k botovi), jinak server pokládku odmítne nebo žebřík
 * míří opačně. Na rozdíl od {@link PlaceBlockTask} se nikdy neopírá o podlahu.</p>
 */
class LadderTaskTest {

    @Test
    void stenaNaVychodSeLepiZapadniStranou() {
        // Bot jde na +X (stěna je na východ) → klik na její západní stranu.
        assertEquals(Direction.WEST, LadderTask.faceTowardBot(1, 0));
    }

    @Test
    void stenaNaZapadSeLepiVychodniStranou() {
        assertEquals(Direction.EAST, LadderTask.faceTowardBot(-1, 0));
    }

    @Test
    void stenaNaJihSeLepiSevverniStranou() {
        // +Z je jih; přivrácená (severní) strana míří k botovi.
        assertEquals(Direction.NORTH, LadderTask.faceTowardBot(0, 1));
    }

    @Test
    void stenaNaSeverSeLepiJizniStranou() {
        assertEquals(Direction.SOUTH, LadderTask.faceTowardBot(0, -1));
    }
}
