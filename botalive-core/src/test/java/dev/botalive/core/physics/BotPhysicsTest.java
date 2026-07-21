package dev.botalive.core.physics;

import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.Vec3;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy fyziky bota (pád, kolize se zdí, skok přes blok).
 */
class BotPhysicsTest {

    private static final int FLOOR = 63;
    private static final double FEET_Y = FLOOR + 1;
    private static final double EPSILON = 1.0E-7;

    @Test
    void padDopadneNaPodlahu() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        BotPhysics physics = new BotPhysics(world, new Vec3(0.5, FEET_Y + 6, 0.5));

        for (int i = 0; i < 60 && !physics.onGround(); i++) {
            physics.step(MoveInput.IDLE);
        }

        assertTrue(physics.onGround(), "bot má dopadnout na zem");
        assertEquals(FEET_Y, physics.position().y(), 0.01, "nohy mají stát na podlaze");
    }

    @Test
    void chuzeDoZdiZastaviBota() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Zeď výšky 2 na x=3 (skrz ni to nejde ani skokem).
        for (int z = -2; z <= 2; z++) {
            world.wall(3, (int) FEET_Y, (int) FEET_Y + 1, z);
        }
        BotPhysics physics = new BotPhysics(world, new Vec3(0.5, FEET_Y, 0.5));

        MoveInput east = MoveInput.walk(new Vec3(1, 0, 0));
        boolean collided = false;
        for (int i = 0; i < 80; i++) {
            physics.step(east);
            collided |= physics.horizontalCollision();
        }

        assertTrue(collided, "bot má narazit do zdi");
        assertTrue(physics.position().x() < 3.0, "bot nesmí projít zdí, x=" + physics.position().x());
    }

    @Test
    void skokemPrekonaJedenBlok() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Terasa výšky 1 od x=3 (širší než dolet – vanilla skok 1,25 umí
        // jednoblokový schod rovnou přeletět a dopadnout za ním).
        for (int x = 3; x <= 7; x++) {
            for (int z = -2; z <= 2; z++) {
                world.set(x, (int) FEET_Y, z, FakeWorldView.SOLID);
            }
        }
        BotPhysics physics = new BotPhysics(world, new Vec3(0.5, FEET_Y, 0.5));

        for (int i = 0; i < 120 && physics.position().x() < 4.0; i++) {
            physics.step(MoveInput.of(new Vec3(1, 0, 0), false, physics.onGround()));
        }
        // Vanilla skok (vrchol 1,25) nese přes hranu ještě ve vzduchu –
        // před kontrolou výšky nechat bota dosednout.
        for (int i = 0; i < 40 && !physics.onGround(); i++) {
            physics.step(MoveInput.IDLE);
        }

        assertTrue(physics.position().x() >= 4.0,
                "bot má skokem vylézt na blok, x=" + physics.position().x());
        assertEquals(FEET_Y + 1, physics.position().y(), 0.05, "bot má stát na schodu");
    }

    @Test
    void proudUnasiPlavajicihoBota() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Kanál tekoucí vody podél +x: zdroj na x=0, hladiny řídnou k x=12.
        world.set(0, (int) FEET_Y, 0, FakeWorldView.WATER);
        for (int x = 1; x <= 12; x++) {
            world.set(x, (int) FEET_Y, 0, FakeWorldView.flowing(Math.min(7, x)));
        }
        BotPhysics physics = new BotPhysics(world, new Vec3(2.5, FEET_Y, 0.5));

        for (int i = 0; i < 60; i++) {
            physics.step(MoveInput.IDLE); // bot nic nedělá – nese ho proud
        }

        assertTrue(physics.position().x() > 3.5,
                "proud má bota unášet po gradientu: x=" + physics.position().x());
    }

    @Test
    void plavecSeSkokemVyhoupneNaBreh() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Bazén (x 0..2) o blok zapuštěný; břeh od x=3 v úrovni FEET_Y.
        for (int x = 0; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                world.set(x, (int) FEET_Y - 1, z, FakeWorldView.WATER);
            }
        }
        // Podlahu pod bazénem posunout o 2 dolů (voda hloubky 2).
        for (int x = 0; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                world.set(x, (int) FEET_Y - 2, z, FakeWorldView.WATER);
            }
        }
        BotPhysics physics = new BotPhysics(world, new Vec3(1.5, FEET_Y - 1, 0.5));

        // Plavat na východ se skokem – u stěny břehu se má vyhoupnout nahoru.
        MoveInput swim = new MoveInput(new Vec3(1, 0, 0), false, true, false);
        for (int i = 0; i < 200 && physics.position().x() < 3.6; i++) {
            physics.step(swim);
        }

        assertTrue(physics.position().x() >= 3.6,
                "plavec se má dostat na břeh, x=" + physics.position().x());
        assertTrue(physics.position().y() >= FEET_Y - 0.05,
                "plavec má stát na úrovni břehu, y=" + physics.position().y());
    }

    @Test
    void vyslapeSloupecZebriku() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Sloupec žebříků ve vlastním sloupci bota (x=0), 4 patra nad podlahou,
        // za nimi pevná stěna. Bot se skokem šplhá vzhůru.
        for (int y = (int) FEET_Y; y <= (int) FEET_Y + 3; y++) {
            world.set(0, y, 0, FakeWorldView.CLIMBABLE);
            world.set(1, y, 0, FakeWorldView.SOLID);
        }
        BotPhysics physics = new BotPhysics(world, new Vec3(0.5, FEET_Y, 0.5));

        // Držení „nahoru" po žebříku (jump = stoupání).
        MoveInput climb = new MoveInput(Vec3.ZERO, false, true, false);
        for (int i = 0; i < 80; i++) {
            physics.step(climb);
        }

        assertTrue(physics.position().y() >= FEET_Y + 2.5,
                "bot má vyšplhat po žebříku, y=" + physics.position().y());
    }

    @Test
    void bezZebrikuNestoupa() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        BotPhysics physics = new BotPhysics(world, new Vec3(0.5, FEET_Y, 0.5));

        // Bez žebříku samotný „jump" bota nevynese trvale nahoru (skáče na místě
        // a padá zpět) – nikdy nevyšplhá o víc než jeden blok.
        MoveInput climb = new MoveInput(Vec3.ZERO, false, true, false);
        double maxY = FEET_Y;
        for (int i = 0; i < 80; i++) {
            physics.step(climb);
            maxY = Math.max(maxY, physics.position().y());
        }

        assertTrue(maxY < FEET_Y + 1.5,
                "bez žebříku má bot jen skákat na místě, maxY=" + maxY);
    }

    @Test
    void sprintSkokPrekonaDvoublokovouMezeru() {
        // Dvě platformy s mezerou 2 bloků (x=3,4 je bezedné prázdno).
        FakeWorldView world = new FakeWorldView(0);
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                world.set(x, FLOOR, z, FakeWorldView.SOLID);
            }
        }
        for (int x = 5; x <= 9; x++) {
            for (int z = -2; z <= 2; z++) {
                world.set(x, FLOOR, z, FakeWorldView.SOLID);
            }
        }
        BotPhysics physics = new BotPhysics(world, new Vec3(0.5, FEET_Y, 0.5));

        // Emulace navigátoru: sprint na východ, odraz na hraně (kousek před
        // botem chybí podlaha) – stejná logika jako Navigator.takeoffEdge.
        Vec3 east = new Vec3(1, 0, 0);
        double minY = FEET_Y;
        boolean crossed = false;
        for (int i = 0; i < 300 && !crossed; i++) {
            boolean edge = !world.traitsAt(
                    physics.position().add(0.7, 0, 0).toBlockPos().down()).solid();
            boolean jump = physics.onGround() && edge;
            physics.step(new MoveInput(east, true, jump, false));
            minY = Math.min(minY, physics.position().y());
            crossed = physics.onGround() && physics.position().x() > 5.2;
        }

        assertTrue(crossed, "bot má přeskočit mezeru, x=" + physics.position().x());
        assertTrue(minY >= FEET_Y - 0.1,
                "bot nesmí propadnout do mezery, minY=" + minY);
    }

    @Test
    void diagonalniSprintSkokPresRoh() {
        // Dvě platformy dotýkající se jen rohem: A (x,z ≤ 0), B (x,z ≥ 2);
        // mezera je rohový sloupec (1,1) a sousední rohové sloupce.
        FakeWorldView world = new FakeWorldView(0);
        for (int x = -2; x <= 0; x++) {
            for (int z = -2; z <= 0; z++) {
                world.set(x, FLOOR, z, FakeWorldView.SOLID);
            }
        }
        for (int x = 2; x <= 4; x++) {
            for (int z = 2; z <= 4; z++) {
                world.set(x, FLOOR, z, FakeWorldView.SOLID);
            }
        }
        BotPhysics physics = new BotPhysics(world, new Vec3(-1.5, FEET_Y, -1.5));

        Vec3 diag = new Vec3(1, 0, 1).normalized();
        double minY = FEET_Y;
        boolean crossed = false;
        for (int i = 0; i < 300 && !crossed; i++) {
            Vec3 ahead = physics.position().add(diag.mul(0.7));
            boolean edge = !world.traitsAt(ahead.toBlockPos().down()).solid();
            boolean jump = physics.onGround() && edge;
            physics.step(new MoveInput(diag, true, jump, false));
            minY = Math.min(minY, physics.position().y());
            crossed = physics.onGround()
                    && physics.position().x() > 2.0 && physics.position().z() > 2.0;
        }

        assertTrue(crossed, "bot má přeskočit rohovou mezeru, pos=" + physics.position());
        assertTrue(minY >= FEET_Y - 0.1,
                "bot nesmí propadnout do rohové mezery, minY=" + minY);
    }

    @Test
    void sprintSkokSDopademONizsi() {
        // Mezera 2 bloků (x=3,4), dopadová platforma o blok níž (y = FLOOR−1).
        FakeWorldView world = new FakeWorldView(0);
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                world.set(x, FLOOR, z, FakeWorldView.SOLID);
            }
        }
        for (int x = 5; x <= 9; x++) {
            for (int z = -2; z <= 2; z++) {
                world.set(x, FLOOR - 1, z, FakeWorldView.SOLID);
            }
        }
        BotPhysics physics = new BotPhysics(world, new Vec3(0.5, FEET_Y, 0.5));

        Vec3 east = new Vec3(1, 0, 0);
        double minY = FEET_Y;
        boolean crossed = false;
        for (int i = 0; i < 300 && !crossed; i++) {
            boolean edge = !world.traitsAt(
                    physics.position().add(0.7, 0, 0).toBlockPos().down()).solid();
            boolean jump = physics.onGround() && edge;
            physics.step(new MoveInput(east, true, jump, false));
            minY = Math.min(minY, physics.position().y());
            crossed = physics.onGround() && physics.position().x() > 5.2;
        }

        assertTrue(crossed, "bot má doletět na nižší platformu, x=" + physics.position().x());
        assertEquals(FEET_Y - 1, physics.position().y(), 0.05,
                "dopad má být o blok níž");
        assertTrue(minY >= FEET_Y - 1.1,
                "bot nesmí propadnout do mezery, minY=" + minY);
    }

    /** Dvě rohové plošiny; cílová o blok níž – diagonální skok s klesáním. */
    @Test
    void diagonalniSprintSkokSDopademONizsi() {
        FakeWorldView world = new FakeWorldView(0);
        for (int x = -2; x <= 0; x++) {
            for (int z = -2; z <= 0; z++) {
                world.set(x, FLOOR, z, FakeWorldView.SOLID);
            }
        }
        for (int x = 2; x <= 4; x++) {
            for (int z = 2; z <= 4; z++) {
                world.set(x, FLOOR - 1, z, FakeWorldView.SOLID);
            }
        }
        BotPhysics physics = new BotPhysics(world, new Vec3(-1.5, FEET_Y, -1.5));

        Vec3 diag = new Vec3(1, 0, 1).normalized();
        boolean crossed = false;
        for (int i = 0; i < 300 && !crossed; i++) {
            Vec3 ahead = physics.position().add(diag.mul(0.7));
            boolean edge = !world.traitsAt(ahead.toBlockPos().down()).solid();
            boolean jump = physics.onGround() && edge;
            physics.step(new MoveInput(diag, true, jump, false));
            crossed = physics.onGround()
                    && physics.position().x() > 2.0 && physics.position().z() > 2.0;
        }

        assertTrue(crossed, "bot má přeskočit rohovou mezeru s dopadem níž, pos=" + physics.position());
        assertEquals(FEET_Y - 1, physics.position().y(), 0.05, "dopad má být o blok níž");
    }

    /** Dvě rohové plošiny; cílová o blok výš – diagonální parkour výskok. */
    @Test
    void diagonalniParkourVyskokPresRoh() {
        FakeWorldView world = new FakeWorldView(0);
        for (int x = -2; x <= 0; x++) {
            for (int z = -2; z <= 0; z++) {
                world.set(x, FLOOR, z, FakeWorldView.SOLID);
            }
        }
        for (int x = 2; x <= 4; x++) {
            for (int z = 2; z <= 4; z++) {
                world.set(x, FLOOR + 1, z, FakeWorldView.SOLID);
            }
        }
        BotPhysics physics = new BotPhysics(world, new Vec3(-1.5, FEET_Y, -1.5));

        Vec3 diag = new Vec3(1, 0, 1).normalized();
        boolean crossed = false;
        for (int i = 0; i < 300 && !crossed; i++) {
            Vec3 ahead = physics.position().add(diag.mul(0.7));
            boolean edge = !world.traitsAt(ahead.toBlockPos().down()).solid();
            boolean jump = physics.onGround() && edge;
            physics.step(new MoveInput(diag, true, jump, false));
            crossed = physics.onGround()
                    && physics.position().x() > 2.0 && physics.position().z() > 2.0;
        }

        assertTrue(crossed, "bot má vyskočit přes roh na vyšší římsu, pos=" + physics.position());
        assertEquals(FEET_Y + 1, physics.position().y(), 0.05, "dopad má být o blok výš");
    }

    @Test
    void padDoPrasanuNezrani() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Prašan 3 bloky vysoký v místě dopadu.
        for (int y = FLOOR + 1; y <= FLOOR + 3; y++) {
            world.set(0, y, 0, FakeWorldView.POWDER);
        }
        BotPhysics physics = new BotPhysics(world, new Vec3(0.5, FEET_Y + 10, 0.5));

        boolean landed = false;
        boolean waded = false;
        for (int i = 0; i < 400 && !landed; i++) {
            physics.step(MoveInput.IDLE);
            waded |= physics.inPowderSnow();
            landed = physics.landedThisTick();
        }

        assertTrue(waded, "bot měl propadnout prašanem");
        assertTrue(landed, "bot má klesnout až na dno");
        assertEquals(0, physics.lastFallDamage(),
                "prašan pád utlumí, damage=" + physics.lastFallDamage());
    }

    @Test
    void prasanZpomalujeBrozeni() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Vrstva prašanu na zemi přes x = 0..3.
        for (int x = 0; x <= 3; x++) {
            for (int z = -1; z <= 1; z++) {
                world.set(x, FLOOR + 1, z, FakeWorldView.POWDER);
            }
        }
        BotPhysics physics = new BotPhysics(world, new Vec3(-1.5, FEET_Y, 0.5));

        MoveInput east = MoveInput.walk(new Vec3(1, 0, 0));
        boolean waded = false;
        int ticks = 0;
        while (ticks < 400 && physics.position().x() < 4.5) {
            physics.step(east);
            waded |= physics.inPowderSnow();
            ticks++;
        }

        assertTrue(waded, "bot se měl brodit prašanem");
        assertTrue(physics.position().x() >= 4.5, "bot se má prodrat ven, x=" + physics.position().x());
        assertTrue(ticks > 40, "brodění má být znatelně pomalejší než chůze, ticks=" + ticks);
    }

    @Test
    void skokemStoupaPrasanemVzhuru() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Hluboký prašan – sloupec 3 bloky.
        for (int y = FLOOR + 1; y <= FLOOR + 3; y++) {
            world.set(0, y, 0, FakeWorldView.POWDER);
        }
        BotPhysics physics = new BotPhysics(world, new Vec3(0.5, FEET_Y, 0.5));

        MoveInput jump = new MoveInput(Vec3.ZERO, false, true, false);
        double maxY = FEET_Y;
        for (int i = 0; i < 120; i++) {
            physics.step(jump);
            maxY = Math.max(maxY, physics.position().y());
        }

        assertTrue(maxY >= FEET_Y + 2.5,
                "držením skoku má bot vystoupat prašanem, maxY=" + maxY);
    }

    @Test
    void knockbackNastaviRychlost() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        BotPhysics physics = new BotPhysics(world, new Vec3(0.5, FEET_Y, 0.5));
        physics.step(MoveInput.IDLE); // usadit na zem

        physics.setVelocity(new Vec3(0.5, 0.4, 0));
        physics.step(MoveInput.IDLE);

        assertTrue(physics.position().x() > 0.5, "knockback má bota posunout");
        assertTrue(physics.position().y() > FEET_Y, "knockback má bota nadzvednout");
    }

    /** Platforma bloků na úrovni FLOOR pro x,z v [−2..2]; kolem prázdno až dolů. */
    private static FakeWorldView platform() {
        FakeWorldView world = new FakeWorldView(0); // podlaha hluboko dole
        for (int x = -2; x <= 2; x++) {
            for (int z = -2; z <= 2; z++) {
                world.set(x, FLOOR, z, FakeWorldView.SOLID);
            }
        }
        return world;
    }

    @Test
    void plizeniNespadneZHrany() {
        BotPhysics physics = new BotPhysics(platform(), new Vec3(0.5, FEET_Y, 0.5));

        // Plížení k východní hraně platformy (blok x=2 končí na x=3.0).
        MoveInput sneakEast = new MoveInput(new Vec3(1, 0, 0), false, false, true);
        for (int i = 0; i < 400; i++) {
            physics.step(sneakEast);
        }

        assertTrue(physics.onGround(), "plížící se bot má zůstat na platformě");
        assertTrue(physics.position().y() >= FEET_Y - 0.05,
                "plížící se bot nesmí spadnout z hrany, y=" + physics.position().y());
        assertTrue(physics.position().x() < 3.4,
                "bot se má zastavit u hrany, x=" + physics.position().x());
    }

    @Test
    void chuzeBezPlizeniZHranySpadne() {
        BotPhysics physics = new BotPhysics(platform(), new Vec3(0.5, FEET_Y, 0.5));

        // Bez plížení bot z hrany sejde a padá do prázdna.
        MoveInput walkEast = MoveInput.walk(new Vec3(1, 0, 0));
        for (int i = 0; i < 60; i++) {
            physics.step(walkEast);
        }

        assertTrue(physics.position().y() < FLOOR - 1,
                "bez plížení má bot spadnout z platformy, y=" + physics.position().y());
    }

    @Test
    void dopadZVysokaZraniBota() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        BotPhysics physics = new BotPhysics(world, new Vec3(0.5, FEET_Y + 10, 0.5));

        boolean landed = false;
        for (int i = 0; i < 120 && !landed; i++) {
            physics.step(MoveInput.IDLE);
            landed = physics.landedThisTick();
        }

        assertTrue(landed, "bot má dopadnout na zem");
        assertTrue(physics.lastFallDistance() >= 9.0,
                "výška pádu ~10 bloků, změřeno=" + physics.lastFallDistance());
        assertTrue(physics.lastFallDamage() >= 6,
                "pád z 10 bloků má bolet, damage=" + physics.lastFallDamage());
        assertEquals(0.0, physics.fallDistance(), EPSILON, "po dopadu se výška pádu nuluje");
    }

    @Test
    void nizkyPadNezraniBota() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        BotPhysics physics = new BotPhysics(world, new Vec3(0.5, FEET_Y + 2.5, 0.5));

        boolean landed = false;
        for (int i = 0; i < 60 && !landed; i++) {
            physics.step(MoveInput.IDLE);
            landed = physics.landedThisTick();
        }

        assertTrue(landed, "bot má dopadnout na zem");
        assertEquals(0, physics.lastFallDamage(),
                "pád do 3 bloků nezraňuje, damage=" + physics.lastFallDamage());
    }

    @Test
    void dopadNaSenoTlumiPoskozeni() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        world.set(0, FLOOR, 0, FakeWorldView.SOFT); // seno v místě dopadu

        BotPhysics physics = new BotPhysics(world, new Vec3(0.5, FEET_Y + 10, 0.5));

        boolean landed = false;
        for (int i = 0; i < 120 && !landed; i++) {
            physics.step(MoveInput.IDLE);
            landed = physics.landedThisTick();
        }

        assertTrue(landed, "bot má dopadnout na seno");
        assertTrue(physics.lastFallDistance() >= 9.0,
                "výška pádu ~10 bloků, změřeno=" + physics.lastFallDistance());
        assertTrue(physics.lastFallDamage() <= 2,
                "seno má ztlumit poškození na ~20 %, damage=" + physics.lastFallDamage());
    }

    @Test
    void dopadDoVodyNezraniBota() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Hluboká vodní díra pod bodem pádu (voda FEET_Y-1 až FEET_Y-4).
        for (int y = (int) FEET_Y - 4; y <= (int) FEET_Y - 1; y++) {
            world.set(0, y, 0, FakeWorldView.WATER);
        }
        BotPhysics physics = new BotPhysics(world, new Vec3(0.5, FEET_Y + 10, 0.5));

        boolean submerged = false;
        for (int i = 0; i < 200; i++) {
            physics.step(MoveInput.IDLE);
            submerged |= physics.inWater();
        }

        assertTrue(submerged, "bot měl doletět do vody");
        assertEquals(0, physics.lastFallDamage(),
                "dopad do vody netlumí poškození, damage=" + physics.lastFallDamage());
    }

    // ---------------------------------------------------------------- terén 2.0

    @Test
    void naDeskuVyjdeStepUpemBezSkoku() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        for (int z = -2; z <= 2; z++) {
            world.set(3, (int) FEET_Y, z, FakeWorldView.SLAB_BOTTOM);
        }
        BotPhysics physics = new BotPhysics(world, new Vec3(0.5, FEET_Y, 0.5));

        MoveInput east = MoveInput.walk(new Vec3(1, 0, 0));
        for (int i = 0; i < 80 && physics.position().x() < 3.5; i++) {
            physics.step(east);
        }

        assertTrue(physics.position().x() >= 3.5,
                "bot má vyjít na desku bez skoku, x=" + physics.position().x());
        assertEquals(FEET_Y + 0.5, physics.position().y(), 0.05,
                "nohy mají stát na horní ploše desky");
    }

    @Test
    void schodyVyjdeChuziBezSkoku() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Schod na x=3, terasa plné bloky od x=4.
        for (int z = -2; z <= 2; z++) {
            world.set(3, (int) FEET_Y, z, FakeWorldView.STAIR_EAST);
            world.set(4, (int) FEET_Y, z, FakeWorldView.SOLID);
            world.set(5, (int) FEET_Y, z, FakeWorldView.SOLID);
        }
        BotPhysics physics = new BotPhysics(world, new Vec3(0.5, FEET_Y, 0.5));

        MoveInput east = MoveInput.walk(new Vec3(1, 0, 0));
        for (int i = 0; i < 120 && physics.position().x() < 4.5; i++) {
            physics.step(east);
        }

        assertTrue(physics.position().x() >= 4.5,
                "bot má vyjít schody pouhou chůzí, x=" + physics.position().x());
        assertEquals(FEET_Y + 1, physics.position().y(), 0.05,
                "bot má stát na terase o blok výš");
    }

    @Test
    void plotNepreleze() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Plot (1,5 bloku) na x=3 – step-up 0,6 nestačí a skok 1,25 taky ne.
        for (int z = -2; z <= 2; z++) {
            world.set(3, (int) FEET_Y, z, FakeWorldView.FENCE);
        }
        BotPhysics physics = new BotPhysics(world, new Vec3(0.5, FEET_Y, 0.5));

        for (int i = 0; i < 120; i++) {
            physics.step(MoveInput.of(new Vec3(1, 0, 0), false, physics.onGround()));
        }

        assertTrue(physics.position().x() < 3.0,
                "bot nesmí přelézt plot ani skokem, x=" + physics.position().x());
    }

    @Test
    void pavucinaZbrzdiPadIBota() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        // Pavučina těsně nad zemí pod bodem pádu. Klesání pavučinou je
        // vanilla ~0.004 bloku/tick – propad jedné buňky trvá stovky ticků.
        world.set(0, (int) FEET_Y, 0, FakeWorldView.WEB);

        BotPhysics physics = new BotPhysics(world, new Vec3(0.5, FEET_Y + 8, 0.5));

        boolean caught = false;
        for (int i = 0; i < 800 && !physics.onGround(); i++) {
            physics.step(MoveInput.IDLE);
            caught |= physics.inWeb();
        }

        assertTrue(caught, "bot má proletět pavučinou");
        assertTrue(physics.onGround(), "bot má nakonec dopadnout na zem");
        assertEquals(0, physics.lastFallDamage(),
                "pavučina nuluje pádové poškození, damage=" + physics.lastFallDamage());
    }

    @Test
    void ledKloužeVicNezKamen() {
        FakeWorldView stoneWorld = new FakeWorldView(FLOOR);
        FakeWorldView iceWorld = new FakeWorldView(FLOOR);
        for (int x = -2; x <= 12; x++) {
            for (int z = -2; z <= 2; z++) {
                iceWorld.set(x, FLOOR, z, FakeWorldView.ICE);
            }
        }
        double stoneCoast = coastDistance(stoneWorld);
        double iceCoast = coastDistance(iceWorld);

        assertTrue(iceCoast > stoneCoast * 2,
                "na ledu má bot dojíždět výrazně dál: led=" + iceCoast + " kámen=" + stoneCoast);
    }

    /** Rozjezd 20 ticků na východ, pak 30 ticků bez vstupu; vrací ujeté „dojíždění". */
    private static double coastDistance(FakeWorldView world) {
        BotPhysics physics = new BotPhysics(world, new Vec3(0.5, FEET_Y, 0.5));
        MoveInput east = MoveInput.walk(new Vec3(1, 0, 0));
        for (int i = 0; i < 20; i++) {
            physics.step(east);
        }
        double start = physics.position().x();
        for (int i = 0; i < 30; i++) {
            physics.step(MoveInput.IDLE);
        }
        return physics.position().x() - start;
    }

    @Test
    void soulSandZpomalujeChuzi() {
        FakeWorldView stoneWorld = new FakeWorldView(FLOOR);
        FakeWorldView slowWorld = new FakeWorldView(FLOOR);
        for (int x = -2; x <= 12; x++) {
            for (int z = -2; z <= 2; z++) {
                slowWorld.set(x, FLOOR, z, FakeWorldView.SOUL_SAND);
            }
        }
        MoveInput east = MoveInput.walk(new Vec3(1, 0, 0));

        BotPhysics onStone = new BotPhysics(stoneWorld, new Vec3(0.5, FEET_Y, 0.5));
        BotPhysics onSand = new BotPhysics(slowWorld, new Vec3(0.5, FEET_Y, 0.5));
        for (int i = 0; i < 40; i++) {
            onStone.step(east);
            onSand.step(east);
        }
        double stoneDist = onStone.position().x() - 0.5;
        double sandDist = onSand.position().x() - 0.5;

        assertTrue(sandDist < stoneDist * 0.6,
                "soul sand má chůzi výrazně zpomalit: sand=" + sandDist + " kámen=" + stoneDist);
    }

    @Test
    void zavreneDvereFyzickyBlokuji() {
        FakeWorldView world = new FakeWorldView(FLOOR);
        for (int z = -2; z <= 2; z++) {
            world.set(3, (int) FEET_Y, z, FakeWorldView.DOOR_CLOSED);
            world.set(3, (int) FEET_Y + 1, z, FakeWorldView.DOOR_CLOSED);
        }
        BotPhysics physics = new BotPhysics(world, new Vec3(0.5, FEET_Y, 0.5));

        MoveInput east = MoveInput.walk(new Vec3(1, 0, 0));
        for (int i = 0; i < 60; i++) {
            physics.step(east);
        }

        assertTrue(physics.position().x() < 3.0,
                "zavřené dveře mají bota zastavit, x=" + physics.position().x());
    }
}
