package dev.botalive.core.bot;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.bot.BotControl;
import dev.botalive.api.bot.NearbyEntity;
import dev.botalive.api.bot.Position;
import dev.botalive.api.personality.Personality;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.entity.EntityTracker;
import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.human.Humanizer;
import dev.botalive.core.network.BotClientState;
import dev.botalive.core.pathfinding.NavigationService;
import dev.botalive.core.pathfinding.Navigator;
import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.BotRandom;
import dev.botalive.core.util.Vec3;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;
import org.junit.jupiter.api.Test;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Ověřuje, že veřejné {@link BotControl} věrně deleguje na subsystémy bota –
 * tedy že cizí plugin (který dostává jen {@link Bot}) může přes
 * {@link Bot#control()} bota skutečně řídit: vnímat svět a entity, navigovat
 * a mluvit, aniž by sahal na implementační třídy.
 *
 * <p>Kontext bota je poskládaný z reálných subsystémů (navigace, tracker entit,
 * pohled na svět, protokolový stav) přes {@link Proxy} – testuje se tak skutečné
 * chování {@link BotControlImpl}, ne atrapa.</p>
 */
class BotControlTest {

    private static final Personality FLAT = new Personality() {
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
            return 0L;
        }

        @Override
        public String archetype() {
            return "test";
        }
    };

    /** Poslední věta, kterou bot „řekl" (zachytává ji Bot proxy). */
    private final String[] lastSaid = new String[1];

    private BotControl newControl(Vec3 position, FakeWorldView world,
                                  EntityTracker entities, Navigator navigator,
                                  BotClientState clientState) {
        BotRandom rng = new BotRandom(1);
        Humanizer humanizer = new Humanizer(rng, FLAT);
        dev.botalive.core.bot.ServerSideView serverView =
                new dev.botalive.core.bot.ServerSideView(UUID.randomUUID(), null);

        Bot botDouble = (Bot) Proxy.newProxyInstance(
                Bot.class.getClassLoader(), new Class[]{Bot.class}, (p, m, a) -> {
                    if (m.getName().equals("say")) {
                        lastSaid[0] = (String) a[0];
                        return null;
                    }
                    return objectDefault(p, m, a);
                });

        InvocationHandler handler = (p, m, a) -> switch (m.getName()) {
            case "bot" -> botDouble;
            case "position" -> position;
            case "onGround" -> Boolean.TRUE;
            case "worldTime" -> 6000L;
            case "raining" -> Boolean.FALSE;
            case "thundering" -> Boolean.FALSE;
            case "clientState" -> clientState;
            case "worldView" -> world;
            case "entities" -> entities;
            case "navigator" -> navigator;
            case "humanizer" -> humanizer;
            case "serverView" -> serverView;
            default -> {
                Object obj = objectDefault(p, m, a);
                if (obj != NO_OBJECT_METHOD) {
                    yield obj;
                }
                throw new UnsupportedOperationException(m.getName());
            }
        };
        BotContext ctx = (BotContext) Proxy.newProxyInstance(
                BotContext.class.getClassLoader(), new Class[]{BotContext.class}, handler);
        return new BotControlImpl(ctx);
    }

    private static final Object NO_OBJECT_METHOD = new Object();

    private static Object objectDefault(Object proxy, Method m, Object[] a) {
        return switch (m.getName()) {
            case "toString" -> "stub";
            case "hashCode" -> System.identityHashCode(proxy);
            case "equals" -> proxy == a[0];
            default -> NO_OBJECT_METHOD;
        };
    }

    @Test
    void perceivesPositionVitalsAndWeather() {
        BotClientState state = new BotClientState();
        state.updateVitals(15f, 18, 5f);
        BotControl control = newControl(new Vec3(1.5, 65, -2.5),
                new FakeWorldView(64), new EntityTracker(), navigator(new FakeWorldView(64)), state);

        Position pos = control.position();
        assertEquals(1.5, pos.x(), 1e-9);
        assertEquals(65, pos.y(), 1e-9);
        assertEquals(-2.5, pos.z(), 1e-9);
        assertEquals(15.0, control.health(), 1e-6);
        assertEquals(18, control.food());
        assertTrue(control.onGround());
        assertEquals(6000L, control.worldTime());
        assertFalse(control.raining());
        assertFalse(control.thundering());
        assertEquals("fake", control.worldName());
    }

    @Test
    void readsBlocksFromWorldView() {
        FakeWorldView world = new FakeWorldView(64);
        world.set(2, 65, 2, FakeWorldView.WATER);
        BotControl control = newControl(new Vec3(0, 65, 0), world,
                new EntityTracker(), navigator(world), new BotClientState());

        // Podlaha na y<=64 je pevná (STONE), nad ní vzduch.
        assertEquals("STONE", control.blockAt(0, 64, 0));
        assertTrue(control.isSolid(0, 64, 0));
        assertEquals("AIR", control.blockAt(0, 70, 0));
        assertFalse(control.isSolid(0, 70, 0));
        assertTrue(control.isPassable(0, 70, 0));
        assertTrue(control.isLiquid(2, 65, 2));
    }

    @Test
    void listsNearbyEntities() {
        EntityTracker tracker = new EntityTracker();
        UUID zombieId = UUID.randomUUID();
        tracker.add(new TrackedEntity(42, zombieId, EntityType.ZOMBIE, new Vec3(3, 65, 0)));
        BotControl control = newControl(new Vec3(0, 65, 0), new FakeWorldView(64),
                tracker, navigator(new FakeWorldView(64)), new BotClientState());

        List<NearbyEntity> near = control.nearbyEntities(10);
        assertEquals(1, near.size());
        NearbyEntity zombie = near.get(0);
        assertEquals(42, zombie.id());
        assertEquals(zombieId, zombie.uuid());
        assertEquals("ZOMBIE", zombie.type());
        assertTrue(zombie.hostile());
        assertFalse(zombie.player());
        assertEquals(3.0, zombie.position().distanceTo(new Position(0, 65, 0)), 1e-6);

        // Mimo poloměr se entita nevrací.
        assertTrue(control.nearbyEntities(1).isEmpty());
    }

    @Test
    void navigationIsIntentDriven() {
        FakeWorldView world = new FakeWorldView(64);
        Navigator navigator = navigator(world);
        BotControl control = newControl(new Vec3(0, 65, 0), world,
                new EntityTracker(), navigator, new BotClientState());

        assertFalse(control.navigating());
        control.navigateTo(5, 65, 5);
        assertTrue(control.navigating(), "po navigateTo bot směřuje k cíli");
        control.stopNavigation();
        assertFalse(control.navigating(), "stopNavigation navigaci zruší");
    }

    @Test
    void hasItemIsNullSafeWithoutSnapshot() {
        BotControl control = newControl(new Vec3(0, 65, 0), new FakeWorldView(64),
                new EntityTracker(), navigator(new FakeWorldView(64)), new BotClientState());

        // Bez snapshotu inventáře i pro neznámý materiál je odpověď false;
        // požadavek na 0 kusů je triviálně splněn.
        assertFalse(control.hasItem("STONE", 1));
        assertFalse(control.hasItem("naprosto_neexistujici_material", 1));
        assertTrue(control.hasItem("STONE", 0));
    }

    @Test
    void walkToTaskDrivesNavigationAndFactoriesReturnTasks() {
        FakeWorldView world = new FakeWorldView(64);
        Navigator navigator = navigator(world);
        BotControl control = newControl(new Vec3(0, 65, 0), world,
                new EntityTracker(), navigator, new BotClientState());

        dev.botalive.api.task.BotTask walk = control.walkTo(5, 65, 5);
        assertFalse(control.navigating());
        assertFalse(walk.tick(control), "první tick spustí navigaci, ještě není hotovo");
        assertTrue(control.navigating());
        walk.cancel(control);
        assertFalse(control.navigating(), "cancel navigaci zastaví");

        // Vestavěná primitiva vrací připravené tasky (bez ticku – ten už
        // potřebuje síť; delegáti MineBlockTask/PlaceBlockTask jsou pokryté jinde).
        assertNotNull(control.mineBlock(0, 64, 0));
        assertNotNull(control.placeBlock(1, 65, 1, "COBBLESTONE"));
    }

    @Test
    void sayRoutesThroughBot() {
        BotControl control = newControl(new Vec3(0, 65, 0), new FakeWorldView(64),
                new EntityTracker(), navigator(new FakeWorldView(64)), new BotClientState());
        assertNotNull(control.bot());

        control.say("ahoj světe");
        assertEquals("ahoj světe", lastSaid[0]);
    }

    private Navigator navigator(FakeWorldView world) {
        Navigator navigator = new Navigator(new NavigationService(1), null, new BotRandom(1), FLAT);
        navigator.world(world);
        return navigator;
    }
}
