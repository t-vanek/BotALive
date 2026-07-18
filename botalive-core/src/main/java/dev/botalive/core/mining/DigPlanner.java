package dev.botalive.core.mining;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.WorldView;

import java.util.ArrayList;
import java.util.List;

/**
 * Čistý plánovač výkopu – rozloží cestu k zasypanému cíli na kroky.
 *
 * <p>Bot kope jako zkušený hráč: vodorovně razí štolu 1×2 (nohy + hlava),
 * dolů sestupuje <b>schodištěm</b> (krok vpřed + o blok níž) – <b>nikdy nekope
 * kolmo dolů</b> pod vlastníma nohama. Stoupání se neplánuje (žádné
 * pilířování); cíl výš než ~1 blok se považuje za nedosažitelný výkopem.</p>
 *
 * <p>Plánovač je geometrický a bez závislostí na světě – bezpečnost (láva,
 * voda) kontroluje vykonavatel před vytěžením každého bloku, protože svět se
 * během kopání mění. Díky tomu je plně jednotkově testovatelný.</p>
 */
public final class DigPlanner {

    /**
     * Jeden krok výkopu.
     *
     * @param toBreak bloky, které je nutné vytěžit (v pořadí; hlava dřív než nohy)
     * @param feet    pozice nohou bota po dokončení kroku
     */
    public record Step(List<BlockPos> toBreak, BlockPos feet) {
    }

    private DigPlanner() {
    }

    /**
     * Naplánuje výkop od nohou bota k cíli.
     *
     * <p>Cíl je pozice, kde mají skončit nohy bota – typicky blok <b>vedle</b>
     * rudy, ne ruda sama (tu pak bot vytěží normálně, už odkrytou).</p>
     *
     * @param feet     aktuální pozice nohou
     * @param target   cílová pozice nohou
     * @param maxSteps strop počtu kroků (pojistka)
     * @return kroky výkopu; prázdný seznam, když cíl nedává smysl (moc vysoko)
     */
    public static List<Step> plan(BlockPos feet, BlockPos target, int maxSteps) {
        if (target.y() > feet.y() + 1) {
            return List.of(); // nahoru se nekope (žádné pilířování)
        }
        List<Step> steps = new ArrayList<>();
        BlockPos current = feet;
        for (int i = 0; i < maxSteps; i++) {
            if (current.equals(target)) {
                return steps;
            }
            int dx = Integer.signum(target.x() - current.x());
            int dz = Integer.signum(target.z() - current.z());
            int dy = Integer.signum(target.y() - current.y());

            BlockPos next;
            List<BlockPos> toBreak = new ArrayList<>(3);
            if (dy < 0) {
                // Schodiště dolů: vpřed (preferuj delší osu, jinak +x) a o blok níž.
                int sx = dx != 0 ? dx : (dz == 0 ? 1 : 0);
                int sz = sx == 0 ? dz : 0;
                next = current.offset(sx, -1, sz);
                // Tři bloky shora dolů: hlava na staré úrovni (bot jí prochází
                // při kroku přes hranu), hlava nové úrovně, nohy nové úrovně.
                toBreak.add(next.up().up());
                toBreak.add(next.up());
                toBreak.add(next);
            } else if (dx != 0 || dz != 0) {
                // Vodorovná štola 1×2: preferuj osu s větší zbývající vzdáleností.
                boolean preferX = Math.abs(target.x() - current.x())
                        >= Math.abs(target.z() - current.z());
                int sx = preferX ? dx : 0;
                int sz = preferX ? 0 : dz;
                if (sx == 0 && sz == 0) {
                    sx = dx;
                    sz = dz;
                }
                next = current.offset(sx, 0, sz);
                toBreak.add(next.up());
                toBreak.add(next);
            } else {
                // Zbývá jen stoupnout o 1 (dy > 0 při povoleném +1): vyskočit
                // na sousední blok neumíme naplánovat bez pokládání – konec.
                return steps;
            }
            steps.add(new Step(List.copyOf(toBreak), next));
            current = next;
        }
        return steps; // budget vyčerpán – dovede bota aspoň blíž
    }

    /**
     * Má pozice pevnou podlahu do dané hloubky? Sonda před vstupem na krok
     * výkopu – bez ní si bot prokopne strop velké jeskyně a padá.
     *
     * <p>Tekutina pod nohama se bere jako NEbezpečná (kaverna s jezerem/lávou
     * pod stropem – tam se schodištěm nechodí).</p>
     *
     * @param world    pohled na svět
     * @param feet     pozice chodidel po kroku
     * @param maxDepth kolik bloků pod chodidly ještě prohledat
     * @return {@code true} pokud je do {@code maxDepth} pevná podlaha
     */
    public static boolean hasFloorBelow(WorldView world, BlockPos feet, int maxDepth) {
        BlockPos below = feet.down();
        for (int depth = 1; depth <= maxDepth + 1; depth++) {
            var traits = world.traitsAt(below);
            if (traits.liquid()) {
                return false;
            }
            if (traits.floorHeight() >= 0.99) {
                return depth <= maxDepth;
            }
            below = below.down();
        }
        return false;
    }

    /**
     * Naplánuje schodiště na cílovou hloubku (těžební sestup naslepo).
     *
     * @param feet     aktuální pozice nohou
     * @param targetY  cílová výška nohou
     * @param heading  vodorovný směr sestupu: 0 = +x, 1 = +z, 2 = −x, 3 = −z
     * @param maxSteps strop počtu kroků
     * @return kroky schodiště (končí na targetY nebo po vyčerpání budgetu)
     */
    public static List<Step> staircase(BlockPos feet, int targetY, int heading, int maxSteps) {
        int sx = switch (heading & 3) {
            case 0 -> 1;
            case 2 -> -1;
            default -> 0;
        };
        int sz = switch (heading & 3) {
            case 1 -> 1;
            case 3 -> -1;
            default -> 0;
        };
        List<Step> steps = new ArrayList<>();
        BlockPos current = feet;
        for (int i = 0; i < maxSteps && current.y() > targetY; i++) {
            BlockPos next = current.offset(sx, -1, sz);
            steps.add(new Step(List.of(next.up().up(), next.up(), next), next));
            current = next;
        }
        return steps;
    }
}
