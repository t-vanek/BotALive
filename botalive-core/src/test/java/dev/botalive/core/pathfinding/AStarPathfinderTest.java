package dev.botalive.core.pathfinding;

import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.BlockPos;
import org.junit.jupiter.api.Test;

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
        Path path = new AStarPathfinder(platformsWithGap(3))
                .findPath(new BlockPos(0, FEET, 0), new BlockPos(8, FEET, 0), 2000);

        assertFalse(path.complete(), "mezera 3 bloky je nad síly sprint-skoku");
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
}
