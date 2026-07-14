package dev.botalive.core.inventory;

import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.concurrent.atomic.AtomicReferenceArray;

/**
 * Klientský model inventáře bota (kontejner id 0 – inventář hráče).
 *
 * <p>Synchronizuje se výhradně z paketů serveru (SetContent/SetSlot), takže
 * odpovídá tomu, co by viděl skutečný klient. Rozvržení slotů:
 * 0 = výstup craftingu, 1–4 = crafting mřížka, 5–8 = zbroj,
 * 9–35 = hlavní inventář, 36–44 = hotbar, 45 = druhá ruka.</p>
 */
public final class ClientInventory {

    /** Počet slotů inventáře hráče. */
    public static final int SIZE = 46;

    /** Index prvního hotbar slotu. */
    public static final int HOTBAR_START = 36;

    private final AtomicReferenceArray<ItemStack> slots = new AtomicReferenceArray<>(SIZE);

    /**
     * Kompletní obsah (ClientboundContainerSetContentPacket, containerId 0).
     *
     * @param items pole itemů od serveru
     */
    public void setContents(ItemStack[] items) {
        for (int i = 0; i < SIZE; i++) {
            slots.set(i, i < items.length ? items[i] : null);
        }
    }

    /**
     * Jednotlivý slot (ClientboundContainerSetSlotPacket, containerId 0).
     *
     * @param slot index slotu
     * @param item nový obsah (null = prázdný)
     */
    public void setSlot(int slot, ItemStack item) {
        if (slot >= 0 && slot < SIZE) {
            slots.set(slot, item);
        }
    }

    /**
     * @param slot index slotu
     * @return obsah slotu, nebo {@code null}
     */
    public ItemStack slot(int slot) {
        return slot >= 0 && slot < SIZE ? slots.get(slot) : null;
    }

    /**
     * @param hotbarIndex 0–8
     * @return obsah hotbar slotu
     */
    public ItemStack hotbar(int hotbarIndex) {
        return slot(HOTBAR_START + hotbarIndex);
    }

    /** Vyprázdní model (odpojení). */
    public void clear() {
        for (int i = 0; i < SIZE; i++) {
            slots.set(i, null);
        }
    }
}
