package dev.botalive.core.pathfinding;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.BlockTraits;
import dev.botalive.core.world.WorldView;

/**
 * Levná kontrola, jestli waypoint naplánované cesty stále dává smysl.
 *
 * <p>Svět se pod cestou mění (výbuch, vykopnutý blok, postavená zeď) – bez
 * validace to bot zjistí až fyzickým zaseknutím o 2,5 s později. Validace je
 * záměrně <b>konzervativní</b>: zneplatňuje jen jisté překážky, tedy stavy,
 * které by odmítl i samotný A* ({@code feetHeight}/{@code headClear}) – nová
 * cesta se pak rozbitému místu spolehlivě vyhne a replán se nezacyklí.
 * {@link BlockTraits#UNKNOWN} nechává být: chunk mohl jen vypršet z cache
 * (TTL) a replán storm nad dlouhou cestou by byl horší než dojet podle
 * původního plánu.</p>
 */
public final class PathValidator {

    private static final double EPS = 1.0E-6;

    private PathValidator() {
    }

    /**
     * Je waypoint prokazatelně zablokovaný změnou světa?
     *
     * @param world pohled na svět
     * @param wp    waypoint cesty (pozice nohou)
     * @return {@code true} když waypointem už projít nejde (zazděno, hazard,
     *         stržená podlaha); {@code false} při nejistotě (nenačtený chunk)
     */
    public static boolean blocked(WorldView world, BlockPos wp) {
        BlockTraits feet = world.traitsAt(wp);
        if (feet == BlockTraits.UNKNOWN) {
            return false;
        }
        if (feet.hazard() || feet.web()) {
            return true; // do buňky natekla láva / vyrostla pavučina
        }
        if (feet.liquid() || feet.climbable() || feet.door()) {
            return false; // plavecký/šplhací/dveřní waypoint funguje beze změny
        }
        if (feet.floorHeight() >= 0.99) {
            return true; // buňku někdo zazdil (plný blok, plot)
        }
        BlockTraits head = world.traitsAt(wp.up());
        if (head != BlockTraits.UNKNOWN) {
            if (head.hazard() || head.web()) {
                return true;
            }
            // Kolize začínající pod ~0.8 bloku překáží tělu v každém postoji,
            // který mohl A* naplánovat (horní poklop u stropu nevadí).
            if (!head.door() && head.lowestCollisionStart() < 0.8 - EPS) {
                return true;
            }
        }
        if (feet.floorHeight() <= 0) {
            BlockTraits below = world.traitsAt(wp.down());
            if (below != BlockTraits.UNKNOWN && below.noCollision()
                    && !below.liquid() && !below.climbable()) {
                return true; // podlaha zmizela – krok do prázdna
            }
        }
        return false;
    }
}
