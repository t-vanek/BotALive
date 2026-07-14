package dev.botalive.core.station;

import dev.botalive.core.container.ContainerView;
import dev.botalive.core.inventory.ClientInventory;
import dev.botalive.core.world.state.ItemMapper;
import org.bukkit.Material;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.HashMap;
import java.util.Map;

/**
 * Pomocníci pro čtení oken v paketových stanicích.
 *
 * <p>Převádí sloty oken (protokolové {@code ItemStack}y se síťovými ID)
 * na {@link Material} přes {@link ItemMapper} a staví mapy pro čisté
 * plánovače ({@code GridPlacer}).</p>
 */
final class Windows {

    /** První slot hráčovy sekce okna 0 (hlavní inventář). */
    static final int INV_MAIN_START = 9;

    /** Poslední slot hráčovy sekce okna 0 (konec hotbaru). */
    static final int INV_PLAYER_END = 44;

    private Windows() {
    }

    /** @return materiál slotu okna, nebo {@code null} */
    static Material materialAt(ContainerView view, ItemMapper mapper, int slot) {
        ItemStack stack = view.slot(slot);
        return stack == null ? null : mapper.materialOf(stack.getId());
    }

    /** @return počet kusů ve slotu okna (0 = prázdný) */
    static int amountAt(ContainerView view, int slot) {
        ItemStack stack = view.slot(slot);
        return stack == null ? 0 : stack.getAmount();
    }

    /** @return index prvního slotu hráčovy sekce okna */
    static int playerStart(ContainerView view) {
        return view.totalSlots() - ContainerView.PLAYER_SECTION;
    }

    /**
     * Mapy (slot → materiál, slot → počet) hráčovy sekce otevřeného okna –
     * vstup pro {@code GridPlacer}.
     */
    static SlotMaps playerSection(ContainerView view, ItemMapper mapper) {
        Map<Integer, Material> materials = new HashMap<>();
        Map<Integer, Integer> counts = new HashMap<>();
        for (int slot = playerStart(view); slot < view.totalSlots(); slot++) {
            ItemStack stack = view.slot(slot);
            Material material = stack == null ? null : mapper.materialOf(stack.getId());
            if (material != null) {
                materials.put(slot, material);
                counts.put(slot, stack.getAmount());
            }
        }
        return new SlotMaps(materials, counts);
    }

    /** Totéž pro okno 0 (vlastní inventář – sloty 9–44). */
    static SlotMaps inventorySection(ClientInventory inventory, ItemMapper mapper) {
        Map<Integer, Material> materials = new HashMap<>();
        Map<Integer, Integer> counts = new HashMap<>();
        for (int slot = INV_MAIN_START; slot <= INV_PLAYER_END; slot++) {
            ItemStack stack = inventory.slot(slot);
            Material material = stack == null ? null : mapper.materialOf(stack.getId());
            if (material != null) {
                materials.put(slot, material);
                counts.put(slot, stack.getAmount());
            }
        }
        return new SlotMaps(materials, counts);
    }

    /**
     * Obsah sekce inventáře jako mapy pro plánovače.
     *
     * @param materials slot okna → materiál
     * @param counts    slot okna → počet kusů
     */
    record SlotMaps(Map<Integer, Material> materials, Map<Integer, Integer> counts) {

        /** @return celkový počet kusů materiálů vyhovujících predikátu */
        int count(java.util.function.Predicate<Material> predicate) {
            int total = 0;
            for (Map.Entry<Integer, Material> entry : materials.entrySet()) {
                if (predicate.test(entry.getValue())) {
                    total += counts.getOrDefault(entry.getKey(), 0);
                }
            }
            return total;
        }

        /** @return první slot s materiálem vyhovujícím predikátu, nebo -1 */
        int findSlot(java.util.function.Predicate<Material> predicate) {
            for (Map.Entry<Integer, Material> entry : materials.entrySet()) {
                if (predicate.test(entry.getValue())) {
                    return entry.getKey();
                }
            }
            return -1;
        }

        /** @return první materiál vyhovující predikátu, nebo {@code null} */
        Material findMaterial(java.util.function.Predicate<Material> predicate) {
            int slot = findSlot(predicate);
            return slot < 0 ? null : materials.get(slot);
        }
    }
}
