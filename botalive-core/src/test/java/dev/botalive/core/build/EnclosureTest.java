package dev.botalive.core.build;

import dev.botalive.core.testutil.FakeWorldView;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Cardinal;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy plánovače obvodové bariéry – uzavřený obvod, branky, sledování terénu,
 * idempotence (hotový plot se nestaví znovu), strop kroků a rozvinutí do výšky.
 */
class EnclosureTest {

    /** Výška podlahy syntetického světa. */
    private static final int Y = 64;

    /** Obdélník 5×5 (min 0,0 – max 4,4) → obvod 2·5 + 2·5 − 4 = 16 sloupců. */
    private static List<Enclosure.Post> plan5x5(FakeWorldView world, Set<Cardinal> gates,
                                                int maxSteps) {
        return Enclosure.plan(world, new BlockPos(0, Y, 0), new BlockPos(4, Y, 4), Y,
                gates, maxSteps);
    }

    @Test
    void obvodMaSpravnyPocetSloupcuBezVnitrku() {
        List<Enclosure.Post> posts = plan5x5(new FakeWorldView(Y), Set.of(), 100);
        assertEquals(16, posts.size());
        for (Enclosure.Post p : posts) {
            int x = p.base().x();
            int z = p.base().z();
            assertTrue(x == 0 || x == 4 || z == 0 || z == 4,
                    "sloupec nesmí být uvnitř: " + p.base());
            assertEquals(Y + 1, p.base().y(), "bariéra stojí na zemi (Y+1)");
        }
    }

    @Test
    void brankaJeVeStreduPozadovaneHrany() {
        List<Enclosure.Post> posts = plan5x5(new FakeWorldView(Y), Set.of(Cardinal.NORTH), 100);
        List<Enclosure.Post> gates = posts.stream().filter(Enclosure.Post::gate).toList();
        assertEquals(1, gates.size(), "právě jedna branka na severní hraně");
        assertEquals(new BlockPos(2, Y + 1, 0), gates.get(0).base(), "střed severní hrany");
    }

    @Test
    void gateCenterProKazdouStranu() {
        assertEquals(new BlockPos(2, Y, 0), Enclosure.gateCenter(0, 0, 4, 4, Y, Cardinal.NORTH));
        assertEquals(new BlockPos(2, Y, 4), Enclosure.gateCenter(0, 0, 4, 4, Y, Cardinal.SOUTH));
        assertEquals(new BlockPos(0, Y, 2), Enclosure.gateCenter(0, 0, 4, 4, Y, Cardinal.WEST));
        assertEquals(new BlockPos(4, Y, 2), Enclosure.gateCenter(0, 0, 4, 4, Y, Cardinal.EAST));
    }

    @Test
    void vsechnyStranyDostanouBranku() {
        List<Enclosure.Post> posts = plan5x5(new FakeWorldView(Y),
                Set.of(Cardinal.NORTH, Cardinal.SOUTH, Cardinal.EAST, Cardinal.WEST), 100);
        assertEquals(4, posts.stream().filter(Enclosure.Post::gate).count());
    }

    @Test
    void bariéraKopírujeSvah() {
        FakeWorldView world = new FakeWorldView(Y);
        world.set(0, Y + 1, 0, FakeWorldView.SOLID); // roh (0,0) je o blok výš
        List<Enclosure.Post> posts = plan5x5(world, Set.of(), 100);
        assertEquals(Y + 2, baseAt(posts, 0, 0).y(), "zem o blok výš → bariéra o blok výš");
        assertEquals(Y + 1, baseAt(posts, 4, 4).y(), "rovná část beze změny");
    }

