package dev.botalive.core.physics;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.BlockTraits;
import dev.botalive.core.world.WorldView;

/**
 * Detekce zazdění – bot je (podle world view) uvnitř pevného bloku a dusí se.
 *
 * <p>Jak k tomu dojde: nepovedený výkop (server odmítl FINISH_DIGGING, bot
 * vkročil), zásyp padajícím blokem, korekce pozice od serveru do rohu bloku.
 * Chunk cache dožene skutečnost během pár sekund, takže bot dokáže vlastní
 * zazdění poznat – a má ~10 s (20 HP à 1 dmg/0,5 s), aby se vyprostil.</p>
 */
public final class Suffocation {

    private static final double EYE_HEIGHT = 1.62;

    private Suffocation() {
    }

    /**
     * Najde blok, ve kterém je bot uvězněný (priorita: hlava, pak nohy).
     *
     * @param world    pohled na svět
     * @param position pozice nohou bota
     * @return blok k okamžitému vykopání, nebo {@code null} když bot uvězněný není
     */
    public static BlockPos trappedIn(WorldView world, Vec3 position) {
        BlockPos eye = new Vec3(position.x(), position.y() + EYE_HEIGHT, position.z()).toBlockPos();
        if (covers(world.traitsAt(eye), position, position.y() + EYE_HEIGHT)) {
            return eye;
        }
        BlockPos feet = position.toBlockPos();
        if (covers(world.traitsAt(feet), position, position.y() + 0.1)) {
            return feet;
        }
        return null;
    }

    /** Pokrývá některý kolizní box bloku daný bod (střed hitboxu v dané výšce)? */
    private static boolean covers(BlockTraits traits, Vec3 position, double absoluteY) {
        if (traits == BlockTraits.UNKNOWN) {
            return false; // nenačtený chunk není zazdění
        }
        double[] boxes = traits.boxes();
        double lx = position.x() - Math.floor(position.x());
        double ly = absoluteY - Math.floor(absoluteY);
        double lz = position.z() - Math.floor(position.z());
        for (int i = 0; i < boxes.length; i += 6) {
            if (lx >= boxes[i] && lx <= boxes[i + 3]
                    && ly >= boxes[i + 1] && ly <= boxes[i + 4]
                    && lz >= boxes[i + 2] && lz <= boxes[i + 5]) {
                return true;
            }
        }
        return false;
    }
}
