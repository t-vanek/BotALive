package dev.botalive.core.nether;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.WorldView;
import org.bukkit.Material;

import java.util.Optional;

/**
 * Hledání portálů v okolí – aktivních (portálové bloky) i nezapálených
 * obsidiánových rámů. Čte jen {@link WorldView} (chunk cache), takže je
 * bezpečný z tick vlákna; sken je O(r²·výška) a volá se zřídka
 * (jednou za pokus, ne každý tick).
 */
public final class PortalScanner {

    /** Nalezený rám: vnitřní spodní roh + orientace. */
    public record Frame(BlockPos base, boolean axisX, boolean lit) {

        /** @return buňka, do které se vstupuje (nohy). */
        public BlockPos entry() {
            return PortalBlueprint.entryCell(base);
        }
    }

    private PortalScanner() {
    }

    /**
     * Najde nejbližší aktivní portál <b>daného druhu</b>. Filtr materiálem je
     * záměrný: generický trait {@code portal} pokrývá nether portál, End
     * portál i gateway – sken bez filtru uměl poslat nether výpravu do Endu
     * (bez luku, bloků na mosty a s drakem za dveřmi).
     *
     * @param world  pohled na svět
     * @param around střed hledání
     * @param radius vodorovný poloměr (bloky)
     * @param depth  svislý rozsah ±(bloky)
     * @param portal materiál portálového bloku (např. {@code NETHER_PORTAL})
     * @return vstupní buňka nejbližšího portálu daného druhu
     */
    public static Optional<BlockPos> findActivePortal(WorldView world, BlockPos around,
                                                      int radius, int depth, Material portal) {
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -depth; dy <= depth; dy++) {
                    BlockPos pos = around.offset(dx, dy, dz);
                    if (world.materialAt(pos) != portal) {
                        continue;
                    }
                    // Vstupní buňka: portálový blok s oporou pod nohama
                    // (spodní patro vnitřku).
                    if (world.materialAt(pos.down()) == portal) {
                        continue;
                    }
                    double dist = around.distanceSquared(pos);
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = pos;
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Najde nejbližší kompletní obsidiánový rám (zapálený i nezapálený).
     * Prochází obsidiánové bloky a zkouší je interpretovat jako spodní řadu
     * rámu (obě orientace, oba posuny podél osy).
     *
     * @param world  pohled na svět
     * @param around střed hledání
     * @param radius vodorovný poloměr (bloky)
     * @param depth  svislý rozsah ±(bloky)
     * @return nejbližší nalezený rám
     */
    public static Optional<Frame> findFrame(WorldView world, BlockPos around,
                                            int radius, int depth) {
        Frame best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dy = -depth; dy <= depth; dy++) {
                    BlockPos pos = around.offset(dx, dy, dz);
                    if (world.materialAt(pos) != Material.OBSIDIAN) {
                        continue;
                    }
                    Frame frame = frameAt(world, pos);
                    if (frame == null) {
                        continue;
                    }
                    double dist = around.distanceSquared(frame.base());
                    if (dist < bestDist) {
                        bestDist = dist;
                        best = frame;
                    }
                }
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Zkusí obsidiánový blok interpretovat jako blok spodní řady rámu.
     *
     * @param world    pohled na svět
     * @param obsidian pozice obsidiánu
     * @return rám, jehož je blok součástí, nebo {@code null}
     */
    static Frame frameAt(WorldView world, BlockPos obsidian) {
        // Kandidát na base: buňka nad obsidiánem, posunutá o 0/-1 podél osy
        // (obsidián může být pod kteroukoli ze dvou vnitřních buněk).
        for (boolean axisX : new boolean[]{true, false}) {
            for (int shift = 0; shift >= -1; shift--) {
                BlockPos base = PortalBlueprint.along(obsidian.up(), axisX, shift, 0);
                if (PortalBlueprint.isFrame(world, base, axisX)
                        && PortalBlueprint.interiorClear(world, base, axisX)) {
                    return new Frame(base, axisX, PortalBlueprint.isLit(world, base, axisX));
                }
            }
        }
        return null;
    }
}
