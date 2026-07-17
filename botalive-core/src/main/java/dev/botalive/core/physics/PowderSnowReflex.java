package dev.botalive.core.physics;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.WorldView;

/**
 * Reflex úniku z prašanu (powder snow) – poslední slovo nad pohybovým vstupem.
 *
 * <p>Bot zabořený v prašanu začne po ~7 sekundách mrznout (server odečítá
 * životy). Pathfinder do prašanu nevede (je to hazard), ale bot se do něj může
 * dostat pádem, postrčením nebo zásahem sněhové vrstvy do cesty. Reflex drží
 * skok (vanilla únik jump-spamem – v prašanu znamená pomalé stoupání) a brodí
 * se k nejbližšímu bezpečnému bloku v okolí. Zasahuje vždy – žádný cíl nemá
 * v prašanu co pohledávat.</p>
 */
public final class PowderSnowReflex {

    private static final double EPS = 1.0E-4;

    private PowderSnowReflex() {
    }

    /**
     * Aplikuje reflex na pohybový vstup.
     *
     * @param input        vstup od navigace/cíle
     * @param inPowderSnow bot se boří v prašanu (z {@link BotPhysics#inPowderSnow()})
     * @param position     pozice nohou bota
     * @param world        pohled na svět
     * @return únikový vstup, když je bot v prašanu; jinak beze změny
     */
    public static MoveInput apply(MoveInput input, boolean inPowderSnow,
                                  Vec3 position, WorldView world) {
        if (!inPowderSnow || world == null) {
            return input;
        }
        BlockPos feet = position.toBlockPos();
        Vec3 escape = LiquidReflex.towardNearestSafe(world, feet);
        Vec3 direction = escape.horizontalLength() > EPS ? escape : input.direction();
        // Skok = stoupání prašanem; bez sprintu a plížení – jen se prodrat ven.
        return new MoveInput(direction, false, true, false);
    }
}
