package dev.botalive.core.physics;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.WorldView;

/**
 * Hledání útočiště pro nouzový přesun zaseknutého bota.
 *
 * <p>Watchdog {@code BotImpl} sahá po teleportu až jako po poslední instanci –
 * potřebuje k tomu buňku, kde bot opravdu ustojí (pevná podlaha, volno v úrovni
 * nohou i hlavy). Když žádná není, MUSÍ to říct: dřívější verze vracela
 * výchozí pozici a volající se pak teleportoval přesně tam, kde bot už stál.
 * Takový „přesun" je zaručený no-op, který navíc shodí eskalaci watchdogu na
 * nulu – naměřeno živě jako {@code nouzový přesun na BlockPos[x=57, y=63,
 * z=-129]} z téže pozice.</p>
 */
public final class Refuge {

    /** Nejdál od bota, kam se útočiště hledá (bloky). */
    private static final int MAX_RING = 2;

    /** Patra prohledávaná v každém prstenci: vlastní, o blok níž, o blok výš. */
    private static final int[] LEVELS = {0, -1, 1};

    private Refuge() {
    }

    /**
     * Najde poblíž buňku, kde bot ustojí.
     *
     * <p>Prochází prstence {@code r=1..2} a v každém i patro nad/pod: zaseknutí
     * bývá na hraně schodu nebo v jednoblokové díře, kde ve vlastní rovině
     * volná buňka není, ale o blok vedle a výš/níž ano.</p>
     *
     * @param world  pohled na svět (může být {@code null} – bot ještě nemá data)
     * @param around pozice, ze které se bot vyprošťuje
     * @return útočiště, nebo {@code null}, není-li kam uhnout
     */
    public static BlockPos findNear(WorldView world, BlockPos around) {
        if (world == null) {
            return null;
        }
        for (int r = 1; r <= MAX_RING; r++) {
            for (int dy : LEVELS) {
                for (int dx = -r; dx <= r; dx++) {
                    for (int dz = -r; dz <= r; dz++) {
                        if (Math.max(Math.abs(dx), Math.abs(dz)) != r) {
                            continue; // jen obvod prstence
                        }
                        BlockPos cell = new BlockPos(around.x() + dx, around.y() + dy,
                                around.z() + dz);
                        if (standable(world, cell)) {
                            return cell;
                        }
                    }
                }
            }
        }
        return null;
    }

    /**
     * Ustojí bot (vysoký 2 bloky) v této buňce?
     *
     * @param world pohled na svět
     * @param cell  zkoumaná buňka (úroveň nohou)
     * @return true, když je pod ní pevná podlaha a nohy i hlava mají volno
     */
    public static boolean standable(WorldView world, BlockPos cell) {
        return world.traitsAt(cell).lowProfile()
                && world.traitsAt(cell.up()).lowProfile()
                && world.traitsAt(cell.down()).solid();
    }
}
