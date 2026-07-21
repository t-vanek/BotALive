package dev.botalive.core.build.plan;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Cardinal;
import dev.botalive.core.world.BlockTraits;
import dev.botalive.core.world.WorldView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Výběr <b>výšky</b> staveniště: půdorys stavby je daný (parcela, projekt
 * sídla), ale úroveň podlahy je jen odhad – katastr vesnice rozdává parcely
 * s Y návsi ({@code PlotLayout.plotOrigin}), takže na svahu leží návrh klidně
 * osm bloků nad zemí nebo pod ní. Bez korekce se plán rozvine do vzduchu:
 * {@code groundColumns} mají jednu vrstvu, mezeru nepodepřou, pokládka nemá
 * oporu a stavba nikdy nedoběhne.
 *
 * <p>Utilita je čistá funkce nad {@link WorldView} a {@link Blueprint} –
 * geometrii si bere z blueprintu samotného ({@code groundColumns} = půdorys
 * podlahy, {@code clearVolume} = objem, který musí být volný), takže platí
 * stejně pro dům, studnu, sýpku i dílnu. Žádná závislost na bukkitu ani
 * navigaci; bezpečné volat na tick vlákně.</p>
 *
 * <p>Kandidáti na výšku se zkoušejí ve dvou vlnách: nejdřív <b>navržená</b>
 * výška ±2 (rozestavěná stavba se musí trefit přesně do svého originu, proto
 * je posun 0 první), pak výška <b>odvozená z terénu</b> pod půdorysem ±2.
 * Druhá vlna je to, co zvládne vícebloková mezera; první drží paritu
 * s dosavadním chováním a resume. Nenačtené chunky ({@link BlockTraits#UNKNOWN})
 * hlásí {@link #COST_UNKNOWN} – z dálky se staveniště neposuzuje a parcela se
 * nikdy netombstonuje jen proto, že ji bot ještě neviděl.</p>
 */
public final class SiteFinder {

    /** Cena staveniště: nepoužitelné (tekutina, moc úprav, úpravy zakázané). */
    public static final int COST_INVALID = -1;
    /** Cena staveniště: nenačtené chunky – nelze posoudit, nikdy netombstonovat. */
    public static final int COST_UNKNOWN = -2;

    /** Kolik sloupců podlahy smí chybět (zásyp), aby se staveniště srovnalo. */
    private static final int MAX_FILLS = 4;
    /** Kolik bloků smí v objemu stavby přebývat (výkop). */
    private static final int MAX_DIGS = 8;
    /** Pořadí zkoušených výškových posunů (resume musí zkusit 0 první). */
    private static final int[] DY_ORDER = {0, -1, 1, -2, 2};
    /**
     * Svislý rozsah hledání povrchu kolem navržené výšky (bloky).
     *
     * <p>Není to jen rozpočet skenu, ale i <b>rozumná mez</b>: parcela, jejíž
     * terén je od úrovně sídla dál než tohle, není staveniště k srovnání –
     * je to sráz. Studna vesnice nepatří třicet bloků nad náves, takže se
     * taková parcela úmyslně nenajde a volající ji přesune jinam (živě
     * pozorováno na vesnici pod horou, 2026-07-21).</p>
     */
    private static final int SURFACE_SCAN = 24;

    private SiteFinder() {
    }

    /**
     * Cena úprav staveniště: {@code 2 ×} zásypy podlahy {@code +} výkopy
     * v objemu stavby (zásyp stojí i materiál).
     *
     * <p>Pozice ze {@code skip} se jako výkop nepočítají – tím se při návratu
     * na rozestavěné staveniště nezaúčtují vlastní už položené zdi.</p>
     *
     * @param world        pohled na svět
     * @param blueprint    geometrie stavby
     * @param origin       zkoušený roh půdorysu (úroveň podlahy)
     * @param facing       orientace stavby
     * @param skip         pozice, které se nepočítají jako překážka
     * @param terraforming smí bot upravovat terén?
     * @return cena, {@link #COST_INVALID} nebo {@link #COST_UNKNOWN}
     */
    public static int cost(WorldView world, Blueprint blueprint, BlockPos origin,
                           Cardinal facing, Set<BlockPos> skip, boolean terraforming) {
        int fills = 0;
        // Podlaha: pod celým půdorysem musí být pevno (díry se zasypou).
        for (BlockPos ground : blueprint.groundColumns(origin, facing)) {
            BlockTraits traits = world.traitsAt(ground);
            if (traits == BlockTraits.UNKNOWN) {
                return COST_UNKNOWN;
            }
            if (traits.liquid()) {
                return COST_INVALID;
            }
            if (!traits.solid()) {
                fills++;
                if (fills > MAX_FILLS || !terraforming) {
                    return COST_INVALID;
                }
            }
        }
        int digs = 0;
        // Objem stavby: co v něm přebývá, se musí vytěžit.
        for (BlockPos space : blueprint.clearVolume(origin, facing)) {
            if (skip.contains(space)) {
                continue;
            }
            BlockTraits traits = world.traitsAt(space);
            if (traits == BlockTraits.UNKNOWN) {
                return COST_UNKNOWN;
            }
            if (traits.liquid()) {
                return COST_INVALID;
            }
            if (traits.solid()) {
                digs++;
                if (digs > MAX_DIGS || !terraforming) {
                    return COST_INVALID;
                }
            }
        }
        return fills * 2 + digs; // zásyp stojí i materiál
    }

    /**
     * Nejlepší (nejlevnější) cena staveniště přes všechny zkoušené výšky –
     * pro <b>výběr</b> parcely, typicky z dálky nad studenou cache.
     *
     * @param world        pohled na svět
     * @param blueprint    geometrie stavby
     * @param suggested    navržený roh půdorysu
     * @param facing       orientace stavby
     * @param terraforming smí bot upravovat terén?
     * @return nejlepší cena, {@link #COST_INVALID} (žádná výška nevyhovuje)
     *         nebo {@link #COST_UNKNOWN} (nenačtený chunk – neposuzovat)
     */
    public static int bestCost(WorldView world, Blueprint blueprint, BlockPos suggested,
                               Cardinal facing, boolean terraforming) {
        int best = COST_INVALID;
        for (BlockPos candidate : candidates(world, blueprint, suggested, facing)) {
            int cost = cost(world, blueprint, candidate, facing, Set.of(), terraforming);
            if (cost == COST_UNKNOWN) {
                return COST_UNKNOWN;
            }
            if (cost >= 0 && (best < 0 || cost < best)) {
                best = cost;
            }
        }
        return best;
    }

    /**
     * Použitelný roh půdorysu se <b>srovnanou výškou</b> – volá se až na místě,
     * s načtenými chunky. Vrací první vyhovující výšku v pořadí kandidátů
     * (navržená ±2, pak terénní ±2), ne nejlevnější: rozestavěná stavba tak
     * dostane zpátky přesně svůj origin a naváže world-diffem.
     *
     * <p>Vlastní bloky stavby se jako překážka nepočítají – návrat na
     * rozestavěné staveniště si vlastní zdi nezbourá ani si jimi staveniště
     * neprodraží.</p>
     *
     * @param world        pohled na svět (načtené okolí staveniště)
     * @param blueprint    geometrie stavby
     * @param suggested    navržený roh půdorysu (z katastru / projektu)
     * @param facing       orientace stavby
     * @param terraforming smí bot upravovat terén?
     * @return roh půdorysu ke stavbě, nebo prázdné (staveniště nepoužitelné)
     */
    public static Optional<BlockPos> usableOrigin(WorldView world, Blueprint blueprint,
                                                  BlockPos suggested, Cardinal facing,
                                                  boolean terraforming) {
        for (BlockPos candidate : candidates(world, blueprint, suggested, facing)) {
            Set<BlockPos> own = structureOf(blueprint, candidate, facing);
            if (cost(world, blueprint, candidate, facing, own, terraforming) >= 0) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    // ---------------------------------------------------------------- pomocné

    /**
     * Zkoušené výšky v pořadí: navržená ±2 (parita s dosavadním chováním,
     * posun 0 první kvůli resume), pak výška odvozená z terénu pod půdorysem
     * ±2 (to je, co srovná víceblokovou mezeru na svahu). Duplicity vypadnou.
     */
    private static List<BlockPos> candidates(WorldView world, Blueprint blueprint,
                                             BlockPos suggested, Cardinal facing) {
        Set<Integer> levels = new LinkedHashSet<>();
        for (int dy : DY_ORDER) {
            levels.add(suggested.y() + dy);
        }
        Integer terrain = terrainLevel(world, blueprint, suggested, facing);
        if (terrain != null) {
            for (int dy : DY_ORDER) {
                levels.add(terrain + dy);
            }
        }
        List<BlockPos> result = new ArrayList<>(levels.size());
        for (int y : levels) {
            result.add(new BlockPos(suggested.x(), y, suggested.z()));
        }
        return result;
    }

    /**
     * Úroveň podlahy odvozená z terénu pod půdorysem – medián povrchů
     * jednotlivých sloupců. Medián (ne průměr ani minimum) drží stavbu na
     * převažující úrovni terénu: osamělý sráz, strom ani díra u kraje
     * půdorysu s ním nehnou.
     *
     * @return úroveň podlahy, nebo {@code null} když terén nejde odečíst
     */
    private static Integer terrainLevel(WorldView world, Blueprint blueprint,
                                        BlockPos suggested, Cardinal facing) {
        List<Integer> levels = new ArrayList<>();
        // groundColumns leží blok POD podlahou – povrch hledáme v jejich sloupcích.
        for (BlockPos ground : blueprint.groundColumns(suggested, facing)) {
            Integer surface = surfaceLevel(world, ground.x(), ground.z(), suggested.y());
            if (surface != null) {
                levels.add(surface);
            }
        }
        if (levels.isEmpty()) {
            return null;
        }
        levels.sort(Integer::compareTo);
        return levels.get(levels.size() / 2);
    }

    /**
     * Povrch ve sloupci: nejvyšší úroveň v dosahu skenu, kde je pod nohama
     * pevno a v buňce samotné volno. Vrací se úroveň <b>podlahy</b> (buňka nad
     * pevnou zemí) – přesně to, co blueprint čeká jako {@code origin.y()}.
     *
     * @return úroveň podlahy, nebo {@code null} (nenačteno / voda / nic pevného)
     */
    private static Integer surfaceLevel(WorldView world, int x, int z, int refY) {
        for (int y = refY + SURFACE_SCAN; y >= refY - SURFACE_SCAN; y--) {
            BlockTraits below = world.traitsAt(new BlockPos(x, y - 1, z));
            if (below == BlockTraits.UNKNOWN) {
                return null; // studená cache – terén se odsud posoudit nedá
            }
            if (!below.solid()) {
                continue;
            }
            BlockTraits here = world.traitsAt(new BlockPos(x, y, z));
            if (here == BlockTraits.UNKNOWN) {
                return null;
            }
            // Pevno pod nohama a volno v buňce = povrch. Voda v buňce povrch
            // není (na hladinu se nestaví) – hledá se dál dolů.
            if (!here.solid() && !here.liquid()) {
                return y;
            }
        }
        return null;
    }

    /** Bloky stavby samotné – při posuzování staveniště se nepočítají. */
    private static Set<BlockPos> structureOf(Blueprint blueprint, BlockPos origin,
                                             Cardinal facing) {
        Set<BlockPos> result = new HashSet<>();
        for (PlacementCell cell : blueprint.cells(origin, facing)) {
            result.add(cell.pos());
        }
        return result;
    }
}
