package dev.botalive.core.world.state;

import org.bukkit.Material;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.EnumMap;
import java.util.Map;
import java.util.Optional;

/**
 * Přesné mapování itemů sestavené z interních registrů hostitelského serveru.
 *
 * <p>Stejný přístup jako {@link ReflectionBlockStateMapper}: Bukkit API síťová
 * item ID nevystavuje, ale hostitel mluví stejnou verzí protokolu jako
 * MCProtocolLib (hlídá {@code ViaCompat}), takže jeho item registr je
 * identický s registrem cizího serveru. Tabulka se sestaví jednou při startu:
 * {@code net.minecraft.core.registries.BuiltInRegistries#ITEM} → pro každé id
 * {@code CraftMagicNumbers.getMaterial(Item)} → {@link Material}.</p>
 */
public final class ReflectionItemMapper implements ItemMapper {

    private static final Logger LOG = LoggerFactory.getLogger(ReflectionItemMapper.class);

    private final Material[] byId;
    private final Map<Material, Integer> byMaterial;
    private final String[] potionById;

    private ReflectionItemMapper(Material[] byId, Map<Material, Integer> byMaterial,
                                 String[] potionById) {
        this.byId = byId;
        this.byMaterial = byMaterial;
        this.potionById = potionById;
    }

    /**
     * Pokusí se sestavit tabulku itemů z interních registrů serveru.
     *
     * @return mapper, nebo prázdno při nekompatibilitě interních API
     */
    public static Optional<ItemMapper> tryCreate() {
        try {
            Class<?> registries = Class.forName("net.minecraft.core.registries.BuiltInRegistries");
            Field itemField = registries.getField("ITEM");
            Object registry = itemField.get(null);

            Method size = registry.getClass().getMethod("size");
            Method byIdMethod = registry.getClass().getMethod("byId", int.class);
            size.setAccessible(true);
            byIdMethod.setAccessible(true);

            Class<?> itemClass = Class.forName("net.minecraft.world.item.Item");
            Class<?> magicNumbers = Class.forName("org.bukkit.craftbukkit.util.CraftMagicNumbers");
            Method getMaterial = magicNumbers.getMethod("getMaterial", itemClass);

            int count = (Integer) size.invoke(registry);
            Material[] byId = new Material[count];
            Map<Material, Integer> byMaterial = new EnumMap<>(Material.class);
            for (int id = 0; id < count; id++) {
                Object item = byIdMethod.invoke(registry, id);
                if (item == null) {
                    continue;
                }
                Material material = (Material) getMaterial.invoke(null, item);
                byId[id] = material;
                if (material != null) {
                    byMaterial.putIfAbsent(material, id);
                }
            }
            String[] potions = tryPotionTable(registries);
            LOG.info("Item tabulka sestavena z registrů serveru ({} itemů, {} lektvarů)",
                    count, potions.length);
            return Optional.of(new ReflectionItemMapper(byId, byMaterial, potions));
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warn("Nepodařilo se sestavit item tabulku ({}); paketový survival "
                    + "nebude umět klasifikovat itemy", e.toString());
            return Optional.empty();
        }
    }

    /**
     * Tabulka typů lektvarů (registr je statický jako itemy). Best-effort:
     * selhání nechá itemy fungovat a jen vypne varianty lektvarů.
     */
    private static String[] tryPotionTable(Class<?> registries) {
        try {
            Field potionField = registries.getField("POTION");
            Object registry = potionField.get(null);
            Method size = registry.getClass().getMethod("size");
            Method byIdMethod = registry.getClass().getMethod("byId", int.class);
            Method getKey = registry.getClass().getMethod("getKey", Object.class);
            size.setAccessible(true);
            byIdMethod.setAccessible(true);
            getKey.setAccessible(true);

            int count = (Integer) size.invoke(registry);
            String[] byId = new String[count];
            for (int id = 0; id < count; id++) {
                Object potion = byIdMethod.invoke(registry, id);
                if (potion == null) {
                    continue;
                }
                Object key = getKey.invoke(registry, potion);
                if (key != null) {
                    Object path = key.getClass().getMethod("getPath").invoke(key);
                    byId[id] = String.valueOf(path);
                }
            }
            return byId;
        } catch (ReflectiveOperationException | RuntimeException e) {
            LOG.warn("Tabulka lektvarů se nesestavila ({}); varianty lektvarů "
                    + "v packet režimu nebudou", e.toString());
            return new String[0];
        }
    }

    @Override
    public String potionKeyOf(int potionId) {
        return potionId < 0 || potionId >= potionById.length ? null : potionById[potionId];
    }

    @Override
    public Material materialOf(int itemId) {
        return itemId < 0 || itemId >= byId.length ? null : byId[itemId];
    }

    @Override
    public int idOf(Material material) {
        Integer id = byMaterial.get(material);
        return id == null ? -1 : id;
    }
}
