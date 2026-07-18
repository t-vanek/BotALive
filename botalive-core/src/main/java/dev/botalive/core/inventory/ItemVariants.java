package dev.botalive.core.inventory;

import dev.botalive.core.bot.ServerSideView;
import org.bukkit.Material;

/**
 * Varianty itemů, jejichž identita se pozná až z metadat – lektvary (typ
 * lektvaru), obalené šípy a enchantované knihy. {@link ServerSideView.Snapshot}
 * nese jen {@link Material}; varianta je normalizovaný řetězec ve slotové mapě
 * {@code itemVariants} (server režim ji plní z ItemMeta na vlákně entity).
 *
 * <p>Formát variant: lektvary/šípy = klíč typu lektvaru malými písmeny
 * ({@code fire_resistance}, {@code strong_healing}, {@code long_swiftness}…),
 * enchantované knihy = {@code klíč_enchantu:úroveň} ({@code sharpness:4}).
 * Prefixy {@code long_}/{@code strong_} rozlišují délku/sílu – porovnání přes
 * {@link #effectIs(String, String)} je ignoruje.</p>
 */
public final class ItemVariants {

    /** Odolnost ohni – klíčový lektvar pro Nether (barter s pigliny). */
    public static final String FIRE_RESISTANCE = "fire_resistance";

    /** Okamžité léčení. */
    public static final String HEALING = "healing";

    /** Regenerace. */
    public static final String REGENERATION = "regeneration";

    private ItemVariants() {
    }

    /**
     * @param material materiál
     * @return {@code true} pokud jde o pitelný lektvar (ne splash/lingering)
     */
    public static boolean isDrinkablePotion(Material material) {
        return material == Material.POTION;
    }

    /**
     * Odpovídá varianta danému efektu? Prefixy long_/strong_ (delší/silnější
     * podoba téhož lektvaru) se ignorují.
     *
     * @param variant varianta ze snapshotu (může být {@code null})
     * @param effect  hledaný efekt (např. {@link #FIRE_RESISTANCE})
     * @return {@code true} při shodě
     */
    public static boolean effectIs(String variant, String effect) {
        if (variant == null) {
            return false;
        }
        return variant.equals(effect)
                || variant.equals("long_" + effect)
                || variant.equals("strong_" + effect);
    }

    /**
     * Najde slot (0–35, Bukkit číslování) s pitelným lektvarem daného efektu.
     *
     * @param snapshot snapshot inventáře
     * @param effect   hledaný efekt
     * @return slot 0–8 (hotbar) / 9–35 (hlavní inventář), nebo -1
     */
    public static int findPotionSlot(ServerSideView.Snapshot snapshot, String effect) {
        if (snapshot == null || snapshot.itemVariants() == null) {
            return -1;
        }
        Material[] hotbar = snapshot.hotbar();
        for (int i = 0; i < hotbar.length; i++) {
            if (isDrinkablePotion(hotbar[i])
                    && effectIs(snapshot.itemVariants().get(i), effect)) {
                return i;
            }
        }
        Material[] main = snapshot.mainInventory();
        for (int i = 0; i < main.length; i++) {
            if (isDrinkablePotion(main[i])
                    && effectIs(snapshot.itemVariants().get(9 + i), effect)) {
                return 9 + i;
            }
        }
        return -1;
    }

    /**
     * @param snapshot snapshot inventáře
     * @param effect   hledaný efekt
     * @return {@code true} pokud bot nese pitelný lektvar daného efektu
     */
    public static boolean hasPotion(ServerSideView.Snapshot snapshot, String effect) {
        return findPotionSlot(snapshot, effect) >= 0;
    }
}
