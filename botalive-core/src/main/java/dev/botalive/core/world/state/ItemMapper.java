package dev.botalive.core.world.state;

import org.bukkit.Material;

/**
 * Mapování síťových item ID protokolu na Bukkit {@link Material} a zpět.
 *
 * <p>Protějšek {@link BlockStateMapper} pro itemy: klientský model inventáře
 * ({@code ClientInventory}) drží protokolové {@code ItemStack}y se síťovými
 * ID, ale rozhodovací vrstva AI pracuje s {@link Material}. Platí stejné
 * pravidlo verzí: tabulka z registrů hostitele je správná jen při shodě
 * protokolu hostitele s protokolem botů (Via překládá pakety do formátu
 * klienta).</p>
 */
public interface ItemMapper {

    /**
     * @param itemId síťové id itemu
     * @return materiál, nebo {@code null} pro neznámé id
     */
    Material materialOf(int itemId);

    /**
     * @param material materiál
     * @return síťové id itemu, nebo -1 pokud materiál není item
     */
    int idOf(Material material);

    /**
     * Klíč typu lektvaru pro síťové registry ID ({@code fire_resistance},
     * {@code strong_healing}…). Registr lektvarů je statický (per verze
     * protokolu), tabulka se sestavuje z registrů hostitele stejně jako
     * itemy – bez ní se varianty lektvarů v packet režimu nečtou.
     *
     * @param potionId síťové ID typu lektvaru
     * @return klíč typu, nebo {@code null} bez tabulky/mimo rozsah
     */
    default String potionKeyOf(int potionId) {
        return null;
    }
}
