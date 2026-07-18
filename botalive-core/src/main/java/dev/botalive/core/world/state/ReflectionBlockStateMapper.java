package dev.botalive.core.world.state;

import dev.botalive.core.world.BlockTraits;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Optional;

/**
 * Přesné mapování block states sestavené z interních registrů hostitelského
 * Paper serveru.
 *
 * <p><b>Proč reflexe:</b> Bukkit API síťová block-state ID nevystavuje.
 * Hostitelský server ale mluví stejnou verzí protokolu jako MCProtocolLib
 * (obojí je pinované na 26.1), takže jeho globální paleta je identická
 * s paletou cizího serveru. Tabulka se sestaví jednou při startu:
 * {@code net.minecraft.world.level.block.Block#BLOCK_STATE_REGISTRY}
 * (mojmap – Paper od 1.20.5 běží s Mojang mappingy) → pro každé id
 * {@code CraftBlockData.fromData(state)} → Bukkit {@link BlockData}.</p>
 *
 * <p>Výsledkem jsou dvě ploché tabulky (traits + block data) s O(1) čtením
 * bez zámků. Pokud se interní názvy v budoucí verzi serveru změní, tovární
 * metoda vrátí {@link Optional#empty()} a použije se
 * {@link FallbackBlockStateMapper} (degradace se zalogováním).</p>
 */
public final class ReflectionBlockStateMapper implements BlockStateMapper {

    private static final Logger LOG = LoggerFactory.getLogger(ReflectionBlockStateMapper.class);

    private final BlockTraits[] traits;
    private final BlockData[] blockData;
    private final Material[] materials;

    private ReflectionBlockStateMapper(BlockTraits[] traits, BlockData[] blockData,
                                       Material[] materials) {
        this.traits = traits;
        this.blockData = blockData;
        this.materials = materials;
    }

    /**
     * Pokusí se sestavit přesnou tabulku z interních registrů serveru.
     *
     * @return mapper, nebo prázdno při nekompatibilitě interních API
     */
    public static Optional<BlockStateMapper> tryCreate() {
        try {
            Class<?> blockClass = Class.forName("net.minecraft.world.level.block.Block");
            Field registryField = blockClass.getField("BLOCK_STATE_REGISTRY");
            Object registry = registryField.get(null);

            Method size = registry.getClass().getMethod("size");
            Method byId = registry.getClass().getMethod("byId", int.class);
            size.setAccessible(true);
            byId.setAccessible(true);

            Class<?> craftBlockData = Class.forName("org.bukkit.craftbukkit.block.data.CraftBlockData");
            Class<?> blockStateClass = Class.forName("net.minecraft.world.level.block.state.BlockState");
            Method fromData = craftBlockData.getMethod("fromData", blockStateClass);

            int count = (Integer) size.invoke(registry);
            BlockTraits[] traits = new BlockTraits[count];
            BlockData[] blockData = new BlockData[count];
            Material[] materials = new Material[count];

            for (int id = 0; id < count; id++) {
                Object state = byId.invoke(registry, id);
                if (state == null) {
                    traits[id] = BlockTraits.UNKNOWN;
                    continue;
                }
                BlockData data = (BlockData) fromData.invoke(null, state);
                blockData[id] = data;
                materials[id] = data.getMaterial();
                // Per-state vlastnosti: poloviny desek, otevřenost dveří,
                // waterlogging, přesné kolizní boxy.
                traits[id] = BlockTraits.of(data);
            }
            LOG.info("Block-state tabulka sestavena z registrů serveru ({} stavů)", count);
            return Optional.of(new ReflectionBlockStateMapper(traits, blockData, materials));
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warn("Nepodařilo se sestavit block-state tabulku ({}); klientský world model "
                    + "pojede v degradovaném režimu (vzduch/pevné bloky)", e.toString());
            return Optional.empty();
        }
    }

    @Override
    public BlockTraits traitsOf(int stateId) {
        if (stateId < 0 || stateId >= traits.length) {
            return BlockTraits.UNKNOWN;
        }
        BlockTraits result = traits[stateId];
        return result == null ? BlockTraits.UNKNOWN : result;
    }

    @Override
    public Material materialOf(int stateId) {
        return stateId < 0 || stateId >= materials.length ? null : materials[stateId];
    }

    @Override
    public BlockData blockDataOf(int stateId) {
        return stateId < 0 || stateId >= blockData.length ? null : blockData[stateId];
    }

    @Override
    public int stateCount() {
        return traits.length;
    }
}
