package dev.botalive.core.pathfinding;

import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.BlockPos;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy A* pathfinderu nad syntetickým světem.
 */
class AStarPathfinderTest {

    private static final int FLOOR = 63;
    private static final int FEET = FLOOR + 1;

    @Test
    void najdeCestuPoRovnePodlaze() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        Path path = new AStarPathfinder(world)
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(6, FEET, 6), 0);

        assertTrue(path.complete(), "cesta má dosáhnout cíle");
        assertFalse(path.isEmpty());
        // Všechny waypointy musí být pochozí (pevná podlaha pod nohama).
        for (BlockPos waypoint : path.waypoints()) {
            assertTrue(world.traitsAt(waypoint.down()).solid()
                            || world.traitsAt(waypoint).liquid(),
                    "waypoint " + waypoint + " není pochozí");
        }
    }

    @Test
    void vyhybaSeMistuSmrti() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        BlockPos danger = new BlockPos(3, FEET, 0); // tady bot minule zemřel
        Path avoided = new AStarPathfinder(world, java.util.List.of(danger))
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(8, FEET, 0), 0);

        assertTrue(avoided.complete(), "cesta má dorazit i s obchůzkou");
        // Obchůzka: žádný waypoint v těsné blízkosti místa smrti.
        boolean tooClose = avoided.waypoints().stream()
                .anyMatch(p -> p.distanceSquared(danger) <= 2 * 2);
        assertFalse(tooClose, "cesta má místo smrti obejít: " + avoided.waypoints());
        // Bez vzpomínky vede přímo (kontrola, že penalizace je příčinou obchůzky).
        Path direct = new AStarPathfinder(world)
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(8, FEET, 0), 0);
        assertTrue(direct.waypoints().stream().anyMatch(p -> p.distanceSquared(danger) <= 1),
                "bez vzpomínky se čekala přímá cesta přes dané místo");
    }

    @Test
    void prekonaZedVyskyJedna() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Zeď výšky 1 napříč cestou (x=3, celý rozsah z).
        for (int z = -8; z <= 8; z++) {
            world.set(3, FEET, z, FakeWorldView.SOLID);
        }
        Path path = new AStarPathfinder(world)
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(6, FEET, 0), 0);

        assertTrue(path.complete(), "zeď výšky 1 se dá přeskočit");
        // Cesta musí obsahovat výstup na zeď (y = FEET+1).
        assertTrue(path.waypoints().stream().anyMatch(p -> p.y() == FEET + 1),
                "očekáván skok na zeď");
    }

    @Test
    void vyhneSeLave() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Pruh lávy (x=3, z −2..2) – vlevo zůstává obchozí koridor.
        for (int z = -2; z <= 2; z++) {
            world.set(3, FEET, z, FakeWorldView.HAZARD);
            world.set(3, FLOOR, z, FakeWorldView.HAZARD);
        }
        Path path = new AStarPathfinder(world)
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(6, FEET, 0), 0);

        assertTrue(path.complete());
        for (BlockPos waypoint : path.waypoints()) {
            assertFalse(world.traitsAt(waypoint).hazard(),
                    "cesta nesmí vést lávou: " + waypoint);
        }
    }

    @Test
    void portaloveBlokyObchaziAleCilovyPortalDosahne() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Pruh portálových bloků napříč přímou trasou (x=3, z −1..1);
        // obchůzka kolem existuje a je levnější než portálová přirážka.
        for (int z = -1; z <= 1; z++) {
            world.set(3, FEET, z, FakeWorldView.PORTAL);
            world.set(3, FEET + 1, z, FakeWorldView.PORTAL);
        }
        Path around = new AStarPathfinder(world)
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(6, FEET, 0), 0);
        assertTrue(around.complete());
        assertFalse(around.waypoints().stream()
                        .anyMatch(p -> world.traitsAt(p).portal()),
                "bot nemá měnit dimenzi omylem: " + around.waypoints());

        // Záměrný vstup: portálová buňka jako cíl je pořád dosažitelná.
        Path into = new AStarPathfinder(world)
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(3, FEET, 0), 0);
        assertTrue(into.complete(), "cíl uvnitř portálu musí být dosažitelný");
    }

    @Test
    void vynoriSeZVodniJamy() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Vodní jáma 1×1 hloubky 4 (vyhloubená do podlahy, po hladinu):
        // bot na dně musí vyplavat sloupcem vzhůru.
        for (int y = FEET - 4; y <= FEET; y++) {
            world.set(3, y, 0, FakeWorldView.WATER);
        }
        Path path = new AStarPathfinder(world)
                .findPath(new BlockPos(3, FEET - 4, 0), new BlockPos(6, FEET, 0), 0);

        assertTrue(path.complete(), "z vodní jámy se má dát vyplavat: " + path.waypoints());
        assertTrue(path.waypoints().stream().anyMatch(p -> p.y() == FEET),
                "cesta má vystoupat k hladině");
    }

    @Test
    void preplaveVodniPrekop() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Vodní příkop přes celou šíři (x=3..4): plave se skrz, ne okolo.
        for (int x = 3; x <= 4; x++) {
            for (int z = -8; z <= 8; z++) {
                world.set(x, FEET, z, FakeWorldView.WATER);
                world.set(x, FLOOR, z, FakeWorldView.WATER);
            }
        }
        Path path = new AStarPathfinder(world)
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(7, FEET, 0), 0);

        assertTrue(path.complete(), "voda se má dát přeplavat");
        assertTrue(path.waypoints().stream()
                        .anyMatch(p -> world.traitsAt(p).liquid()),
                "cesta má vést vodou (plavání)");
    }

    @Test
    void seskociZVyskyDoHlubokeVody() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Útes: bot startuje na věži výšky 8; hned vedle bazén hloubky 2.
        int towerTop = FEET + 8;
        world.wall(0, FEET, towerTop - 1, 0);
        for (int x = 1; x <= 2; x++) {
            world.set(x, FEET, 0, FakeWorldView.WATER);
            world.set(x, FLOOR, 0, FakeWorldView.WATER);
        }
        Path path = new AStarPathfinder(world)
                .findPath(new BlockPos(0, towerTop, 0), new BlockPos(6, FEET, 0), 0);

        assertTrue(path.complete(), "seskok do hluboké vody má být povolen");
        assertTrue(path.waypoints().stream().anyMatch(p -> world.traitsAt(p).liquid()),
                "cesta má vést dopadem do vody: " + path.waypoints());
    }

    @Test
    void neseskociZVyskyDoLavy() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Stejný útes, ale dole láva – seskok je zakázaný a cesta nevede.
        int towerTop = FEET + 8;
        world.wall(0, FEET, towerTop - 1, 0);
        for (int x = 1; x <= 8; x++) {
            for (int z = -8; z <= 8; z++) {
                world.set(x, FEET, z, FakeWorldView.HAZARD);
                world.set(x, FLOOR, z, FakeWorldView.HAZARD);
            }
        }
        Path path = new AStarPathfinder(world)
                .findPath(new BlockPos(0, towerTop, 0), new BlockPos(6, FEET, 0), 2000);

        assertFalse(path.complete(), "do lávy se nesmí seskočit ani vstoupit");
        for (BlockPos waypoint : path.waypoints()) {
            assertFalse(world.traitsAt(waypoint).hazard(),
                    "žádný waypoint nesmí být v lávě: " + waypoint);
        }
    }

    /**
     * Uzavřená „krabice" v lajně z=0 (x 0..6) s příčnou zdí výšky 3 na x=3.
     * Boční zdi i čelní zátky brání jakékoli obchůzce – ven vede jen nahoru.
     */
    private static FakeWorldView corridorWithWall() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        for (int x = -1; x <= 7; x++) {
            world.wall(x, FEET, FEET + 2, -1); // boční zeď
            world.wall(x, FEET, FEET + 2, 1);  // boční zeď
        }
        for (int z = -1; z <= 1; z++) {
            world.wall(-1, FEET, FEET + 2, z); // čelní zátka za startem
            world.wall(7, FEET, FEET + 2, z);  // čelní zátka za cílem
        }
        world.wall(3, FEET, FEET + 2, 0); // příčná zeď výšky 3
        return world;
    }

    @Test
    void vyslapeZebrikPresVysokouZed() {
        FakeWorldView world = corridorWithWall();
        // Jediná cesta vzhůru: sloupec žebříků na přivrácené straně příčné zdi.
        for (int y = FEET; y <= FEET + 2; y++) {
            world.set(2, y, 0, FakeWorldView.CLIMBABLE);
        }
        Path path = new AStarPathfinder(world)
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(6, FEET, 0), 0);

        assertTrue(path.complete(), "přes zeď se má dát vyšplhat po žebříku: " + path.waypoints());
        assertTrue(path.waypoints().stream().anyMatch(p -> world.traitsAt(p).climbable()),
                "cesta má vést po žebříku");
        assertTrue(path.waypoints().stream().anyMatch(p -> p.y() > FEET + 1),
                "cesta má vystoupat nad zeď");
    }

    @Test
    void bezZebrikuJeVysokaZedNeprustupna() {
        // Stejný koridor, ale bez žebříku – cíl je pěšky nedosažitelný.
        Path path = new AStarPathfinder(corridorWithWall())
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(6, FEET, 0), 2000);

        assertFalse(path.complete(), "zeď výšky 3 bez žebříku nelze překonat");
    }

    @Test
    void nedosazitelnyCilVratiCastecnouCestu() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Cíl obezděný ze všech stran do výšky 2 (skok nestačí).
        BlockPos goal = new BlockPos(10, FEET, 0);
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx != 0 || dz != 0) {
                    world.wall(goal.x() + dx, FEET, FEET + 2, goal.z() + dz);
                }
            }
        }
        // Strop nad cílem proti seskoku shora.
        world.set(goal.x(), FEET + 2, goal.z(), FakeWorldView.SOLID);

        Path path = new AStarPathfinder(world)
                .findPath(new BlockPos(0, FEET, 0), goal, 2000);

        assertFalse(path.complete(), "obezděný cíl nesmí být dosažen");
    }

    /**
     * Dvě platformy oddělené mezerou šířky {@code gapWidth} (sloupce
     * x = 3 .. 3+gapWidth−1 jsou bezedné prázdno), z v [−1..1].
     */
    private static FakeWorldView platformsWithGap(int gapWidth) {
        FakeWorldView world = new FakeWorldView(0);
        for (int z = -1; z <= 1; z++) {
            for (int x = -1; x <= 2; x++) {
                world.set(x, FLOOR, z, FakeWorldView.SOLID);
            }
            for (int x = 3 + gapWidth; x <= 8 + gapWidth; x++) {
                world.set(x, FLOOR, z, FakeWorldView.SOLID);
            }
        }
        return world;
    }

    /** Nejdelší vodorovný krok mezi po sobě jdoucími waypointy (start = from). */
    private static int longestStep(Path path, BlockPos from) {
        int longest = 0;
        BlockPos prev = from;
        for (BlockPos p : path.waypoints()) {
            int step = Math.abs(p.x() - prev.x()) + Math.abs(p.z() - prev.z());
            longest = Math.max(longest, step);
            prev = p;
        }
        return longest;
    }

    @Test
    void preskociJednoblokovouMezeru() {
        BlockPos start = new BlockPos(0, FEET, 0);
        Path path = new AStarPathfinder(platformsWithGap(1))
                .findPath(start, new BlockPos(6, FEET, 0), 0);

        assertTrue(path.complete(), "mezera 1 blok má jít přeskočit: " + path.waypoints());
        assertTrue(longestStep(path, start) >= 2,
                "cesta má obsahovat skok přes mezeru: " + path.waypoints());
        assertTrue(path.waypoints().stream().noneMatch(p -> p.x() == 3),
                "žádný waypoint nesmí ležet v mezeře: " + path.waypoints());
    }

    @Test
    void preskociDvoublokovouMezeru() {
        BlockPos start = new BlockPos(0, FEET, 0);
        Path path = new AStarPathfinder(platformsWithGap(2))
                .findPath(start, new BlockPos(7, FEET, 0), 0);

        assertTrue(path.complete(), "mezera 2 bloky má jít přeskočit: " + path.waypoints());
        assertTrue(longestStep(path, start) >= 3,
                "cesta má obsahovat sprint-skok přes 2 bloky: " + path.waypoints());
    }

    @Test
    void nepreskociMezeruNadLavou() {
        FakeWorldView world = platformsWithGap(1);
        for (int z = -1; z <= 1; z++) {
            world.set(3, FLOOR, z, FakeWorldView.HAZARD); // láva na dně mezery
        }
        Path path = new AStarPathfinder(world)
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(6, FEET, 0), 2000);

        assertFalse(path.complete(), "nad lávou se neskáče – nepovedený skok je smrt");
    }

    @Test
    void nepreskociprilisSirokouMezeru() {
        Path path = new AStarPathfinder(platformsWithGap(4))
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(9, FEET, 0), 2000);

        assertFalse(path.complete(), "mezera 4 bloky je nad síly sprint-skoku");
    }

    @Test
    void preskociTriblokovouMezeruSprintem() {
        BlockPos start = new BlockPos(0, FEET, 0);
        Path path = new AStarPathfinder(platformsWithGap(3))
                .findPath(start, new BlockPos(8, FEET, 0), 0);

        assertTrue(path.complete(), "mezera 3 bloky má jít přeskočit sprintem: " + path.waypoints());
        assertTrue(longestStep(path, start) >= 4,
                "cesta má obsahovat sprint-skok přes 3 bloky: " + path.waypoints());
    }

    @Test
    void parkourVyskokNaVyssiRimsuPresDiru() {
        FakeWorldView world = new FakeWorldView(0);
        // Startovní plošina, jednobloková díra (x=3) a římsa o blok výš.
        for (int z = -1; z <= 1; z++) {
            for (int x = -1; x <= 2; x++) {
                world.set(x, FLOOR, z, FakeWorldView.SOLID);
            }
            for (int x = 4; x <= 8; x++) {
                world.set(x, FLOOR + 1, z, FakeWorldView.SOLID);
            }
        }
        Path path = new AStarPathfinder(world)
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(7, FEET + 1, 0), 0);

        assertTrue(path.complete(), "parkour výskok na římsu má existovat: " + path.waypoints());
        boolean parkour = false;
        BlockPos prev = new BlockPos(0, FEET, 0);
        for (BlockPos wp : path.waypoints()) {
            if (Math.abs(wp.x() - prev.x()) == 2 && wp.y() - prev.y() == 1) {
                parkour = true;
            }
            prev = wp;
        }
        assertTrue(parkour, "cesta má obsahovat výskok přes díru na +1: " + path.waypoints());
    }

    /**
     * Dvě platformy dotýkající se jen rohem: A (x,z ∈ [−3..0]), B (x,z ∈
     * [1+gap .. 4+gap]) – jediné spojení je diagonální skok přes roh.
     */
    private static FakeWorldView cornerPlatforms(int gap) {
        FakeWorldView world = new FakeWorldView(0);
        int b = 1 + gap;
        for (int x = -3; x <= 0; x++) {
            for (int z = -3; z <= 0; z++) {
                world.set(x, FLOOR, z, FakeWorldView.SOLID);
            }
        }
        for (int x = b; x <= b + 3; x++) {
            for (int z = b; z <= b + 3; z++) {
                world.set(x, FLOOR, z, FakeWorldView.SOLID);
            }
        }
        return world;
    }

    @Test
    void preskociRohovouMezeru() {
        BlockPos start = new BlockPos(-1, FEET, -1);
        Path path = new AStarPathfinder(cornerPlatforms(1))
                .findPath(start, new BlockPos(3, FEET, 3), 0);

        assertTrue(path.complete(), "rohová mezera má jít přeskočit: " + path.waypoints());
        assertTrue(longestStep(path, start) >= 4,
                "cesta má obsahovat diagonální skok přes roh: " + path.waypoints());
    }

    @Test
    void neskaceRohovouDvojmezeru() {
        Path path = new AStarPathfinder(cornerPlatforms(2))
                .findPath(new BlockPos(-1, FEET, -1), new BlockPos(4, FEET, 4), 2000);

        assertFalse(path.complete(), "diagonálně se skáče jen přes jeden roh");
    }

    @Test
    void preskociMezeruSDopademONizsi() {
        // Mezera 1 blok (x=3), dopadová platforma o blok níž (y = FLOOR−1).
        FakeWorldView world = new FakeWorldView(0);
        for (int z = -1; z <= 1; z++) {
            for (int x = -1; x <= 2; x++) {
                world.set(x, FLOOR, z, FakeWorldView.SOLID);
            }
            for (int x = 4; x <= 8; x++) {
                world.set(x, FLOOR - 1, z, FakeWorldView.SOLID);
            }
        }
        BlockPos start = new BlockPos(0, FEET, 0);
        Path path = new AStarPathfinder(world)
                .findPath(start, new BlockPos(6, FEET - 1, 0), 0);

        assertTrue(path.complete(), "mezera s nižším dopadem má jít přeskočit: " + path.waypoints());
        boolean hasDropJump = false;
        BlockPos prev = start;
        for (BlockPos p : path.waypoints()) {
            int step = Math.abs(p.x() - prev.x()) + Math.abs(p.z() - prev.z());
            if (step >= 2 && p.y() < prev.y()) {
                hasDropJump = true;
            }
            prev = p;
        }
        assertTrue(hasDropJump,
                "cesta má obsahovat skok s dopadem níž: " + path.waypoints());
    }

    @Test
    void obchaziPrasan() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Val prašanu napříč přímou cestou (x=3, z −3..3).
        for (int z = -3; z <= 3; z++) {
            world.set(3, FEET, z, FakeWorldView.POWDER);
        }
        Path path = new AStarPathfinder(world)
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(6, FEET, 0), 0);

        assertTrue(path.complete(), "prašan má jít obejít: " + path.waypoints());
        assertTrue(path.waypoints().stream()
                        .noneMatch(p -> world.traitsAt(p).powderSnow()),
                "žádný waypoint nesmí vést prašanem: " + path.waypoints());
    }

    // ------------------------------------------------------------ terén 2.0

    @Test
    void prejdeDeskuVJejiBunce() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Pruh spodních desek napříč cestou – bot jde PŘES ně (nohy v buňce
        // desky, ne o patro výš) a nepotřebuje skákat.
        for (int z = -8; z <= 8; z++) {
            world.set(3, FEET, z, FakeWorldView.SLAB_BOTTOM);
        }
        Path path = new AStarPathfinder(world)
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(6, FEET, 0), 0);

        assertTrue(path.complete(), "přes desky se má dát přejít: " + path.waypoints());
        assertTrue(path.waypoints().stream()
                        .anyMatch(p -> p.x() == 3 && p.y() == FEET),
                "waypoint má být v buňce desky: " + path.waypoints());
        assertTrue(path.waypoints().stream()
                        .noneMatch(p -> p.x() == 3 && p.y() == FEET + 1),
                "bot nemá skákat NAD desku: " + path.waypoints());
    }

    @Test
    void vystoupaPoSchodechNaTerasu() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Terasa o blok výš od x=4; nástup přes schody na x=3.
        for (int x = 4; x <= 8; x++) {
            for (int z = -3; z <= 3; z++) {
                world.set(x, FEET, z, FakeWorldView.SOLID);
            }
        }
        for (int z = -3; z <= 3; z++) {
            world.set(3, FEET, z, FakeWorldView.STAIR_EAST);
        }
        Path path = new AStarPathfinder(world)
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(6, FEET + 1, 0), 0);

        assertTrue(path.complete(), "po schodech se má dát vyjít: " + path.waypoints());
        assertTrue(path.waypoints().stream()
                        .anyMatch(p -> p.x() == 3 && p.y() == FEET + 1),
                "cesta má vést přes schodovou buňku: " + path.waypoints());
    }

    @Test
    void plotJeNeprekonatelny() {
        FakeWorldView world = corridorWithWall();
        // Příčnou zeď nahradit plotem: přes 1,5 bloku bot nepřeskočí.
        world.set(3, FEET, 0, FakeWorldView.FENCE);
        world.set(3, FEET + 1, 0, FakeWorldView.AIRLIKE);
        world.set(3, FEET + 2, 0, FakeWorldView.AIRLIKE);
        Path path = new AStarPathfinder(world)
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(6, FEET, 0), 2000);

        assertFalse(path.complete(), "plot nesmí jít překonat: " + path.waypoints());
        assertTrue(path.waypoints().stream()
                        .noneMatch(p -> p.x() == 3 && p.z() == 0 && p.y() >= FEET),
                "bot nesmí plánovat výstup na plot: " + path.waypoints());
    }

    @Test
    void pavucineSeVyhne() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Pavučiny v přímé lajně – bot je obejde (uvíznout nechce).
        for (int z = -1; z <= 1; z++) {
            world.set(3, FEET, z, FakeWorldView.WEB);
        }
        Path path = new AStarPathfinder(world)
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(6, FEET, 0), 0);

        assertTrue(path.complete(), "pavučiny mají jít obejít: " + path.waypoints());
        assertTrue(path.waypoints().stream().noneMatch(p -> world.traitsAt(p).web()),
                "žádný waypoint nesmí vést pavučinou: " + path.waypoints());
    }

    @Test
    void zavrenymiDvermiProjde() {
        FakeWorldView world = corridorWithWall();
        // V příčné zdi jsou zavřené dveře (2 buňky na výšku) – jediný průchod.
        world.set(3, FEET, 0, FakeWorldView.DOOR_CLOSED);
        world.set(3, FEET + 1, 0, FakeWorldView.DOOR_CLOSED);
        Path path = new AStarPathfinder(world)
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(6, FEET, 0), 0);

        assertTrue(path.complete(), "zavřenými dveřmi se má projít: " + path.waypoints());
        assertTrue(path.waypoints().stream()
                        .anyMatch(p -> p.x() == 3 && p.y() == FEET),
                "cesta má vést buňkou dveří: " + path.waypoints());
    }

    @Test
    void dlouhyZatopenyTunelSeOdmita() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Uzavřený koridor, uvnitř masiv s jedinou cestou: plně potopená
        // trubka délky 14 (> dechový rozpočet 12 buněk). Plán skrz je
        // rozsudek utopením – musí se odmítnout (provozní nález: bot utonul
        // v aquiferu bez vzduchové kapsy do 16 bloků). Krátký tunel
        // (6, simulační scénář) dál prochází.
        for (int x = -1; x <= 18; x++) {
            world.wall(x, FEET, FEET + 2, -1);
            world.wall(x, FEET, FEET + 2, 1);
        }
        for (int z = -1; z <= 1; z++) {
            world.wall(-1, FEET, FEET + 2, z);
            world.wall(18, FEET, FEET + 2, z);
        }
        for (int x = 2; x <= 15; x++) {
            world.wall(x, FEET, FEET + 2, 0);
            world.set(x, FEET, 0, FakeWorldView.WATER);
        }
        Path path = new AStarPathfinder(world)
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(17, FEET, 0), 0);

        assertTrue(!path.complete(),
                "plán skrz 14 potopených buněk nesmí být kompletní: " + path.waypoints());
        assertTrue(path.waypoints().stream().allMatch(p -> p.x() <= 2),
                "cesta nesmí vést potopenou trubkou: " + path.waypoints());
    }

    @Test
    void pomalemuPovrchuSeVyhne() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Soul sand v podlaze přímo v lajně – úkrok o buňku vedle je levnější
        // než pomalé brodění (u širokého pole se naopak vyplatí projít).
        world.set(3, FLOOR, 0, FakeWorldView.SOUL_SAND);
        Path path = new AStarPathfinder(world)
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(6, FEET, 0), 0);

        assertTrue(path.complete());
        assertTrue(path.waypoints().stream()
                        .noneMatch(p -> p.x() == 3 && p.z() == 0),
                "bot má soul sand obejít úkrokem: " + path.waypoints());
    }

    @Test
    void plavePoHladineMistoPodvodnihoTunelu() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Vodní příkop: hladina ve FEET, hloubka 3. Přes příkop se dá plavat
        // po hladině (hlava venku) i tunelem u dna – dno má být dražší.
        for (int x = 2; x <= 6; x++) {
            for (int z = -2; z <= 2; z++) {
                for (int y = FEET - 3; y <= FEET; y++) {
                    world.set(x, y, z, FakeWorldView.WATER);
                }
            }
        }
        Path path = new AStarPathfinder(world)
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(8, FEET, 0), 0);

        assertTrue(path.complete(), "příkop se má dát přeplavat: " + path.waypoints());
        assertTrue(path.waypoints().stream()
                        .noneMatch(p -> world.traitsAt(p).liquid()
                                && world.traitsAt(p.up()).liquid()),
                "cesta má držet hladinu (hlava venku), ne dno: " + path.waypoints());
    }

    @Test
    void vysokySnihPrekonaSkokem() {
        FakeWorldView world = corridorWithWall();
        // Místo zdi šest vrstev sněhu (0.625) – přes step-up to nejde, skokem ano.
        world.set(3, FEET, 0, FakeWorldView.SNOW_SIX);
        world.set(3, FEET + 1, 0, FakeWorldView.AIRLIKE);
        world.set(3, FEET + 2, 0, FakeWorldView.AIRLIKE);
        Path path = new AStarPathfinder(world)
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(6, FEET, 0), 0);

        assertTrue(path.complete(), "vysoký sníh má jít přeskočit: " + path.waypoints());
        assertTrue(path.waypoints().stream()
                        .anyMatch(p -> p.x() == 3 && p.y() == FEET),
                "cesta má vést buňkou sněhu: " + path.waypoints());
    }

    @Test
    void vysledekNeseMetriky() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        AStarPathfinder.Result result = new AStarPathfinder(world)
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(5, FEET, 0), 0, 0L, null);

        assertTrue(result.path().complete(), "krátká rovná cesta má dorazit");
        assertTrue(result.expandedNodes() > 0, "metriky mají nést počet uzlů");
        assertTrue(result.elapsedNanos() > 0, "metriky mají nést dobu výpočtu");
        assertFalse(result.timedOut());
        assertFalse(result.cancelled());
    }

    @Test
    void zruseniUkonciVypocetBrzy() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Nedosažitelný cíl vysoko ve vzduchu nad nekonečnou plání – bez
        // zrušení by výpočet semlel celý rozpočet uzlů.
        BlockPos goal = new BlockPos(200, FEET + 50, 0);
        java.util.concurrent.atomic.AtomicInteger checks = new java.util.concurrent.atomic.AtomicInteger();
        AStarPathfinder.Result result = new AStarPathfinder(world).findPath(
                new BlockPos(0, FEET, 0), goal, 100_000, 0L,
                () -> checks.incrementAndGet() > 2);

        assertTrue(result.cancelled(), "výpočet měl skončit zrušením");
        assertFalse(result.path().complete());
        assertTrue(result.expandedNodes() < 2_000,
                "zrušení se kontroluje po blocích expanzí, expandováno: " + result.expandedNodes());
    }

    @Test
    void casovyStropVratiCastecnouCestu() {
        // Pomalý svět (každý dotaz na nový blok ~20 µs) + nedosažitelný cíl:
        // časový strop musí výpočet utnout dávno před rozpočtem uzlů.
        FakeWorldView world = new FakeWorldView(FLOOR);
        dev.botalive.core.world.WorldView slow = new dev.botalive.core.world.WorldView() {
            @Override
            public org.bukkit.Material materialAt(BlockPos pos) {
                return world.materialAt(pos);
            }

            @Override
            public org.bukkit.block.data.BlockData blockDataAt(BlockPos pos) {
                return world.blockDataAt(pos);
            }

            @Override
            public dev.botalive.core.world.BlockTraits traitsAt(BlockPos pos) {
                java.util.concurrent.locks.LockSupport.parkNanos(20_000);
                return world.traitsAt(pos);
            }

            @Override
            public boolean isAvailable(BlockPos pos) {
                return world.isAvailable(pos);
            }

            @Override
            public void prefetch(BlockPos center, int radiusChunks) {
            }

            @Override
            public String worldName() {
                return world.worldName();
            }

            @Override
            public dev.botalive.core.world.WorldDimension dimension() {
                return world.dimension();
            }

            @Override
            public int minY() {
                return world.minY();
            }
        };
        AStarPathfinder.Result result = new AStarPathfinder(slow).findPath(
                new BlockPos(0, FEET, 0), new BlockPos(300, FEET + 50, 0), 500_000, 5L, null);

        assertTrue(result.timedOut(), "výpočet měl skončit časovým stropem");
        assertFalse(result.path().complete());
        assertTrue(result.expandedNodes() < 500_000,
                "rozpočet uzlů se neměl stihnout vyčerpat: " + result.expandedNodes());
    }

    @Test
    void anyOfNajdeNejblizsihoDosazitelnehoKandidata() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        BlockPos near = new BlockPos(6, FEET, 0);
        BlockPos far = new BlockPos(0, FEET, 12);
        // Bližší kandidát zazděný do krabice výšky 3 – dosažitelný je jen
        // vzdálenější; předvýběr vzdušnou čarou by vybral špatně.
        for (int x = 5; x <= 7; x++) {
            for (int z = -1; z <= 1; z++) {
                if (x != 6 || z != 0) {
                    world.wall(x, FEET, FEET + 2, z);
                }
            }
        }
        AStarPathfinder.Result result = new AStarPathfinder(world).findPath(
                new BlockPos(0, FEET, 0),
                PathGoal.anyOf(java.util.List.of(near, far)), 0, 0L, null);

        assertTrue(result.path().complete(), "dosažitelný kandidát existuje");
        assertTrue(far.equals(result.path().waypoints().getLast()),
                "cesta má skončit u dosažitelného kandidáta: "
                        + result.path().waypoints().getLast());
    }

    @Test
    void nearSkonciVOkruhu() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        BlockPos center = new BlockPos(12, FEET, 0);
        Path path = new AStarPathfinder(world).findPath(
                new BlockPos(0, FEET, 0), PathGoal.near(center, 3), 0, 0L, null).path();

        assertTrue(path.complete(), "okruh 3 kolem bloku je dosažitelný");
        BlockPos last = path.waypoints().getLast();
        assertTrue(last.distanceSquared(center) <= 9,
                "cesta má skončit v okruhu 3: " + last);
        assertTrue(path.waypoints().size() <= 10,
                "do okruhu se nemá chodit dál, než je potřeba: " + path.waypoints());
    }

    @Test
    void awayFromUtecePoBezpecnemTerenu() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Láva východně od bota – útěk musí vybrat jiný směr.
        for (int x = 5; x <= 40; x++) {
            for (int z = -20; z <= 20; z++) {
                world.set(x, FEET, z, FakeWorldView.HAZARD);
                world.set(x, FLOOR, z, FakeWorldView.HAZARD);
            }
        }
        BlockPos threat = new BlockPos(0, FEET, 0);
        Path path = new AStarPathfinder(world).findPath(
                new BlockPos(2, FEET, 0), PathGoal.awayFrom(threat, 12), 0, 0L, null).path();

        assertTrue(path.complete(), "únik na 12 bloků má existovat");
        BlockPos last = path.waypoints().getLast();
        long dx = last.x() - threat.x();
        long dz = last.z() - threat.z();
        assertTrue(dx * dx + dz * dz >= 144,
                "konec cesty má být aspoň 12 bloků od hrozby: " + last);
        for (BlockPos waypoint : path.waypoints()) {
            assertFalse(world.traitsAt(waypoint).hazard(),
                    "útěk nesmí vést lávou: " + waypoint);
        }
    }

    @Test
    void yLevelSestoupiNaHladinu() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Kaskáda teras dolů (−1 každé 2 bloky, celkem −4).
        for (int step = 1; step <= 4; step++) {
            for (int x = 2 + step * 2; x <= 30; x++) {
                for (int z = -4; z <= 4; z++) {
                    world.set(x, FLOOR - step + 1, z, FakeWorldView.AIRLIKE);
                }
            }
        }
        BlockPos start = new BlockPos(0, FEET, 0);
        Path path = new AStarPathfinder(world).findPath(
                start, PathGoal.yLevel(FEET - 4, start), 0, 0L, null).path();

        assertTrue(path.complete(), "na hladinu −4 vedou terasy");
        assertTrue(path.waypoints().getLast().y() == FEET - 4,
                "cesta má skončit na cílové hladině: " + path.waypoints().getLast());
    }

    @Test
    void prokopeSeMasivemKdyzJeToPovoleno() {
        FakeWorldView world = massif();
        BlockPos start = new BlockPos(0, FEET, 0);
        BlockPos goal = new BlockPos(9, FEET, 0);

        Path walkOnly = new AStarPathfinder(world)
                .findPath(start, goal, 2000);
        assertFalse(walkOnly.complete(), "pěšky masiv nejde");

        AStarPathfinder.Result dug = new AStarPathfinder(world).findPath(
                start, PathGoal.block(goal), 0, 0L, null, PathOptions.WITH_DIGGING);
        assertTrue(dug.path().complete(), "s kopáním se má prorazit tunel: "
                + dug.path().waypoints());
        assertFalse(dug.path().actions().isEmpty(), "plán má nést zásahy do terénu");
        int digs = dug.path().actions().values().stream()
                .mapToInt(a -> a.digs().size()).sum();
        assertTrue(digs >= 6, "tunel skrz 3 bloky zdi = aspoň 6 vykopnutí, je " + digs);
    }

    @Test
    void nekopeChraneneMaterialy() {
        FakeWorldView world = massif();
        // Masiv z obsidiánu – deny-list kopání zakazuje.
        for (int x = 3; x <= 5; x++) {
            for (int z = -2; z <= 2; z++) {
                for (int y = FEET; y <= FEET + 3; y++) {
                    world.set(x, y, z, org.bukkit.Material.OBSIDIAN, FakeWorldView.SOLID);
                }
            }
        }
        AStarPathfinder.Result result = new AStarPathfinder(world).findPath(
                new BlockPos(0, FEET, 0), PathGoal.block(new BlockPos(9, FEET, 0)),
                2000, 0L, null, PathOptions.WITH_DIGGING);

        assertFalse(result.path().complete(), "obsidián se neprokopává");
    }

    @Test
    void nekopeVedleTekutiny() {
        FakeWorldView world = massif();
        // Vodní kapsa uprostřed masivu v ose tunelu – kopání se jí musí
        // vyhnout (protržení by štolu zatopilo).
        world.set(4, FEET, 0, FakeWorldView.WATER);
        world.set(4, FEET + 1, 0, FakeWorldView.WATER);
        AStarPathfinder.Result result = new AStarPathfinder(world).findPath(
                new BlockPos(0, FEET, 0), PathGoal.block(new BlockPos(9, FEET, 0)),
                0, 0L, null, PathOptions.WITH_DIGGING);

        assertTrue(result.path().complete(), "tunel jde vést mimo vodní kapsu");
        for (TerrainAction action : result.path().actions().values()) {
            for (BlockPos dig : action.digs()) {
                for (BlockPos n : new BlockPos[]{dig.up(), dig.down(),
                        dig.offset(1, 0, 0), dig.offset(-1, 0, 0),
                        dig.offset(0, 0, 1), dig.offset(0, 0, -1)}) {
                    assertFalse(world.traitsAt(n).liquid(),
                            "vykopávaný blok " + dig + " sousedí s tekutinou");
                }
            }
        }
    }

    @Test
    void vylameSchodyDolu() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Cíl 3 patra pod povrchem – sestup vylámaným schodištěm, nikdy
        // kolmou šachtou (každý krok klesá nejvýš o 1).
        AStarPathfinder.Result result = new AStarPathfinder(world).findPath(
                new BlockPos(0, FEET, 0), PathGoal.block(new BlockPos(4, FEET - 3, 0)),
                0, 0L, null, PathOptions.WITH_DIGGING);

        assertTrue(result.path().complete(), "schodiště dolů má jít vylámat: "
                + result.path().waypoints());
        assertFalse(result.path().actions().isEmpty());
        BlockPos prev = new BlockPos(0, FEET, 0);
        for (BlockPos wp : result.path().waypoints()) {
            assertTrue(prev.y() - wp.y() <= 1,
                    "sestup po patrech, ne šachtou: " + result.path().waypoints());
            prev = wp;
        }
    }

    /**
     * Souvislý kamenný masiv (x 3..5, výška 4) přes celou uzavřenou chodbu –
     * pěšky neprůchozí, jediná cesta vede prokopáním masivu. Ohrada chodby
     * je z bedrocku: bez ní si plánovač našel levnější tunel skrz
     * jednoblokovou boční stěnu a masiv obešel venku.
     */
    private static FakeWorldView massif() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        for (int x = -1; x <= 10; x++) {
            bedrockWall(world, x, -3);
            bedrockWall(world, x, 3);
        }
        for (int z = -3; z <= 3; z++) {
            bedrockWall(world, -1, z);
            bedrockWall(world, 10, z);
        }
        for (int x = 3; x <= 5; x++) {
            for (int z = -2; z <= 2; z++) {
                world.wall(x, FEET, FEET + 3, z);
            }
        }
        return world;
    }

    /** Nedigatelný sloupec ohrady (bedrock, výška 4). */
    private static void bedrockWall(FakeWorldView world, int x, int z) {
        for (int y = FEET; y <= FEET + 3; y++) {
            world.set(x, y, z, org.bukkit.Material.BEDROCK, FakeWorldView.SOLID);
        }
    }

    @Test
    void premostiPropastKdyzMaBloky() {
        FakeWorldView world = platformsWithGap(5);
        BlockPos start = new BlockPos(0, FEET, 0);
        BlockPos goal = new BlockPos(10, FEET, 0);

        Path walkOnly = new AStarPathfinder(world).findPath(start, goal, 2000);
        assertFalse(walkOnly.complete(), "mezera 5 bloků pěšky/skokem nejde");

        AStarPathfinder.Result bridged = new AStarPathfinder(world).findPath(
                start, PathGoal.block(goal), 0, 0L, null, PathOptions.withActions(8));
        assertTrue(bridged.path().complete(), "s bloky v rozpočtu se má přemostit: "
                + bridged.path().waypoints());
        int places = bridged.path().actions().values().stream()
                .mapToInt(a -> a.places().size()).sum();
        // Plánovač míchá strategie jako hráč: 2 opory + sprint-skok přes
        // zbylé 3 sloupce z položeného decku (ne otrocky 5 bloků).
        assertTrue(places >= 2, "přemostění chce aspoň 2 opory, je " + places);

        AStarPathfinder.Result tight = new AStarPathfinder(world).findPath(
                start, PathGoal.block(goal), 2000, 0L, null, PathOptions.withActions(1));
        assertFalse(tight.path().complete(), "1 blok na mezeru 5 nestačí (rozpočet)");
    }

    @Test
    void vypilirujeSeNaPlosinu() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Plošina +4 s kolmými stěnami – bez akcí nedosažitelná.
        for (int x = 5; x <= 12; x++) {
            for (int z = -4; z <= 4; z++) {
                world.wall(x, FEET, FEET + 3, z);
            }
        }
        BlockPos start = new BlockPos(0, FEET, 0);
        BlockPos goal = new BlockPos(8, FEET + 4, 0);

        Path walkOnly = new AStarPathfinder(world).findPath(start, goal, 2000);
        assertFalse(walkOnly.complete(), "kolmé stěny pěšky nejdou");

        // Jen pokládání (bez kopání) – deterministicky pilíř, ne schody do stěny.
        AStarPathfinder.Result pillared = new AStarPathfinder(world).findPath(
                start, PathGoal.block(goal), 0, 0L, null, new PathOptions(false, 8, 0, false));
        assertTrue(pillared.path().complete(), "pilíř má na plošinu vynést: "
                + pillared.path().waypoints());
        int places = pillared.path().actions().values().stream()
                .mapToInt(a -> a.places().size()).sum();
        // 3 bloky pilíře + výskok na hranu plošiny (poslední patro zvládne skok).
        assertTrue(places >= 3, "výstup o 4 patra = aspoň 3 bloky, je " + places);
    }

    @Test
    void nemostiSLavouPodOperou() {
        FakeWorldView world = platformsWithGap(1);
        // Láva těsně pod úrovní opory mostu: skok je zakázaný (hazard dole)
        // a most taky – pád z mostku nad lávou je rozsudek. Bedrockové
        // mantinely po stranách: bez nich plánovač korektně vymyslel boční
        // lávku o dráhu vedle, kde pod oporami láva není.
        for (int z = -1; z <= 1; z++) {
            world.set(3, FLOOR - 1, z, FakeWorldView.HAZARD);
        }
        for (int x = 0; x <= 6; x++) {
            for (int y = FLOOR - 2; y <= FEET + 3; y++) {
                world.set(x, y, -2, org.bukkit.Material.BEDROCK, FakeWorldView.SOLID);
                world.set(x, y, 2, org.bukkit.Material.BEDROCK, FakeWorldView.SOLID);
            }
        }
        AStarPathfinder.Result result = new AStarPathfinder(world).findPath(
                new BlockPos(0, FEET, 0), PathGoal.block(new BlockPos(6, FEET, 0)),
                2000, 0L, null, PathOptions.withActions(8));

        assertFalse(result.path().complete(),
                "nad lávou se nemostí ani neskáče – zůstává reaktivní BridgeTask");
    }

    @Test
    void castecnaCestaMiriKCili() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Cíl nedosažitelný (ve vzduchu), malý rozpočet – částečná cesta se má
        // aspoň přiblížit správným směrem.
        Path path = new AStarPathfinder(world)
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(60, FEET + 40, 0), 500);

        assertFalse(path.complete());
        assertFalse(path.isEmpty(), "částečná cesta má obsahovat přiblížení");
        BlockPos last = path.waypoints().getLast();
        assertTrue(last.x() > 10, "částečná cesta má postoupit k cíli: " + last);
    }

    /**
     * Koridor šířky 1 (bedrock mantinely) s roklí přes celou šířku:
     * sloupce {@code gapFrom..gapTo} vykopané do hloubky {@code depth}
     * pod úroveň podlahy. Jediná cesta vpřed je sprint-skok.
     */
    private static FakeWorldView roklovyKoridor(int gapFrom, int gapTo, int depth) {
        FakeWorldView world = new FakeWorldView(FLOOR);
        for (int x = -1; x <= 8; x++) {
            for (int y = FEET; y <= FEET + 2; y++) {
                world.set(x, y, -1, org.bukkit.Material.BEDROCK, FakeWorldView.SOLID);
                world.set(x, y, 1, org.bukkit.Material.BEDROCK, FakeWorldView.SOLID);
            }
        }
        // Čela koridoru – bez nich se rokle dá legálně obejít kolem konců zdí.
        for (int y = FEET; y <= FEET + 2; y++) {
            world.set(-1, y, 0, org.bukkit.Material.BEDROCK, FakeWorldView.SOLID);
            world.set(8, y, 0, org.bukkit.Material.BEDROCK, FakeWorldView.SOLID);
        }
        for (int x = gapFrom; x <= gapTo; x++) {
            for (int y = FLOOR - depth + 1; y <= FLOOR; y++) {
                world.set(x, y, 0, FakeWorldView.AIRLIKE);
            }
        }
        return world;
    }

    @Test
    void lavaVHlubokeRokliZakazeSkok() {
        // Láva na dně v hloubce 12 – mimo starý sken (8), uvnitř dohledné
        // hloubky (24). Kontrola: bez lávy se stejná rokle přeskočí.
        FakeWorldView clean = roklovyKoridor(3, 4, 11);
        Path jumped = new AStarPathfinder(clean)
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(7, FEET, 0), 0);
        assertTrue(jumped.complete(), "hluboká rokle bez lávy se skáče dál");
        assertTrue(longestStep(jumped, new BlockPos(0, FEET, 0)) >= 3,
                "očekáván sprint-skok: " + jumped.waypoints());

        FakeWorldView lava = roklovyKoridor(3, 4, 11);
        for (int x = 3; x <= 4; x++) {
            lava.set(x, FLOOR - 11, 0, FakeWorldView.HAZARD);
        }
        Path refused = new AStarPathfinder(lava)
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(7, FEET, 0), 0);
        assertFalse(refused.complete(),
                "nad lávou v dohledné hloubce se neskáče: " + refused.waypoints());
    }

    @Test
    void neskaceSkrzZavreneDvere() {
        // Zavřené dveře v letové dráze (úroveň hlavy nad mezerou): uprostřed
        // letu se neotevřou – skok se zakáže. Bez dveří stejná rokle projde
        // (kontrolu dělá lavaVHlubokeRokliZakazeSkok).
        FakeWorldView world = roklovyKoridor(3, 4, 12);
        world.set(4, FEET + 1, 0, FakeWorldView.DOOR_CLOSED);
        // Strop nad dveřmi – zavřené dveře jsou plný box a bez stropu by na
        // jejich vršek šel parkour výskok (legální trasa mimo testovaný let).
        world.set(4, FEET + 2, 0, org.bukkit.Material.BEDROCK, FakeWorldView.SOLID);
        Path path = new AStarPathfinder(world)
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(7, FEET, 0), 0);
        assertFalse(path.complete(),
                "skrz zavřené dveře se neletí: " + path.waypoints());
    }

    @Test
    void diagonalaNerezeRohPresZavreneDvere() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        world.set(1, FEET, 0, FakeWorldView.DOOR_CLOSED);
        world.set(1, FEET + 1, 0, FakeWorldView.DOOR_CLOSED);
        Path path = new AStarPathfinder(world)
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(4, FEET, 4), 0);

        assertTrue(path.complete());
        // Žádný diagonální krok nesmí mít v rohovém sloupci zavřené dveře –
        // bot je při řezání rohu neotvírá a odřel by se o jejich kolizi.
        BlockPos prev = new BlockPos(0, FEET, 0);
        for (BlockPos next : path.waypoints()) {
            int dx = next.x() - prev.x();
            int dz = next.z() - prev.z();
            if (dx != 0 && dz != 0 && next.y() == prev.y()) {
                for (BlockPos corner : new BlockPos[]{
                        new BlockPos(prev.x() + dx, prev.y(), prev.z()),
                        new BlockPos(prev.x(), prev.y(), prev.z() + dz)}) {
                    assertFalse(world.traitsAt(corner).door()
                                    || world.traitsAt(corner.up()).door(),
                            "diagonála " + prev + "→" + next + " řeže roh přes dveře");
                }
            }
            prev = next;
        }
    }

    /** Koridor s bedrockovou příčnou zdí výšky 3 (nejde přeskočit ani prokopat). */
    private static FakeWorldView zedVKoridoru() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        for (int x = -1; x <= 8; x++) {
            for (int y = FEET; y <= FEET + 2; y++) {
                world.set(x, y, -1, org.bukkit.Material.BEDROCK, FakeWorldView.SOLID);
                world.set(x, y, 1, org.bukkit.Material.BEDROCK, FakeWorldView.SOLID);
            }
        }
        for (int y = FEET; y <= FEET + 2; y++) {
            world.set(-1, y, 0, org.bukkit.Material.BEDROCK, FakeWorldView.SOLID);
            world.set(8, y, 0, org.bukkit.Material.BEDROCK, FakeWorldView.SOLID);
            world.set(3, y, 0, org.bukkit.Material.BEDROCK, FakeWorldView.SOLID);
        }
        // Bedrocková podlaha – zeď nejde podkopat (kopací hrany jsou při
        // akčním plánování povolené a kámen pod zdí by tunel legálně pustil).
        for (int x = -1; x <= 8; x++) {
            world.set(x, FLOOR, 0, org.bukkit.Material.BEDROCK, FakeWorldView.SOLID);
        }
        return world;
    }

    @Test
    void prelezeZedPoZebrikuKdyzMaPricky() {
        FakeWorldView world = zedVKoridoru();
        BlockPos start = new BlockPos(0, FEET, 0);
        PathGoal goal = PathGoal.block(new BlockPos(6, FEET, 0));

        AStarPathfinder.Result walk = new AStarPathfinder(world).findPath(
                start, goal, 2000, 0L, null, PathOptions.WALK_ONLY);
        assertFalse(walk.path().complete(), "bedrock zeď výšky 3 pěšky nejde");

        AStarPathfinder.Result laddered = new AStarPathfinder(world).findPath(
                start, goal, 2000, 0L, null, PathOptions.withActions(0, 8));
        assertTrue(laddered.path().complete(), "s žebříky se má zeď přelézt: "
                + laddered.path().waypoints());
        TerrainAction.Ladder ladder = laddered.path().actions().values().stream()
                .map(TerrainAction::ladder)
                .filter(java.util.Objects::nonNull)
                .findFirst().orElse(null);
        assertTrue(ladder != null && ladder.height() == 3,
                "plán má nést žebříkový výstup výšky 3: " + laddered.path().actions());

        AStarPathfinder.Result tight = new AStarPathfinder(world).findPath(
                start, goal, 2000, 0L, null, PathOptions.withActions(0, 2));
        assertFalse(tight.path().complete(), "2 příčky na zeď výšky 3 nestačí");
    }

    @Test
    void anyNearVybereDosazitelnehoKandidata() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Bližší „ruda" je zazděná (žádná pochozí buňka v okruhu 2),
        // vzdálenější stojí volně – o pořadí rozhoduje dosažitelnost.
        BlockPos sealed = new BlockPos(4, FEET, 0);
        BlockPos open = new BlockPos(10, FEET, 4);
        world.set(10, FEET, 4, FakeWorldView.SOLID);
        for (int x = 2; x <= 6; x++) {
            for (int z = -2; z <= 2; z++) {
                for (int y = FEET - 1; y <= FEET + 2; y++) {
                    world.set(x, y, z, FakeWorldView.SOLID);
                }
            }
        }

        AStarPathfinder.Result blocked = new AStarPathfinder(world).findPath(
                new BlockPos(0, FEET, 0), PathGoal.near(sealed, 2), 2000, 0L, null);
        assertFalse(blocked.path().complete(), "zazděná ruda není dosažitelná");

        AStarPathfinder.Result any = new AStarPathfinder(world).findPath(
                new BlockPos(0, FEET, 0),
                PathGoal.anyNear(java.util.List.of(sealed, open), 2), 2000, 0L, null);
        assertTrue(any.path().complete(), "anyNear má dojít k volné rudě");
        BlockPos last = any.path().waypoints().getLast();
        assertTrue(last.distanceSquared(open) <= 2 * 2,
                "cesta má končit u dosažitelného kandidáta: " + last);
    }

    @Test
    void nekopeTunelPodPadavymStropem() {
        FakeWorldView world = massif();
        // Horní dvě patra masivu jsou písek: jakýkoli tunel (i vylámaný
        // schod) by měl nad vykopanou buňkou padavý blok a strop by se
        // sesypal do štoly – plán se odmítne.
        for (int x = 3; x <= 5; x++) {
            for (int z = -2; z <= 2; z++) {
                world.set(x, FEET + 2, z, org.bukkit.Material.SAND, FakeWorldView.SOLID);
                world.set(x, FEET + 3, z, org.bukkit.Material.SAND, FakeWorldView.SOLID);
            }
        }
        // Bedrockové dno proti podkopání masivu zespodu (schody dolů a tunel
        // pod pískem by pojistku legálně obešly – tady testujeme strop).
        for (int x = -1; x <= 10; x++) {
            for (int z = -3; z <= 3; z++) {
                world.set(x, FLOOR, z, org.bukkit.Material.BEDROCK, FakeWorldView.SOLID);
            }
        }
        AStarPathfinder.Result result = new AStarPathfinder(world).findPath(
                new BlockPos(0, FEET, 0), PathGoal.block(new BlockPos(9, FEET, 0)),
                2000, 0L, null, PathOptions.WITH_DIGGING);
        assertFalse(result.path().complete(),
                "pod padavým stropem se tunel nekope: " + result.path().waypoints());
    }

    @Test
    void prokopeSterkKdyzJeStropPevny() {
        FakeWorldView world = massif();
        // Štěrk v ose tunelu s kamenem nad ním: pojistka míří na strop nad
        // výkopem, ne na kopaný blok – štěrk sám je legitimní cíl krumpáče.
        for (int z = -2; z <= 2; z++) {
            world.set(4, FEET, z, org.bukkit.Material.GRAVEL, FakeWorldView.SOLID);
            world.set(4, FEET + 1, z, org.bukkit.Material.GRAVEL, FakeWorldView.SOLID);
        }
        AStarPathfinder.Result result = new AStarPathfinder(world).findPath(
                new BlockPos(0, FEET, 0), PathGoal.block(new BlockPos(9, FEET, 0)),
                2000, 0L, null, PathOptions.WITH_DIGGING);
        assertTrue(result.path().complete(),
                "štěrk pod pevným stropem se prokopat smí: " + result.path().waypoints());
    }

    @Test
    void nearCilUNepruchozihoBlokuSetriRozpocet() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Pec/ponk/truhla: neprůchozí blok. Cíl „přesně tento blok" se nikdy
        // nesplní – A* spálí celý rozpočet a vrátí částečnou cestu. Cíl
        // near(blok, 2) končí vedle bloku a rozpočet nechá na pokoji – proto
        // goaly s interakcí u bloku navigují přes near.
        BlockPos furnace = new BlockPos(8, FEET, 0);
        world.set(8, FEET, 0, FakeWorldView.SOLID);

        AStarPathfinder.Result blocked = new AStarPathfinder(world).findPath(
                new BlockPos(0, FEET, 0), PathGoal.block(furnace), 2000, 0L, null);
        AStarPathfinder.Result near = new AStarPathfinder(world).findPath(
                new BlockPos(0, FEET, 0), PathGoal.near(furnace, 2), 2000, 0L, null);

        assertFalse(blocked.path().complete(), "na neprůchozí blok se nedá dojít");
        assertTrue(near.path().complete(), "okolí bloku dojít jde");
        assertTrue(near.expandedNodes() * 10 < blocked.expandedNodes(),
                "near cíl nemá pálit rozpočet: near=" + near.expandedNodes()
                        + " vs block=" + blocked.expandedNodes());
    }

    @Test
    void vybereKlidnouVoduMistoProtiproudu() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Vodní koridor (bedrock mantinely, z −1..1): prostřední řada (z=0)
        // teče PROTI směru plavání (zdroj u cíle, hladiny řídnou ke startu),
        // krajní řady jsou klidná zdrojová voda. Bez proudu by vyhrála přímá
        // trasa středem; přirážka za protiproud ji má vyhnout do klidné řady.
        for (int x = -1; x <= 10; x++) {
            for (int y = FEET; y <= FEET + 1; y++) {
                world.set(x, y, -2, org.bukkit.Material.BEDROCK, FakeWorldView.SOLID);
                world.set(x, y, 2, org.bukkit.Material.BEDROCK, FakeWorldView.SOLID);
            }
        }
        for (int x = 1; x <= 8; x++) {
            int level = Math.max(1, Math.min(7, 9 - x)); // u startu tenká, u cíle zdroj
            for (int z = -1; z <= 1; z++) {
                world.set(x, FEET, z, z == 0 ? FakeWorldView.flowing(level) : FakeWorldView.WATER);
                world.set(x, FLOOR, z, FakeWorldView.WATER);
            }
        }
        Path path = new AStarPathfinder(world)
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(9, FEET, 0), 0);

        assertTrue(path.complete());
        assertTrue(path.waypoints().stream()
                        .filter(p -> p.x() >= 2 && p.x() <= 7)
                        .allMatch(p -> p.z() != 0),
                "plavat se má klidnou řadou, ne proti proudu: " + path.waypoints());
    }

    @Test
    void drziSeCesticky() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Udusaná cestička běží o řadu vedle přímé spojnice start–cíl.
        for (int x = 0; x <= 15; x++) {
            world.set(x, FLOOR, 1, FakeWorldView.PATH);
        }
        Path path = new AStarPathfinder(world)
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(15, FEET, 0), 0);

        assertTrue(path.complete());
        // Preference: většina cesty má vést po cestičce (z = 1), ne trávou (z = 0).
        long onPath = path.waypoints().stream().filter(p -> p.z() == 1).count();
        assertTrue(onPath >= 10, "cesta má držet cestičku, po ní šlo jen "
                + onPath + " waypointů: " + path.waypoints());
    }

    @Test
    void cestickaNestojiZaVelkouZajizdku() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Stejná cestička, ale daleko stranou – přirážka ~10 % za krok mimo
        // cestu nesmí ospravedlnit zajížďku o šest řad tam a zpět.
        for (int x = 0; x <= 15; x++) {
            world.set(x, FLOOR, 6, FakeWorldView.PATH);
        }
        Path path = new AStarPathfinder(world)
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(15, FEET, 0), 0);

        assertTrue(path.complete());
        assertTrue(path.waypoints().stream().allMatch(p -> p.z() == 0),
                "vzdálená cestička nemá cestu přitáhnout: " + path.waypoints());
    }

    /**
     * Uzavřená trubka podél osy X (boční zdi z=±1 a čela na obou koncích výšky
     * 4), přes kterou stojí masiv (x=3..5) s jednoblokovou štolou v úrovni
     * nohou. Jediná trasa vede středem – vestoje neprůchozí, plazením ano.
     */
    private FakeWorldView crawlTunnel() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        for (int x = -1; x <= 10; x++) {
            for (int y = FEET; y <= FEET + 3; y++) {
                world.set(x, y, -1, FakeWorldView.SOLID);
                world.set(x, y, 1, FakeWorldView.SOLID);
            }
        }
        for (int y = FEET; y <= FEET + 3; y++) {
            world.set(-1, y, 0, FakeWorldView.SOLID); // západní čelo trubky
            world.set(10, y, 0, FakeWorldView.SOLID); // východní čelo trubky
        }
        for (int x = 3; x <= 5; x++) {
            for (int y = FEET; y <= FEET + 3; y++) {
                world.set(x, y, 0, FakeWorldView.SOLID);
            }
            world.set(x, FEET, 0, FakeWorldView.AIRLIKE); // štola: strop FEET+1 drží
        }
        return world;
    }

    @Test
    void proplaziSeJednoblokovouStolou() {
        FakeWorldView world = crawlTunnel();
        BlockPos start = new BlockPos(0, FEET, 0);
        BlockPos goal = new BlockPos(9, FEET, 0);

        Path walkOnly = new AStarPathfinder(world).findPath(start, goal, 2000);
        assertFalse(walkOnly.complete(), "vestoje se jednoblokovou štolou neprojde");

        AStarPathfinder.Result crawl = new AStarPathfinder(world).findPath(
                start, PathGoal.block(goal), 2000, 0L, null, PathOptions.WALK_WITH_CRAWL);
        assertTrue(crawl.path().complete(),
                "s plazením se štolou projde: " + crawl.path().waypoints());
        assertTrue(crawl.path().waypoints().stream()
                        .anyMatch(p -> p.x() == 4 && p.y() == FEET && p.z() == 0),
                "cesta má vést štolou: " + crawl.path().waypoints());
    }

    @Test
    void neplaziSeNadPrazdno() {
        FakeWorldView world = crawlTunnel();
        // Pod prostřední buňkou štoly chybí podlaha (propast) – plazit se nad
        // prázdno se nesmí ani s povoleným plazením.
        for (int y = FLOOR; y >= FLOOR - 4; y--) {
            world.set(4, y, 0, FakeWorldView.AIRLIKE);
        }
        AStarPathfinder.Result crawl = new AStarPathfinder(world).findPath(
                new BlockPos(0, FEET, 0), PathGoal.block(new BlockPos(9, FEET, 0)),
                2000, 0L, null, PathOptions.WALK_WITH_CRAWL);
        assertFalse(crawl.path().complete(),
                "nad propastí se štolou neplazí: " + crawl.path().waypoints());
    }

    @Test
    void neplaziSeKdyzToStaciChuze() {
        // Bez nízkého stropu (běžná rovina) plazení nic nepřidá – cesta i s
        // povoleným plazením zůstává čistě pěší (žádná regrese preferencí).
        FakeWorldView world = new FakeWorldView(FLOOR);
        Path plain = new AStarPathfinder(world)
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(6, FEET, 0), 0);
        AStarPathfinder.Result withCrawl = new AStarPathfinder(world).findPath(
                new BlockPos(0, FEET, 0), PathGoal.block(new BlockPos(6, FEET, 0)),
                0, 0L, null, PathOptions.WALK_WITH_CRAWL);

        assertTrue(plain.complete() && withCrawl.path().complete());
        assertEquals(plain.waypoints(), withCrawl.path().waypoints(),
                "na rovině plazení nemění cestu");
    }
}
