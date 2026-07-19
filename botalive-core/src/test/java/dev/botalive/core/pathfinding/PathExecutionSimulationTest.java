package dev.botalive.core.pathfinding;

import dev.botalive.api.personality.Personality;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.physics.BotPhysics;
import dev.botalive.core.physics.FallReflex;
import dev.botalive.core.physics.LiquidReflex;
import dev.botalive.core.physics.MoveInput;
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
 * Integrační simulace spolupráce pathfindingu s kolizním systémem: cesta se
 * nejen naplánuje, ale bot ji <b>skutečně odejde</b> – tick smyčka zrcadlí
 * {@code BotImpl.tick} (navigator → LiquidReflex → FallReflex →
 * {@link BotPhysics#step}). Selhání znamená mezeru mezi tím, co plánovač
 * slibuje, a tím, co fyzika s AABB kolizemi reálně zvládne.
 *
 * <p>Každý scénář ověřuje: bot fyzicky dorazí k cíli, bez poškození z pádů,
 * nikdy nestojí v hazardu a navigace nikdy nepožádá o zásah do terénu
 * (assist = plánovač a exekuce se neshodly).</p>
 */
class PathExecutionSimulationTest {

    private static final int FLOOR = 63;
    private static final int FEET = FLOOR + 1;

    private final NavigationService service = new NavigationService(1);

    @AfterEach
    void tearDown() {
        service.shutdown();
    }

    // ------------------------------------------------------------ scénáře

    @Test
    void dojdePoRovine() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        simulate(world, at(0), new BlockPos(15, FEET, 0), 600);
    }

    @Test
    void dojdeDiagonalne() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        simulate(world, at(0), new BlockPos(10, FEET, 10), 600);
    }

    @Test
    void vyskociNaTerasu() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Terasa o blok výš od x=5 dál.
        for (int x = 5; x <= 12; x++) {
            for (int z = -4; z <= 4; z++) {
                world.set(x, FEET, z, FakeWorldView.SOLID);
            }
        }
        simulate(world, at(0), new BlockPos(9, FEET + 1, 0), 600);
    }

    @Test
    void vyjdeSchody() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Schod (stoupá na +x) před terasou o blok výš.
        for (int z = -2; z <= 2; z++) {
            world.set(4, FEET, z, FakeWorldView.STAIR_EAST);
        }
        for (int x = 5; x <= 10; x++) {
            for (int z = -2; z <= 2; z++) {
                world.set(x, FEET, z, FakeWorldView.SOLID);
            }
        }
        simulate(world, at(0), new BlockPos(8, FEET + 1, 0), 600);
    }

    @Test
    void prejdePresDesku() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        world.set(3, FEET, 0, FakeWorldView.SLAB_BOTTOM);
        simulate(world, at(0), new BlockPos(6, FEET, 0), 600);
    }

    @Test
    void seskociZTerasy() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Start na věži 3 bloky nad terénem (bezpečný seskok bez poškození).
        for (int x = -2; x <= 1; x++) {
            for (int z = -2; z <= 2; z++) {
                world.wall(x, FEET, FEET + 2, z);
            }
        }
        simulate(world, new Vec3(0.5, FEET + 3, 0.5), new BlockPos(8, FEET, 0), 600);
    }

    @Test
    void preskociJednoblokovouMezeru() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        digChasm(world, 3, 3);
        simulate(world, at(0), new BlockPos(7, FEET, 0), 800);
    }

    @Test
    void preskociDvoublokovouMezeruSprintem() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        digChasm(world, 3, 4);
        simulate(world, at(0), new BlockPos(8, FEET, 0), 800);
    }

    @Test
    void preplaveVodniPrikop() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        for (int x = 3; x <= 4; x++) {
            for (int z = -8; z <= 8; z++) {
                world.set(x, FEET, z, FakeWorldView.WATER);
                world.set(x, FLOOR, z, FakeWorldView.WATER);
            }
        }
        simulate(world, at(0), new BlockPos(7, FEET, 0), 1000);
    }

    @Test
    void vylezeZBazenu() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Bazén 5×5 hloubky 3 zapuštěný do terénu, hladina v úrovni FEET.
        for (int x = 21; x <= 25; x++) {
            for (int z = 21; z <= 25; z++) {
                for (int y = FEET - 2; y <= FEET; y++) {
                    world.set(x, y, z, FakeWorldView.WATER);
                }
            }
        }
        simulate(world, new Vec3(23.5, FEET, 23.5), new BlockPos(29, FEET, 23), 1000);
    }

    @Test
    void vyplaveZVodniJamy() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        for (int y = FEET - 4; y <= FEET; y++) {
            world.set(3, y, 0, FakeWorldView.WATER);
        }
        simulate(world, new Vec3(3.5, FEET - 4, 0.5), new BlockPos(6, FEET, 0), 1000);
    }

    @Test
    void vysplhaZebrikPresZed() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Uzavřený koridor s příčnou zdí výšky 3; jediná cesta = žebřík.
        for (int x = -1; x <= 7; x++) {
            world.wall(x, FEET, FEET + 3, -1);
            world.wall(x, FEET, FEET + 3, 1);
        }
        for (int z = -1; z <= 1; z++) {
            world.wall(-1, FEET, FEET + 3, z);
            world.wall(7, FEET, FEET + 3, z);
        }
        world.wall(3, FEET, FEET + 2, 0);
        for (int y = FEET; y <= FEET + 2; y++) {
            world.set(2, y, 0, FakeWorldView.CLIMBABLE);
        }
        simulate(world, at(0), new BlockPos(6, FEET, 0), 1200);
    }

    @Test
    void prekonaVysokySnihSkokem() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        world.set(3, FEET, 0, FakeWorldView.SNOW_SIX);
        simulate(world, at(0), new BlockPos(6, FEET, 0), 600);
    }

    @Test
    void dojdePoLedu() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        for (int x = 2; x <= 12; x++) {
            for (int z = -2; z <= 2; z++) {
                world.set(x, FLOOR, z, FakeWorldView.ICE);
            }
        }
        simulate(world, at(0), new BlockPos(14, FEET, 0), 800);
    }

    @Test
    void prebrodiSoulSand() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        for (int x = 3; x <= 6; x++) {
            for (int z = -1; z <= 1; z++) {
                world.set(x, FLOOR, z, FakeWorldView.SOUL_SAND);
            }
        }
        simulate(world, at(0), new BlockPos(9, FEET, 0), 800);
    }

    @Test
    void seskociZVyskyDoHlubokeVody() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        int towerTop = FEET + 8;
        world.wall(0, FEET, towerTop - 1, 0);
        // Bazén hloubky 2 pod útesem.
        for (int x = 1; x <= 3; x++) {
            for (int z = -1; z <= 1; z++) {
                world.set(x, FEET, z, FakeWorldView.WATER);
                world.set(x, FLOOR, z, FakeWorldView.WATER);
            }
        }
        simulate(world, new Vec3(0.5, towerTop, 0.5), new BlockPos(6, FEET, 0), 1200);
    }

    @Test
    void vysplhaVysokyZebrikNaVrchol() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Věž výšky 10 s žebříkem po boku, cíl na vrcholu – dlouhý šplh
        // (waypointy přímo nad hlavou) a mantle přes horní hranu žebříku.
        int towerTop = FEET + 9; // vrchol masivu; stojí se na FEET+10
        for (int x = 3; x <= 6; x++) {
            for (int z = -1; z <= 1; z++) {
                world.wall(x, FEET, towerTop, z);
            }
        }
        for (int y = FEET; y <= towerTop; y++) {
            world.set(2, y, 0, FakeWorldView.CLIMBABLE);
        }
        simulate(world, at(0), new BlockPos(5, towerTop + 1, 0), 2000);
    }

    @Test
    void prejdeUzkyMostPresPropast() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Propast 12 hluboká, přes ni most šířky 1 (x 3..10, z=0).
        digChasm(world, 3, 10);
        for (int x = 3; x <= 10; x++) {
            world.set(x, FLOOR, 0, FakeWorldView.SOLID);
        }
        simulate(world, at(0), new BlockPos(13, FEET, 0), 1000);
    }

    @Test
    void projdeKlikatouChodbou() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Zigzag chodba šířky 1: zdi nutí ostré rohy – smoothing nesmí řezat zdi.
        for (int x = 2; x <= 10; x += 2) {
            int gapZ = (x % 4 == 2) ? 2 : -2;
            for (int z = -3; z <= 3; z++) {
                if (z != gapZ) {
                    world.wall(x, FEET, FEET + 1, z);
                }
            }
        }
        simulate(world, at(0), new BlockPos(12, FEET, 0), 1200);
    }

    @Test
    void preskociDiagonalniRohovouMezeru() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Rohová mezera: dva ostrovy spojené jen diagonálním skokem přes díru.
        digChasm(world, 3, 20);
        for (int x = 0; x <= 3; x++) {
            for (int z = -3; z <= 3; z++) {
                world.set(x, FLOOR, z, FakeWorldView.SOLID); // startovní ostrov
            }
        }
        for (int x = 5; x <= 9; x++) {
            for (int z = 2; z <= 6; z++) {
                world.set(x, FLOOR, z, FakeWorldView.SOLID); // cílový ostrov šikmo
            }
        }
        simulate(world, new Vec3(3.5, FEET, 0.5), new BlockPos(7, FEET, 4), 1000);
    }

    @Test
    void preskociMezeruSDopademNiz() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Mezera 2 s protějším břehem o blok níž.
        digChasm(world, 3, 4);
        for (int x = 5; x <= 12; x++) {
            for (int z = -8; z <= 8; z++) {
                world.set(x, FLOOR, z, FakeWorldView.AIRLIKE); // snížený břeh
            }
        }
        simulate(world, at(0), new BlockPos(8, FEET - 1, 0), 800);
    }

    @Test
    void zastaviNaLeduPredUtesem() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Ledová dráha končí u hrany hluboké propasti – cíl je poslední blok
        // před hranou. Setrvačnost na ledu nesmí přenést bota přes okraj.
        for (int x = 2; x <= 8; x++) {
            for (int z = -2; z <= 2; z++) {
                world.set(x, FLOOR, z, FakeWorldView.ICE);
            }
        }
        digChasm(world, 9, 16);
        simulate(world, at(0), new BlockPos(8, FEET, 0), 800);
    }

    @Test
    void proplavePodvodnimTunelem() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Zatopený tunel se stropem (x 3..6): jediná cesta vede pod hladinou.
        for (int x = 2; x <= 7; x++) {
            for (int z = -8; z <= 8; z++) {
                world.wall(x, FEET, FEET + 2, z); // masiv napříč
            }
        }
        for (int x = 2; x <= 7; x++) {
            world.set(x, FEET, 0, FakeWorldView.WATER); // zatopená trubka
        }
        simulate(world, at(0), new BlockPos(9, FEET, 0), 1200);
    }

    @Test
    void sejdeSchodovityTerenDolu() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Kaskáda teras dolů: každé 2 bloky o 1 níž (celkem −4).
        for (int step = 1; step <= 4; step++) {
            for (int x = 2 + step * 2; x <= 20; x++) {
                for (int z = -4; z <= 4; z++) {
                    world.set(x, FLOOR - step + 1, z, FakeWorldView.AIRLIKE);
                }
            }
        }
        simulate(world, at(0), new BlockPos(13, FEET - 4, 0), 800);
    }

    @Test
    void potopiSeNaDnoVodniJamy() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Cíl na dně vodní jámy hloubky 4 – naplánovaný sestup vodou musí
        // exekuce umět (pustit skok a nechat se potopit), jinak bot věčně
        // šlape vodu u hladiny.
        for (int y = FEET - 4; y <= FEET; y++) {
            world.set(3, y, 0, FakeWorldView.WATER);
        }
        simulate(world, at(0), new BlockPos(3, FEET - 4, 0), 1200);
    }

    @Test
    void sestoupiZebrikemDoSachty() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Šachta 1×1 hloubky 6 s žebříkem – naplánovaný sestup po žebříku.
        for (int y = FEET - 6; y <= FLOOR; y++) {
            world.set(3, y, 0, FakeWorldView.CLIMBABLE);
        }
        // Vyhloubit šachtu (žebřík nahrazuje sloupec podlahy, stěny zůstávají).
        simulate(world, at(0), new BlockPos(3, FEET - 6, 0), 1500);
    }

    @Test
    void otevreDvereVeZdiAProjde() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Zeď napříč s dveřmi (2 bloky vysoké) na z=0 – jediný průchod.
        for (int z = -4; z <= 4; z++) {
            if (z != 0) {
                world.wall(3, FEET, FEET + 1, z);
            }
        }
        world.set(3, FEET, 0, FakeWorldView.DOOR_CLOSED);
        world.set(3, FEET + 1, 0, FakeWorldView.DOOR_CLOSED);
        // „Server": klik navigátoru dveře skutečně přepne (obě poloviny).
        DoorOpener opener = door -> {
            world.set(door.x(), door.y(), door.z(), FakeWorldView.DOOR_OPEN);
            world.set(door.x(), door.y() + 1, door.z(), FakeWorldView.DOOR_OPEN);
        };
        simulate(world, at(0), new BlockPos(6, FEET, 0), 600, opener);
    }

    @Test
    void projdeNizkymTunelem() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Tunel vysoký 2 (strop ve FEET+2): skoky jsou zakázané, chůze musí stačit.
        for (int x = 2; x <= 10; x++) {
            for (int z = -2; z <= 2; z++) {
                world.set(x, FEET + 2, z, FakeWorldView.SOLID);
            }
        }
        simulate(world, at(0), new BlockPos(12, FEET, 0), 800);
    }

    @Test
    void vyjdeDeskoveSchodiste() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Půlbloky střídané s plnými: stoupání po 0,5 – čistý step-up bez skoků.
        for (int z = -2; z <= 2; z++) {
            world.set(3, FEET, z, FakeWorldView.SLAB_BOTTOM);
            world.set(4, FEET, z, FakeWorldView.SOLID);
            world.set(5, FEET + 1, z, FakeWorldView.SLAB_BOTTOM);
            world.set(5, FEET, z, FakeWorldView.SOLID);
        }
        for (int x = 6; x <= 10; x++) {
            for (int z = -2; z <= 2; z++) {
                world.wall(x, FEET, FEET + 1, z);
            }
        }
        simulate(world, at(0), new BlockPos(8, FEET + 2, 0), 800);
    }

    @Test
    void zedPostavenaUprostredChuzeVynutiObchazku() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        Navigator navigator = new Navigator(service, null, new BotRandom(7), personality());
        navigator.world(world);
        BotPhysics physics = new BotPhysics(world, at(0));
        BlockPos goal = new BlockPos(14, FEET, 0);
        navigator.navigateTo(at(0), goal);

        boolean built = false;
        for (int tick = 0; tick < 1200; tick++) {
            if (navigator.navigating() && !navigator.hasPath()) {
                sleep(1);
            }
            // V půlce cesty vyroste zeď přes zbytek trasy (výška 2, s dírou
            // daleko od osy) – validace ji musí zachytit a replán obejít.
            if (!built && physics.position().x() > 7) {
                for (int z = -6; z <= 6; z++) {
                    if (z != 6) {
                        world.wall(10, FEET, FEET + 1, z);
                    }
                }
                built = true;
            }
            MoveInput input = navigator.tick(physics.position(), physics.onGround(), physics.inWater());
            input = LiquidReflex.apply(input, navigator.hasPath(), physics.position(),
                    physics.submergedTicks(), world);
            input = FallReflex.apply(input, navigator.hasPath(), physics.onGround(),
                    physics.fallDistance(), physics.position(), world);
            physics.step(input);
            assertTrue(!navigator.needsAssist(), "obchůzka existuje – assist je selhání replánu");
            if (arrived(physics.position(), goal)) {
                assertTrue(built, "zeď se měla stihnout postavit");
                return;
            }
        }
        throw new AssertionError("bot se přes postavenou zeď nedostal obchůzkou; skončil na "
                + physics.position());
    }

    @Test
    void dojdeDalekoPresSegmenty() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Cíl za FAR_THRESHOLD (96): trasa se dělí na segmenty po vzdušné
        // čáře – fyzicky se musí projít celý řetěz mezicílů.
        simulate(world, at(0), new BlockPos(140, FEET, 8), 3000);
    }

    @Test
    void rozbehNaLeduPresMezeru() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Ledový rozběh před dvoublokovou mezerou: kluzká akcelerace nesmí
        // rozbít odraz na hraně ani dolet.
        for (int x = 0; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                world.set(x, FLOOR, z, FakeWorldView.ICE);
            }
        }
        digChasm(world, 3, 4);
        simulate(world, at(0), new BlockPos(8, FEET, 0), 1000);
    }

    @Test
    void obejdeLavovePoleKoridorem() {
        FakeWorldView world = lavaFieldWorld();
        Navigator navigator = new Navigator(service, null, new BotRandom(7), personality());
        navigator.world(world);
        BotPhysics physics = new BotPhysics(world, at(0));
        BlockPos goal = new BlockPos(100, FEET, 0);
        navigator.navigateTo(at(0), goal);

        boolean sawCorridor = false;
        for (int tick = 0; tick < 5000; tick++) {
            if (navigator.navigating() && !navigator.hasPath()) {
                sleep(1);
            }
            MoveInput input = navigator.tick(physics.position(), physics.onGround(), physics.inWater());
            input = LiquidReflex.apply(input, navigator.hasPath(), physics.position(),
                    physics.submergedTicks(), world);
            input = FallReflex.apply(input, navigator.hasPath(), physics.onGround(),
                    physics.fallDistance(), physics.position(), world);
            physics.step(input);
            sawCorridor |= navigator.debugSnapshot().corridorCount() > 0;
            BlockPos feet = physics.position().toBlockPos();
            assertTrue(!world.traitsAt(feet).hazard(),
                    "bot vkročil do lávy na " + feet + " (tick " + tick + ")");
            assertTrue(!navigator.needsAssist(),
                    "s koridorem se obchůzka najde – assist je selhání (tick " + tick
                            + ", pozice " + physics.position() + ")");
            if (arrived(physics.position(), goal)) {
                assertTrue(sawCorridor, "dálková trasa měla použít hrubý koridor");
                return;
            }
        }
        throw new AssertionError("bot lávové pole neobešel; skončil na " + physics.position()
                + ", koridor=" + navigator.debugSnapshot().corridorCount());
    }

    /** Lávové pole napříč trasou (x 20..60, z −100..24); obchůzka jen severně. */
    private static FakeWorldView lavaFieldWorld() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        for (int x = 20; x <= 60; x++) {
            for (int z = -100; z <= 24; z++) {
                world.set(x, FEET, z, FakeWorldView.HAZARD);
                world.set(x, FLOOR, z, FakeWorldView.HAZARD);
            }
        }
        return world;
    }

    @Test
    void dojdeDoOkruhuNearCile() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        BlockPos center = new BlockPos(15, FEET, 0);
        Navigator navigator = new Navigator(service, null, new BotRandom(7), personality());
        navigator.world(world);
        BotPhysics physics = new BotPhysics(world, at(0));
        navigator.navigateTo(at(0), PathGoal.near(center, 3));

        for (int tick = 0; tick < 600; tick++) {
            if (navigator.navigating() && !navigator.hasPath()) {
                sleep(1);
            }
            MoveInput input = navigator.tick(physics.position(), physics.onGround(), physics.inWater());
            physics.step(input);
            if (!navigator.navigating()) {
                double dx = physics.position().x() - (center.x() + 0.5);
                double dz = physics.position().z() - (center.z() + 0.5);
                double dist = Math.sqrt(dx * dx + dz * dz);
                assertTrue(dist <= 3.8, "bot má skončit v okruhu 3 od středu, je " + dist);
                assertTrue(physics.position().x() > 8, "bot se měl vydat ke středu");
                return;
            }
        }
        throw new AssertionError("navigace do okruhu neskončila; bot na " + physics.position());
    }

    @Test
    void planovanyUtekObejdeLavu() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Láva východně od bota – panický přímý běh by do ní mohl vést,
        // plánovaný útěk (PathGoal.awayFrom) ji obejde po pochozím terénu.
        for (int x = 5; x <= 40; x++) {
            for (int z = -20; z <= 20; z++) {
                world.set(x, FEET, z, FakeWorldView.HAZARD);
                world.set(x, FLOOR, z, FakeWorldView.HAZARD);
            }
        }
        BlockPos threat = new BlockPos(0, FEET, 0);
        Navigator navigator = new Navigator(service, null, new BotRandom(7), personality());
        navigator.world(world);
        Vec3 start = new Vec3(2.5, FEET, 0.5);
        BotPhysics physics = new BotPhysics(world, start);
        navigator.navigateTo(start, PathGoal.awayFrom(threat, 12));

        for (int tick = 0; tick < 800; tick++) {
            if (navigator.navigating() && !navigator.hasPath()) {
                sleep(1);
            }
            MoveInput input = navigator.tick(physics.position(), physics.onGround(), physics.inWater());
            input = LiquidReflex.apply(input, navigator.hasPath(), physics.position(),
                    physics.submergedTicks(), world);
            input = FallReflex.apply(input, navigator.hasPath(), physics.onGround(),
                    physics.fallDistance(), physics.position(), world);
            physics.step(input);
            BlockPos feet = physics.position().toBlockPos();
            assertTrue(!world.traitsAt(feet).hazard(),
                    "útěk vkročil do lávy na " + feet + " (tick " + tick + ")");
            double dx = physics.position().x() - (threat.x() + 0.5);
            double dz = physics.position().z() - (threat.z() + 0.5);
            if (dx * dx + dz * dz >= 144 && !navigator.navigating()) {
                return; // v bezpečné vzdálenosti a navigace korektně skončila
            }
        }
        throw new AssertionError("bot neutekl na 12 bloků od hrozby; skončil na "
                + physics.position() + ", naviguje=" + navigator.navigating());
    }

    @Test
    void dvaBotiSeVyhnouVProtismeruNaChodbe() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Chodba šířky 2 (z 0..1) – boti jdoucí proti sobě se musí vyhnout
        // úkrokem (CrowdAvoidance), ne se věčně přetlačovat.
        for (int x = -2; x <= 16; x++) {
            world.wall(x, FEET, FEET + 1, -1);
            world.wall(x, FEET, FEET + 1, 2);
        }
        simulateTwoBots(world,
                new Vec3(0.5, FEET, 0.5), new BlockPos(14, FEET, 1),
                new Vec3(14.5, FEET, 1.5), new BlockPos(0, FEET, 0),
                1500);
    }

    @Test
    void dvaBotiSeVyhnouCelneNaVolnemPoli() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Přesně čelní střet na jedné přímce – deterministický úkrok podle id.
        simulateTwoBots(world,
                new Vec3(0.5, FEET, 0.5), new BlockPos(20, FEET, 0),
                new Vec3(20.5, FEET, 0.5), new BlockPos(0, FEET, 0),
                1500);
    }

    /**
     * Dva boti ve sdíleném světě, každý vidí druhého jako entitu davu –
     * tick zrcadlí {@code BotImpl}: navigace → CrowdAvoidance → reflexy →
     * fyzika. Oba musí dorazit; deadlock z přetlačování = selhání.
     */
    private void simulateTwoBots(FakeWorldView world, Vec3 startA, BlockPos goalA,
                                 Vec3 startB, BlockPos goalB, int maxTicks) {
        Navigator navA = new Navigator(service, null, new BotRandom(7), personality());
        Navigator navB = new Navigator(service, null, new BotRandom(13), personality());
        navA.world(world);
        navB.world(world);
        BotPhysics physA = new BotPhysics(world, startA);
        BotPhysics physB = new BotPhysics(world, startB);
        var type = org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType.PLAYER;
        var trackedA = new dev.botalive.core.entity.TrackedEntity(
                1, java.util.UUID.randomUUID(), type, startA);
        var trackedB = new dev.botalive.core.entity.TrackedEntity(
                2, java.util.UUID.randomUUID(), type, startB);
        navA.navigateTo(startA, goalA);
        navB.navigateTo(startB, goalB);

        boolean doneA = false;
        boolean doneB = false;
        for (int tick = 0; tick < maxTicks && !(doneA && doneB); tick++) {
            if ((navA.navigating() && !navA.hasPath()) || (navB.navigating() && !navB.hasPath())) {
                sleep(1);
            }
            trackedA.setPosition(physA.position(), 0, 0);
            trackedB.setPosition(physB.position(), 0, 0);
            if (!doneA) {
                stepBot(world, navA, physA, 1, trackedB);
                doneA = arrived(physA.position(), goalA);
            }
            if (!doneB) {
                stepBot(world, navB, physB, 2, trackedA);
                doneB = arrived(physB.position(), goalB);
            }
        }
        assertTrue(doneA, "bot A nedorazil; skončil na " + physA.position());
        assertTrue(doneB, "bot B nedorazil; skončil na " + physB.position());
    }

    /** Jeden tick jednoho bota v davové simulaci (zrcadlí BotImpl.tick). */
    private static void stepBot(FakeWorldView world, Navigator navigator, BotPhysics physics,
                                int selfId, dev.botalive.core.entity.TrackedEntity neighbor) {
        MoveInput input = navigator.tick(physics.position(), physics.onGround(), physics.inWater());
        Vec3 steered = dev.botalive.core.physics.CrowdAvoidance.steer(
                physics.position(), selfId, java.util.List.of(neighbor), input.direction());
        if (!steered.equals(input.direction())) {
            input = new MoveInput(steered, input.sprint(), input.jump(), input.sneak());
        }
        input = LiquidReflex.apply(input, navigator.hasPath(), physics.position(),
                physics.submergedTicks(), world);
        input = FallReflex.apply(input, navigator.hasPath(), physics.onGround(),
                physics.fallDistance(), physics.position(), world);
        physics.step(input);
    }

    @Test
    void nahodnyTerenJeVzdyPruchozi() {
        // Property test: deterministicky „rozbitý" terén (hrboly, jámy, zídky,
        // desky, ploty, louže, sníh) – když plánovač slíbí kompletní cestu,
        // fyzika ji MUSÍ umět odejít. Každý seed je samostatná krajina.
        int walkable = 0;
        for (long seed = 1; seed <= 20; seed++) {
            FakeWorldView world = new FakeWorldView(FLOOR);
            java.util.Random rng = new java.util.Random(seed);
            for (int x = 2; x <= 24; x++) {
                for (int z = -6; z <= 6; z++) {
                    switch (rng.nextInt(14)) {
                        case 0 -> world.set(x, FEET, z, FakeWorldView.SOLID); // hrbol +1
                        case 1 -> world.set(x, FLOOR, z, FakeWorldView.AIRLIKE); // prohlubeň −1
                        case 2 -> world.wall(x, FEET, FEET + 1, z); // zídka výšky 2
                        case 3 -> world.set(x, FEET, z, FakeWorldView.SLAB_BOTTOM); // deska
                        case 4 -> world.set(x, FEET, z, FakeWorldView.FENCE); // plot
                        case 5 -> world.set(x, FEET, z, FakeWorldView.WATER); // louže
                        case 6 -> world.set(x, FEET, z, FakeWorldView.SNOW_SIX); // vysoký sníh
                        case 7 -> { // jáma hloubky 2 (ven jen oklikou)
                            world.set(x, FLOOR, z, FakeWorldView.AIRLIKE);
                            world.set(x, FLOOR - 1, z, FakeWorldView.AIRLIKE);
                        }
                        default -> {
                        }
                    }
                }
            }
            BlockPos goal = new BlockPos(26, FEET, 0);
            Path planned = new AStarPathfinder(world)
                    .findPath(new BlockPos(0, FEET, 0), goal, 0);
            if (!planned.complete()) {
                continue; // krajina bez cesty – nic ke sladění
            }
            walkable++;
            try {
                simulate(world, at(0), goal, 1500);
            } catch (AssertionError e) {
                throw new AssertionError("seed " + seed + ": " + e.getMessage(), e);
            }
        }
        assertTrue(walkable >= 10, "příliš málo průchozích krajin (" + walkable
                + "/20) – scénář degeneroval");
    }

    // ------------------------------------------------------------ harness

    /** Startovní pozice na rovné podlaze v ose z=0. */
    private static Vec3 at(int x) {
        return new Vec3(x + 0.5, FEET, 0.5);
    }

    /**
     * Vyhloubí propast přes celou šíři (x od {@code fromX} do {@code toX}
     * včetně, z −8..8, do hloubky 12) – seskok je zakázaný, zbývá skok.
     */
    private static void digChasm(FakeWorldView world, int fromX, int toX) {
        for (int x = fromX; x <= toX; x++) {
            for (int z = -8; z <= 8; z++) {
                for (int y = FLOOR - 12; y <= FLOOR; y++) {
                    world.set(x, y, z, FakeWorldView.AIRLIKE);
                }
            }
        }
    }

    /**
     * Odsimuluje navigaci k cíli. Selže, když bot nedorazí do {@code maxTicks},
     * utrpí poškození z pádu, ocitne se v hazardu, nebo navigace eskaluje
     * k zásahu do terénu (plánovač a fyzika se neshodly).
     */
    private void simulate(FakeWorldView world, Vec3 start, BlockPos goal, int maxTicks) {
        simulate(world, start, goal, maxTicks, null);
    }

    /** Varianta s obsluhou dveří (simulace „serveru", který klik provede). */
    private void simulate(FakeWorldView world, Vec3 start, BlockPos goal, int maxTicks,
                          DoorOpener opener) {
        Navigator navigator = new Navigator(service, opener, new BotRandom(7), personality());
        navigator.world(world);
        BotPhysics physics = new BotPhysics(world, start);
        navigator.navigateTo(start, goal);

        int fallDamage = 0;
        for (int tick = 0; tick < maxTicks; tick++) {
            // Asynchronní výpočet: krátce počkat, ať tick smyčka nemele naprázdno.
            if (navigator.navigating() && !navigator.hasPath()) {
                sleep(1);
            }
            MoveInput input = navigator.tick(physics.position(), physics.onGround(), physics.inWater());
            boolean navDriven = navigator.hasPath();
            input = LiquidReflex.apply(input, navDriven, physics.position(),
                    physics.submergedTicks(), world);
            input = FallReflex.apply(input, navDriven, physics.onGround(),
                    physics.fallDistance(), physics.position(), world);
            physics.step(input);

            if (physics.landedThisTick()) {
                fallDamage += physics.lastFallDamage();
            }
            BlockPos feet = physics.position().toBlockPos();
            assertTrue(!world.traitsAt(feet).hazard(),
                    "bot vkročil do hazardu na " + feet + " (tick " + tick + ")");
            assertTrue(!navigator.needsAssist(),
                    "navigace eskalovala k zásahu do terénu – plánovač a fyzika se neshodly "
                            + "(tick " + tick + ", pozice " + physics.position() + ")");

            if (arrived(physics.position(), goal)) {
                assertEquals(0, fallDamage, "cesta neměla bolet (poškození z pádů)");
                return;
            }
        }
        throw new AssertionError("bot nedorazil k cíli " + goal + " do " + maxTicks
                + " ticků; skončil na " + physics.position()
                + " (waypoint " + navigator.debugSnapshot().waypointIndex()
                + "/" + navigator.debugSnapshot().waypointCount()
                + ", počítá=" + navigator.debugSnapshot().computing() + ")");
    }

    /** Dorazil: nohy v okruhu 0,9 bloku od středu cílové buňky (±1,1 na výšku). */
    private static boolean arrived(Vec3 position, BlockPos goal) {
        double dx = position.x() - (goal.x() + 0.5);
        double dz = position.z() - (goal.z() + 0.5);
        return dx * dx + dz * dz < 0.9 * 0.9 && Math.abs(position.y() - goal.y()) < 1.1;
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
                return "sim";
            }
        };
    }
}
