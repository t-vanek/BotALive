package dev.botalive.core.build;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.WorldView;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Silniční síť uvnitř sídla – hlavní ulice od návsi k domům, u města navíc
 * obvodový okruh. Čisté plánování nad {@link WorldView}, idempotentní:
 * stejně jako {@link VillageDecor} se dusá <b>jen na {@link Material#GRASS_BLOCK}</b>,
 * takže plán nikdy nepřepíše podlahu domu, políčko ani vodu a hotová
 * cesta se neplánuje znovu – tentýž plán slouží prvotní stavbě i pozdější
 * údržbě/růstu (nové parcely = nová tráva k udusání).
 *
 * <p>Geometrie navazuje na {@link PlotLayout}: parcely leží na čtvercových
 * prstencích kolem návsi, střed parcely {@code index} je v
 * {@code center + cell × spacing}. Ke každé obsazené parcele vede „L" z návsi:
 * napřed po hlavní ose (té s větší složkou) k sloupci/řádku parcely, pak
 * kolmé žebro k domu. Sjednocení těchto tras přes všechny domy dá souvislou
 * síť – páteřní ulice se sdílejí, žebra ne. Náves samotná (dům zakladatele)
 * se nedusá: leží na prkenné/kamenné podlaze, kterou filtr trávy přeskočí.</p>
 */
public final class SettlementRoads {

    private SettlementRoads() {
    }

    /**
     * Naplánuje dusání silniční sítě sídla.
     *
     * @param world       pohled na svět
     * @param center      náves (střed sídla)
     * @param spacing     rozestup parcel v mřížce (bloky)
     * @param plotOrigins originy půdorysů obsazených parcel ({@code null} prvky
     *                    i parcela na návsi se ignorují)
     * @param ringRoad    přidat obvodový okruh po vnějším prstenci (města)
     * @param maxSteps    strop kroků pro jednu seanci (zbytek příště – plán je
     *                    idempotentní)
     * @return kroky dusání seřazené od návsi ven (může být prázdné)
     */
    public static List<VillageDecor.Step> plan(WorldView world, BlockPos center, int spacing,
                                               Collection<BlockPos> plotOrigins,
                                               boolean ringRoad, int maxSteps) {
        if (world == null || center == null || spacing <= 0 || maxSteps <= 0) {
            return List.of();
        }
        Set<BlockPos> cells = new LinkedHashSet<>();
        int maxRing = 0;
        for (BlockPos origin : plotOrigins) {
            if (origin == null) {
                continue;
            }
            // Zpětné odvození buňky prstence: roh parcely se přichytí na nejbližší
            // uzel mřížky. Nezávislé na velikosti stavby – roh leží vždy blíž než
            // půl rozestupu k uzlu (půdorys < rozestup), takže zaokrouhlení trefí
            // správnou buňku pro domek 4×4 i pro širší generovaný dům.
            int dx = Math.round((float) (origin.x() - center.x()) / spacing);
            int dz = Math.round((float) (origin.z() - center.z()) / spacing);
            if (dx == 0 && dz == 0) {
                continue; // parcela na návsi – žádná ulice k sobě samé
            }
            int px = center.x() + dx * spacing;
            int pz = center.z() + dz * spacing;
            if (Math.abs(dx) >= Math.abs(dz)) {
                // Ven po ose X (hlavní ulice), pak žebro v Z k domu.
                addLine(cells, center.y(), center.x(), center.z(), px, center.z());
                addLine(cells, center.y(), px, center.z(), px, pz);
            } else {
                // Ven po ose Z, pak žebro v X.
                addLine(cells, center.y(), center.x(), center.z(), center.x(), pz);
                addLine(cells, center.y(), center.x(), pz, px, pz);
            }
            maxRing = Math.max(maxRing, Math.max(Math.abs(dx), Math.abs(dz)));
        }
        // Městský okruh: obvodový čtverec po vnějším obsazeném prstenci.
        if (ringRoad && maxRing >= 1) {
            int r = maxRing * spacing;
            int x0 = center.x() - r;
            int x1 = center.x() + r;
            int z0 = center.z() - r;
            int z1 = center.z() + r;
            addLine(cells, center.y(), x0, z0, x1, z0);
            addLine(cells, center.y(), x0, z1, x1, z1);
            addLine(cells, center.y(), x0, z0, x0, z1);
            addLine(cells, center.y(), x1, z0, x1, z1);
        }
        // Od návsi ven – síť roste ze středu a rozdělaná vypadá záměrně.
        List<BlockPos> ordered = new ArrayList<>(cells);
        ordered.sort(Comparator.comparingDouble(p -> p.distanceSquared(center)));
        List<VillageDecor.Step> steps = new ArrayList<>();
        for (BlockPos cell : ordered) {
            if (steps.size() >= maxSteps) {
                break;
            }
            BlockPos ground = VillageDecor.groundAt(world, cell.x(), center.y(), cell.z());
            if (ground != null && world.materialAt(ground) == Material.GRASS_BLOCK) {
                steps.add(new VillageDecor.Step(true, ground));
            }
        }
        return steps;
    }

    /**
     * Přidá buňky osové úsečky (jedna ze složek je konstantní) do množiny.
     * Y je jednotné (návsi) – dedup i řazení pak běží čistě v rovině XZ.
     */
    private static void addLine(Set<BlockPos> cells, int y, int x0, int z0, int x1, int z1) {
        int sx = Integer.signum(x1 - x0);
        int sz = Integer.signum(z1 - z0);
        int x = x0;
        int z = z0;
        cells.add(new BlockPos(x, y, z));
        while (x != x1 || z != z1) {
            if (x != x1) {
                x += sx;
            }
            if (z != z1) {
                z += sz;
            }
            cells.add(new BlockPos(x, y, z));
        }
    }
}
