package dev.botalive.core.pathfinding;

import dev.botalive.api.personality.Personality;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.BotRandom;
import dev.botalive.core.util.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy replanningu Navigatoru: throttle pohyblivého cíle a validace cesty
 * proti změnám světa. Výpočty běží na skutečném poolu (1 vlákno), na výsledek
 * se čeká s deadlinem – počty výpočtů se čtou z {@link PathfindingStats}.
 */
class NavigatorTest {

    private static final int FLOOR = 63;
    private static final int FEET = FLOOR + 1;
    private static final Vec3 START = new Vec3(0.5, FEET, 0.5);

    private final NavigationService service = new NavigationService(1);

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    @Test
    void pohyblivyCilNezahazujeRozpracovanouCestu() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        Navigator navigator = newNavigator(world);

        navigator.navigateTo(START, new BlockPos(20, FEET, 0));
        awaitPath(navigator);
        long planned = requests();
        assertEquals(1, planned, "první cíl = jeden výpočet");

        // Drift o 1 blok: cesta končí u cíle, žádný replán.
        navigator.navigateTo(START, new BlockPos(21, FEET, 0));
        assertEquals(planned, requests(), "malý posun cíle nemá spouštět replán");
        assertEquals(new BlockPos(21, FEET, 0), navigator.destination(),
                "nový cíl se má zapamatovat");

        // Drift o 6 bloků: plný replán (cooldown byl volný).
        navigator.navigateTo(START, new BlockPos(26, FEET, 0));
        awaitRequests(planned + 1);

        // Další drift hned poté: cooldown běží, replán se odloží.
        navigator.navigateTo(START, new BlockPos(29, FEET, 0));
        sleep(60);
        assertEquals(planned + 1, requests(),
                "drift v cooldownu nemá spouštět další výpočet");
        assertEquals(new BlockPos(29, FEET, 0), navigator.destination());
    }

    @Test
    void rozbitaCestaSeReplanujeBezZaseknuti() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        Navigator navigator = newNavigator(world);

        navigator.navigateTo(START, new BlockPos(12, FEET, 0));
        awaitPath(navigator);
        long planned = requests();

        // Postavit zeď na waypoint kousek před botem.
        var snapshot = navigator.debugSnapshot();
        assertTrue(snapshot.upcoming().size() >= 3, "cesta má mít waypointy: " + snapshot);
        BlockPos blocked = snapshot.upcoming().get(2);
        world.set(blocked.x(), blocked.y(), blocked.z(), FakeWorldView.SOLID);
        world.set(blocked.x(), blocked.y() + 1, blocked.z(), FakeWorldView.SOLID);

        // Validace běží každých ~10 ticků – replán přijde bez čekání na
        // detekci zaseknutí (50 ticků).
        for (int i = 0; i < 15 && requests() == planned; i++) {
            navigator.tick(START, true, false);
        }
        awaitRequests(planned + 1);
    }

    // ------------------------------------------------------------- pomocníci

    private Navigator newNavigator(FakeWorldView world) {
        Navigator navigator = new Navigator(service, null, new BotRandom(42), personality());
        navigator.world(world);
        return navigator;
    }

    /** Tickuje navigátor, dokud si nevyzvedne dopočítanou cestu. */
    private void awaitPath(Navigator navigator) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            navigator.tick(START, true, false);
            if (navigator.hasPath()) {
                return;
            }
            sleep(5);
        }
        throw new AssertionError("cesta se nedopočítala do 5 s");
    }

    /** Počká, až metriky ukážou daný počet výpočtů (výpočty běží asynchronně). */
    private void awaitRequests(long expected) {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (requests() >= expected) {
                assertEquals(expected, requests(), "víc výpočtů, než se čekalo");
                return;
            }
            sleep(5);
        }
        throw new AssertionError("čekáno na " + expected + " výpočtů, je " + requests());
    }

    private long requests() {
        return service.stats().snapshot().requests();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static Personality personality() {
        return new Personality() {
            @Override
            public double trait(Trait trait) {
                return 0.5;
            }

            @Override
            public Map<Trait, Double> traits() {
                return Map.of();
            }

            @Override
            public long seed() {
                return 1;
            }

            @Override
            public String archetype() {
                return "test";
            }
        };
    }
}
