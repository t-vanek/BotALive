package dev.botalive.core.world.state;

import org.bukkit.block.data.BlockData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.List;

/**
 * Přesné kolizní boxy block states z interních registrů hostitelského Paper
 * serveru.
 *
 * <p><b>Proč reflexe:</b> Bukkit API kolizní tvary bloků nevystavuje (bez
 * umístěného bloku ve světě). Interně je má každý {@code BlockState}:
 * {@code CraftBlockData#getState()} →
 * {@code BlockStateBase#getCollisionShape(BlockGetter, BlockPos)} →
 * {@code VoxelShape#toAabbs()}. Tvar je pro daný state konstantní, takže se
 * výsledek cachuje výš ({@code BlockTraits} per state).</p>
 *
 * <p>Když se interní API nenajde (změna názvů v budoucí verzi), vrací se
 * {@code null} a volající použije heuristické tvary z Bukkit block dat –
 * degradace se jednou zaloguje.</p>
 */
public final class CollisionShapes {

    private static final Logger LOG = LoggerFactory.getLogger(CollisionShapes.class);

    /** Strop výšky boxu – ploty/zídky mají 1.5, víc nedává pro pohyb smysl. */
    private static final double MAX_BOX_HEIGHT = 1.5;

    private static final Handles HANDLES = Handles.tryCreate();

    private record Handles(Method getState, Method getCollisionShape, Object emptyGetter,
                           Object zeroPos, Method toAabbs,
                           Field minX, Field minY, Field minZ,
                           Field maxX, Field maxY, Field maxZ) {

        static Handles tryCreate() {
            try {
                Class<?> craftBlockData = Class.forName("org.bukkit.craftbukkit.block.data.CraftBlockData");
                Method getState = craftBlockData.getMethod("getState");

                Class<?> stateBase = Class.forName(
                        "net.minecraft.world.level.block.state.BlockBehaviour$BlockStateBase");
                Class<?> blockGetter = Class.forName("net.minecraft.world.level.BlockGetter");
                Class<?> nmsBlockPos = Class.forName("net.minecraft.core.BlockPos");
                Method getCollisionShape = stateBase.getMethod("getCollisionShape", blockGetter, nmsBlockPos);

                Object emptyGetter = Class.forName("net.minecraft.world.level.EmptyBlockGetter")
                        .getField("INSTANCE").get(null);
                Object zeroPos = nmsBlockPos.getField("ZERO").get(null);

                Class<?> voxelShape = Class.forName("net.minecraft.world.phys.shapes.VoxelShape");
                Method toAabbs = voxelShape.getMethod("toAabbs");

                Class<?> aabb = Class.forName("net.minecraft.world.phys.AABB");
                return new Handles(getState, getCollisionShape, emptyGetter, zeroPos, toAabbs,
                        aabb.getField("minX"), aabb.getField("minY"), aabb.getField("minZ"),
                        aabb.getField("maxX"), aabb.getField("maxY"), aabb.getField("maxZ"));
            } catch (ReflectiveOperationException | RuntimeException e) {
                LOG.warn("Kolizní tvary z registrů serveru nedostupné ({}); "
                        + "použijí se heuristické tvary z block dat", e.toString());
                return null;
            }
        }
    }

    private CollisionShapes() {
    }

    /**
     * Kolizní boxy stavu bloku v lokálních souřadnicích buňky.
     *
     * @param data block data (musí pocházet ze serveru – CraftBlockData)
     * @return boxy po šesticích {@code minX,minY,minZ,maxX,maxY,maxZ},
     *         prázdné pole pro bloky bez kolize; {@code null} když interní
     *         API není dostupné nebo čtení selže
     */
    public static double[] boxesOf(BlockData data) {
        if (HANDLES == null) {
            return null;
        }
        try {
            Object state = HANDLES.getState().invoke(data);
            Object shape = HANDLES.getCollisionShape().invoke(state,
                    HANDLES.emptyGetter(), HANDLES.zeroPos());
            List<?> aabbs = (List<?>) HANDLES.toAabbs().invoke(shape);
            if (aabbs.isEmpty()) {
                return dev.botalive.core.world.BlockTraits.NO_BOXES;
            }
            double[] boxes = new double[aabbs.size() * 6];
            int i = 0;
            for (Object box : aabbs) {
                boxes[i++] = HANDLES.minX().getDouble(box);
                boxes[i++] = HANDLES.minY().getDouble(box);
                boxes[i++] = HANDLES.minZ().getDouble(box);
                boxes[i++] = HANDLES.maxX().getDouble(box);
                boxes[i++] = Math.min(HANDLES.maxY().getDouble(box), MAX_BOX_HEIGHT);
                boxes[i++] = HANDLES.maxZ().getDouble(box);
            }
            return boxes;
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null; // ne-Craft implementace (testy) nebo nečekaný tvar
        }
    }
}
