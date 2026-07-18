package dev.botalive.core.build;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Cardinal;
import dev.botalive.core.world.WorldView;
import org.bukkit.Material;

import java.util.ArrayList;
import java.util.List;

/**
 * Zvelebení okolí domu ve vesnici – cestička a pochodně podél linie
 * dveře → náves. Čisté plánování nad {@link WorldView}, idempotentní:
 * hotová cestička (už není tráva) ani stojící pochodeň se neplánují znovu,
 * takže tentýž plán slouží prvotní stavbě i pozdější údržbě (relight po
 * creeperovi).
 */
public final class VillageDecor {

    /** Krok zvelebení: {@code path} = udusat cestičku, jinak postavit pochodeň. */
    public record Step(boolean path, BlockPos target) {
    }

    private VillageDecor() {
    }

    /**
     * Naplánuje kroky zvelebení.
     *
     * @param world   pohled na svět
     * @param origin  origin půdorysu domu
     * @param facing  orientace dveří
     * @param center  náves ({@code null} = zakladatel bez známého středu,
     *                použije se krátká linie od dveří)
     * @param spacing rozestup parcel (limituje délku linie)
     * @param torches plánovat pochodně
     * @param paths   plánovat cestičku
     * @return kroky v pořadí od dveří (může být prázdné)
     */
    public static List<Step> plan(WorldView world, BlockPos origin, Cardinal facing,
                                  BlockPos center, int spacing,
                                  boolean torches, boolean paths) {
        List<Step> steps = new ArrayList<>();
        if (!torches && !paths) {
            return steps;
        }
        BlockPos door = HouseBlueprint.doorBottom(origin, facing);
        int length = 6;
        if (center != null) {
            int distance = (int) Math.round(Math.hypot(
                    center.x() - door.x(), center.z() - door.z()));
            length = Math.max(3, Math.min(distance - 2, spacing + 4));
        }
        // Kolmý směr pro pochodně vedle cestičky (ne v chůzi).
        int perpX = facing.dz();
        int perpZ = -facing.dx();
        for (int d = 1; d <= length; d++) {
            int x = door.x() + facing.dx() * d;
            int z = door.z() + facing.dz() * d;
            BlockPos ground = groundAt(world, x, origin.y(), z);
            if (ground == null) {
                continue;
            }
            if (paths && world.materialAt(ground) == Material.GRASS_BLOCK) {
                steps.add(new Step(true, ground));
            }
            if (torches && d % 4 == 2) {
                BlockPos torchGround = groundAt(world, x + perpX, origin.y(), z + perpZ);
                if (torchGround != null
                        && world.materialAt(torchGround.up()) != Material.TORCH) {
                    steps.add(new Step(false, torchGround.up()));
                }
            }
        }
        return steps;
    }

    /**
     * První pevný blok s volnem nad sebou v okolí výšky staveniště.
     *
     * @param world pohled na svět
     * @param x     blok x
     * @param yHint výška staveniště
     * @param z     blok z
     * @return pevná zem, nebo {@code null}
     */
    public static BlockPos groundAt(WorldView world, int x, int yHint, int z) {
        for (int dy = 2; dy >= -3; dy--) {
            BlockPos pos = new BlockPos(x, yHint + dy, z);
            if (world.traitsAt(pos).solid() && !world.traitsAt(pos.up()).solid()) {
                return pos;
            }
        }
        return null;
    }
}
