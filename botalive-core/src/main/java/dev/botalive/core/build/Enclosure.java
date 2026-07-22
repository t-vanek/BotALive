package dev.botalive.core.build;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Cardinal;
import dev.botalive.core.world.WorldView;

import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Uzavřená obvodová bariéra po terénu – čistá geometrie plotu/hradby kolem
 * obdélníkové oblasti, jednotkově testovatelná. Základ pro budoucí obehnání
 * parcel domů a stád <b>plotem</b> a sídel <b>hradbami</b> (viz
 * {@code docs/SETTLEMENTS_GROWTH.md}); samotné stavění je navazující krok.
 *
 * <p>Sestra {@link SettlementRoads}: stejný model „čistý plán nad
 * {@link WorldView}, idempotentní, strop kroků na seanci". Zatímco cesty se
 * <b>dusají po trávě</b>, bariéra se <b>staví po obvodu</b> a její výška/materiál
 * (plot vs. hradba) jsou věcí vykonavatele – {@link Enclosure} řeší jen
 * geometrii: <b>kde</b> stojí sloupce a <b>kde je branka</b>.</p>
 *
 * <p>Idempotence dělá plán bezúdržbovým: sloupec, kde už bariéra stojí, se
 * přeskočí (na {@code base} není průchozí blok), takže tentýž plán slouží
 * prvotní stavbě i pozdější opravě/růstu a nikdy nepřidá druhý plot přes první.
 * Terén se sleduje po sloupcích přes {@link VillageDecor#groundAt} – bariéra
 * kopíruje svah jako cesta.</p>
 */
public final class Enclosure {

    /** Co se v daném místě sloupce klade: sloupek bariéry, nebo branka. */
    public enum Cell {
        /** Blok bariéry (plaňka plotu / kámen hradby). */
        POST,
        /** Branka v bariéře (fence gate) – průchod dovnitř/ven. */
        GATE
    }

    /**
     * Jeden obvodový sloupec bariéry.
     *
     * @param base spodní blok bariéry (těsně nad zemí, tj. {@code ground.up()})
     * @param gate {@code true} když je v tomto sloupci branka (spodní blok)
     */
    public record Post(BlockPos base, boolean gate) {
    }

    /**
     * Konkrétní blok k položení (rozvinutý sloupec do výšky).
     *
     * @param pos  světová pozice
     * @param kind druh (sloupek / branka)
     */
    public record Placement(BlockPos pos, Cell kind) {
    }

    private Enclosure() {
    }

    /**
     * Naplánuje obvodovou bariéru kolem obdélníkové oblasti.
     *
     * <p>{@code min}/{@code max} jsou <b>inkluzivní</b> XZ rohy ohrazené oblasti;
     * bariéra běží po jejím obvodu (buňky, kde {@code x==minX || x==maxX ||
     * z==minZ || z==maxZ}). Sloupce se procházejí po obvodu od
     * severozápadního rohu po směru hodinových ručiček, takže rozdělaná bariéra
     * vypadá záměrně (jako síť cest od návsi).</p>
     *
     * @param world    pohled na svět (načtené okolí)
     * @param min      roh oblasti s minimálními XZ (Y se ignoruje, bere se {@code yHint})
     * @param max      roh oblasti s maximálními XZ
     * @param yHint    výška, kolem které se hledá zem každého sloupce
     * @param gates    strany, které dostanou branku ve středu hrany (může být prázdné)
     * @param maxSteps strop sloupců pro jednu seanci (zbytek příště – plán je idempotentní)
     * @return sloupce k postavení, seřazené po obvodu (může být prázdné)
     */
    public static List<Post> plan(WorldView world, BlockPos min, BlockPos max, int yHint,
                                  Set<Cardinal> gates, int maxSteps) {
        if (world == null || min == null || max == null || maxSteps <= 0) {
            return List.of();
        }
        int minX = Math.min(min.x(), max.x());
        int maxX = Math.max(min.x(), max.x());
        int minZ = Math.min(min.z(), max.z());
        int maxZ = Math.max(min.z(), max.z());
        Set<Long> gateCells = gateCells(minX, minZ, maxX, maxZ, gates);
        List<Post> posts = new ArrayList<>();
        for (BlockPos column : perimeter(minX, minZ, maxX, maxZ, yHint)) {
            if (posts.size() >= maxSteps) {
                break;
            }
            BlockPos ground = VillageDecor.groundAt(world, column.x(), yHint, column.z());
            if (ground == null) {
                continue; // sráz, jezero, ucpaný sloupec – bez opory se nestaví
            }
            // Idempotence: bariéra už tu stojí. groundAt vyleze na plot/hradbu
            // (je pevná se vzduchem nad sebou), takže „zem" je pak sama bariéra –
            // poznáme ji podle materiálu (vzor: VillageDecor pozná hotovou pochodeň).
            if (isBarrier(world.materialAt(ground))) {
                continue;
            }
            boolean gate = gateCells.contains(new BlockPos(column.x(), 0, column.z()).asLong());
            posts.add(new Post(ground.up(), gate));
        }
        return posts;
    }

    /**
     * Rozvine naplánovaný sloupec do výšky: branka (nebo první sloupek) dole,
     * sloupky nad ním. Čistá geometrie – vykonavatel z toho udělá pokládku.
     *
     * @param post   sloupec z {@link #plan}
     * @param height výška bariéry v blocích (plot = 1, hradba = 2+)
     * @return bloky k položení odspodu nahoru
     */
    public static List<Placement> column(Post post, int height) {
        List<Placement> cells = new ArrayList<>();
        int h = Math.max(1, height);
        for (int y = 0; y < h; y++) {
            Cell kind = (y == 0 && post.gate()) ? Cell.GATE : Cell.POST;
            cells.add(new Placement(post.base().offset(0, y, 0), kind));
        }
        return cells;
    }

    /**
     * Průchod ve vysoké bariéře (hradbě): <b>branka</b> dole, nad ní jeden
     * <b>volný</b> blok (nadpraží – aby se dalo projít), pak <b>překlad</b>
     * z bariéry přes zbytek výšky. Pro výšku 1–2 je to jen branka (na překlad
     * není místo). Na rozdíl od {@link #column} tak zůstane brána průchozí i
     * u hradby, která je vyšší než postava.
     *
     * @param post   sloupec s brankou z {@link #plan}
     * @param height výška bariéry v blocích
     * @return bloky k položení odspodu nahoru (branka + překlad)
     */
    public static List<Placement> gateway(Post post, int height) {
        List<Placement> cells = new ArrayList<>();
        cells.add(new Placement(post.base(), Cell.GATE));
        for (int y = 2; y < height; y++) { // y == 1 zůstává volné (průchod)
            cells.add(new Placement(post.base().offset(0, y, 0), Cell.POST));
        }
        return cells;
    }

    /**
     * Obvodové sloupce obdélníku ve světových XZ (bez dotazu na svět) – po směru
     * hodinových ručiček od severozápadního rohu. Každá buňka právě jednou.
     *
     * @param minX levý okraj (včetně)
     * @param minZ horní okraj (včetně)
     * @param maxX pravý okraj (včetně)
     * @param maxZ dolní okraj (včetně)
     * @param y    výška, kterou nesou vrácené pozice (skutečnou zem si dohledá {@link #plan})
     * @return obvodové buňky v pořadí obchůzky
     */
    public static List<BlockPos> perimeter(int minX, int minZ, int maxX, int maxZ, int y) {
        List<BlockPos> ring = new ArrayList<>();
        if (minX > maxX || minZ > maxZ) {
            return ring;
        }
        if (minX == maxX || minZ == maxZ) {
            // Zvrhlý obdélník (čára/bod) – vyplň bez duplicit.
            for (int x = minX; x <= maxX; x++) {
                for (int z = minZ; z <= maxZ; z++) {
                    ring.add(new BlockPos(x, y, z));
                }
            }
            return ring;
        }
        for (int x = minX; x <= maxX; x++) {          // horní hrana (z=minZ) zleva doprava
            ring.add(new BlockPos(x, y, minZ));
        }
        for (int z = minZ + 1; z <= maxZ; z++) {      // pravá hrana (x=maxX) shora dolů
            ring.add(new BlockPos(maxX, y, z));
        }
        for (int x = maxX - 1; x >= minX; x--) {      // dolní hrana (z=maxZ) zprava doleva
            ring.add(new BlockPos(x, y, maxZ));
        }
        for (int z = maxZ - 1; z >= minZ + 1; z--) {  // levá hrana (x=minX) zdola nahoru
            ring.add(new BlockPos(minX, y, z));
        }
        return ring;
    }

    /**
     * Střed hrany dané strany obdélníku – sem přijde branka.
     *
     * @param minX levý okraj (včetně)
     * @param minZ horní okraj (včetně)
     * @param maxX pravý okraj (včetně)
     * @param maxZ dolní okraj (včetně)
     * @param y    výška vrácené pozice
     * @param side strana (NORTH = hrana z=minZ, SOUTH = z=maxZ, WEST = x=minX, EAST = x=maxX)
     * @return pozice branky ve středu hrany
     */
    public static BlockPos gateCenter(int minX, int minZ, int maxX, int maxZ, int y,
                                      Cardinal side) {
        int midX = (minX + maxX) / 2;
        int midZ = (minZ + maxZ) / 2;
        return switch (side) {
            case NORTH -> new BlockPos(midX, y, minZ);
            case SOUTH -> new BlockPos(midX, y, maxZ);
            case WEST -> new BlockPos(minX, y, midZ);
            case EAST -> new BlockPos(maxX, y, midZ);
        };
    }

    /** Je materiál ohradní bariéra (plot, branka, hradba)? – pro idempotenci. */
    private static boolean isBarrier(Material m) {
        if (m == null) {
            return false;
        }
        String name = m.name();
        return name.endsWith("_FENCE") || name.endsWith("_FENCE_GATE")
                || name.endsWith("_WALL");
    }

    /** Množina XZ klíčů (y=0) sloupců, kde má být branka. */
    private static Set<Long> gateCells(int minX, int minZ, int maxX, int maxZ,
                                       Set<Cardinal> gates) {
        Set<Long> cells = new java.util.HashSet<>();
        if (gates == null || gates.isEmpty()) {
            return cells;
        }
        for (Cardinal side : gates) {
            BlockPos g = gateCenter(minX, minZ, maxX, maxZ, 0, side);
            cells.add(g.asLong());
        }
        return cells;
    }
}