    @Test
    void hotovyPlotSeNestaviZnovu() {
        FakeWorldView world = new FakeWorldView(Y);
        // Plot už stojí v rohu (0,0) – sedí na zemi, tj. na Y+1.
        world.set(0, Y + 1, 0, Material.OAK_FENCE, FakeWorldView.FENCE);
        List<Enclosure.Post> posts = plan5x5(world, Set.of(), 100);
        assertEquals(15, posts.size());
        assertTrue(posts.stream().noneMatch(p -> p.base().x() == 0 && p.base().z() == 0),
                "sloupec s hotovým plotem se přeskočí");
    }

    @Test
    void hotovouHradbuTakePřeskočí() {
        FakeWorldView world = new FakeWorldView(Y);
        world.set(4, Y + 1, 4, Material.COBBLESTONE_WALL, FakeWorldView.FENCE);
        List<Enclosure.Post> posts = plan5x5(world, Set.of(), 100);
        assertTrue(posts.stream().noneMatch(p -> p.base().x() == 4 && p.base().z() == 4));
    }

    @Test
    void stropKrokuDrziSeanciKratkou() {
        List<Enclosure.Post> posts = plan5x5(new FakeWorldView(Y), Set.of(), 3);
        assertEquals(3, posts.size());
        // Pořadí od SZ rohu po horní hraně zleva doprava.
        assertEquals(new BlockPos(0, Y + 1, 0), posts.get(0).base());
        assertEquals(new BlockPos(1, Y + 1, 0), posts.get(1).base());
        assertEquals(new BlockPos(2, Y + 1, 0), posts.get(2).base());
    }

    @Test
    void perimetrJeUzavrenyPrstenecBezDuplicit() {
        List<BlockPos> ring = Enclosure.perimeter(0, 0, 4, 4, Y);
        assertEquals(16, ring.size());
        assertEquals(ring.size(), new HashSet<>(ring).size(), "žádné duplicity");
        for (int[] corner : new int[][]{{0, 0}, {4, 0}, {4, 4}, {0, 4}}) {
            assertTrue(ring.contains(new BlockPos(corner[0], Y, corner[1])), "roh chybí");
        }
        assertFalse(ring.contains(new BlockPos(2, Y, 2)), "vnitřek nepatří do obvodu");
    }

    @Test
    void sloupecSeRozvineDoVyskyBrankaDole() {
        Enclosure.Post gate = new Enclosure.Post(new BlockPos(2, Y + 1, 0), true);
        List<Enclosure.Placement> col = Enclosure.column(gate, 3);
        assertEquals(3, col.size());
        assertEquals(Enclosure.Cell.GATE, col.get(0).kind());
        assertEquals(new BlockPos(2, Y + 1, 0), col.get(0).pos());
        assertEquals(Enclosure.Cell.POST, col.get(1).kind());
        assertEquals(new BlockPos(2, Y + 2, 0), col.get(1).pos());
        assertEquals(Enclosure.Cell.POST, col.get(2).kind());
        assertEquals(new BlockPos(2, Y + 3, 0), col.get(2).pos());
    }

    @Test
    void plotVysky1JeJedenSloupek() {
        Enclosure.Post post = new Enclosure.Post(new BlockPos(0, Y + 1, 0), false);
        List<Enclosure.Placement> col = Enclosure.column(post, 1);
        assertEquals(1, col.size());
        assertEquals(Enclosure.Cell.POST, col.get(0).kind());
        assertEquals(new BlockPos(0, Y + 1, 0), col.get(0).pos());
    }

    @Test
    void branaVHradbeMaProchodANadprazi() {
        // Hradba výšky 3: branka dole, y=1 volné (průchod), překlad na y=2.
        Enclosure.Post gate = new Enclosure.Post(new BlockPos(2, Y + 1, 0), true);
        List<Enclosure.Placement> col = Enclosure.gateway(gate, 3);
        assertEquals(2, col.size());
        assertEquals(Enclosure.Cell.GATE, col.get(0).kind());
        assertEquals(new BlockPos(2, Y + 1, 0), col.get(0).pos());
        assertEquals(Enclosure.Cell.POST, col.get(1).kind());
        assertEquals(new BlockPos(2, Y + 3, 0), col.get(1).pos(), "překlad až nad nadpražím");
    }

