package dev.botalive.core.physics;

import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Simulace plazení jednoblokovou štolou (experimentální {@code ai.crawling}).
 *
 * <p>Bot jde po rovině k tunelu, jehož strop je jen jeden blok nad podlahou.
 * Se zapnutým plazením ho vstupní heuristika srazí do pózy 0,6 a tunelem
 * proleze; vypnuté = hitbox 1,8 narazí do stropu a bot uvízne u ústí. Ověřuje
 * se tím obojí: proplížení i nulová regrese, když je plazení vypnuté.</p>
 */
class CrawlPhysicsTest {

    private static final int FLOOR = 63;
    private static final double FEET_Y = FLOOR + 1;

    /**
     * Rovná podlaha se stropem výšky 1 nad úsekem x=2..6 (tunel v úrovni nohou).
     * Před i za tunelem je volno na postavení.
     */
    private static FakeWorldView tunnel() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        for (int x = 2; x <= 6; x++) {
            world.set(x, (int) FEET_Y + 1, 0, FakeWorldView.SOLID); // strop jeden blok nad podlahou
        }
        return world;
    }

    @Test
    void seZapnutymPlazenimProlezeStolu() {
        FakeWorldView world = tunnel();
        BotPhysics physics = new BotPhysics(world, new Vec3(0.5, FEET_Y, 0.5));
        physics.setCrawlEnabled(true);

        MoveInput east = MoveInput.walk(new Vec3(1, 0, 0));
        boolean everCrawled = false;
        for (int i = 0; i < 400 && physics.position().x() < 7.5; i++) {
            physics.step(east);
            everCrawled |= physics.crawling();
        }

        assertTrue(everCrawled, "bot se měl v tunelu sklonit do plazivé pózy");
        assertTrue(physics.position().x() >= 7.0,
                "bot měl tunelem prolézt, x=" + physics.position().x());
        assertEquals(FEET_Y, physics.position().y(), 0.05,
                "bot má zůstat na podlaze, y=" + physics.position().y());
    }

    @Test
    void bezPlazeniUvizneUUstiTunelu() {
        FakeWorldView world = tunnel();
        BotPhysics physics = new BotPhysics(world, new Vec3(0.5, FEET_Y, 0.5));
        // Plazení vypnuté (default) – hitbox 1,8 narazí do stropu.

        MoveInput east = MoveInput.walk(new Vec3(1, 0, 0));
        for (int i = 0; i < 400; i++) {
            physics.step(east);
            assertFalse(physics.crawling(), "s vypnutým plazením se bot nikdy neskloní");
        }

        assertTrue(physics.position().x() < 2.0,
                "vestoje má bot uvíznout u ústí tunelu, x=" + physics.position().x());
    }
}
