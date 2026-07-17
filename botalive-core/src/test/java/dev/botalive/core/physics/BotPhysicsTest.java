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
        // Schod výšky 1 na x=3.
        for (int z = -2; z <= 2; z++) {
            world.set(3, (int) FEET_Y, z, FakeWorldView.SOLID);
        }
        BotPhysics physics = new BotPhysics(world, new Vec3(0.5, FEET_Y, 0.5));

        for (int i = 0; i < 120 && physics.position().x() < 4.0; i++) {
            physics.step(MoveInput.of(new Vec3(1, 0, 0), false, physics.onGround()));
        }

        assertTrue(physics.position().x() >= 4.0,
                "bot má skokem vylézt na blok, x=" + physics.position().x());
        assertEquals(FEET_Y + 1, physics.position().y(), 0.05, "bot má stát na schodu");
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
}