    @Test
    void nizkaBranaJeJenBranka() {
        // Výška 2 (i 1): na překlad nad nadpražím není místo – zůstane jen branka.
        Enclosure.Post gate = new Enclosure.Post(new BlockPos(2, Y + 1, 0), true);
        List<Enclosure.Placement> col = Enclosure.gateway(gate, 2);
        assertEquals(1, col.size());
        assertEquals(Enclosure.Cell.GATE, col.get(0).kind());
    }

    /** Postaví plot (plaňky na Y+1) po celém obvodu 5×5. */
    private static FakeWorldView fencedField() {
        FakeWorldView world = new FakeWorldView(Y);
        for (BlockPos col : Enclosure.perimeter(0, 0, 4, 4, Y)) {
            world.set(col.x(), Y + 1, col.z(), Material.OAK_FENCE, FakeWorldView.FENCE);
        }
        return world;
    }

    @Test
    void assessCelaBarieraStoji() {
        Enclosure.Assessment a = Enclosure.assess(fencedField(),
                new BlockPos(0, Y, 0), new BlockPos(4, Y, 4), Y, Set.of());
        assertEquals(16, a.standing());
        assertEquals(0, a.missing());
        assertEquals(0.0, a.missingRatio());
    }

    @Test
    void assessParDerJePoskozeni() {
        FakeWorldView world = fencedField();
        // Vytlučeme dva sloupky (0,0) a (1,0) – zbytek stojí.
        world.set(0, Y + 1, 0, FakeWorldView.AIRLIKE);
        world.set(1, Y + 1, 0, FakeWorldView.AIRLIKE);
        Enclosure.Assessment a = Enclosure.assess(world,
                new BlockPos(0, Y, 0), new BlockPos(4, Y, 4), Y, Set.of());
        assertEquals(14, a.standing());
        assertEquals(2, a.missing());
    }

    @Test
    void assessNovaBarieraVsechnoChybi() {
        Enclosure.Assessment a = Enclosure.assess(new FakeWorldView(Y),
                new BlockPos(0, Y, 0), new BlockPos(4, Y, 4), Y, Set.of());
        assertEquals(0, a.standing());
        assertEquals(16, a.missing());
    }

    @Test
    void assessPoznaStojiciBranku() {
        FakeWorldView world = fencedField();
        // Na severní bráně stojí fence gate – v realitě NEpevný (groundAt na něj
        // nevyleze), posuzuje se přes blok nad zemí.
        world.set(2, Y + 1, 0, Material.OAK_FENCE_GATE, FakeWorldView.DOOR_CLOSED);
        Enclosure.Assessment a = Enclosure.assess(world,
                new BlockPos(0, Y, 0), new BlockPos(4, Y, 4), Y, Set.of(Cardinal.NORTH));
        assertEquals(16, a.standing(), "branka se počítá jako stojící");
        assertEquals(0, a.missing());
    }

    @Test
    void assessPoznaChybejiciBranku() {
        FakeWorldView world = fencedField();
        world.set(2, Y + 1, 0, Material.AIR, FakeWorldView.AIRLIKE); // branka vytlučená (i materiál pryč)
        Enclosure.Assessment a = Enclosure.assess(world,
                new BlockPos(0, Y, 0), new BlockPos(4, Y, 4), Y, Set.of(Cardinal.NORTH));
        assertEquals(15, a.standing());
        assertEquals(1, a.missing(), "chybějící branka = díra");
    }

    /** Base sloupce na dané XZ (musí existovat). */
    private static BlockPos baseAt(List<Enclosure.Post> posts, int x, int z) {
        return posts.stream().filter(p -> p.base().x() == x && p.base().z() == z)
                .map(Enclosure.Post::base).findFirst().orElseThrow();
    }
}
