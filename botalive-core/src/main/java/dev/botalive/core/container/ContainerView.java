package dev.botalive.core.container;

import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.VillagerTrade;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Klientský model jednoho otevřeného okna kontejneru (truhla, ponk, pec…).
 *
 * <p>Plní se výhradně z paketů serveru (OpenScreen → SetContent → SetSlot /
 * SetData / MerchantOffers), takže vždy odpovídá tomu, co by viděl skutečný
 * klient. Rozvržení: sloty kontejneru od 0, za nimi 27 slotů hlavního
 * inventáře hráče a 9 slotů hotbaru. Zápis ze síťového vlákna, čtení
 * z vláken stanic – všechny přístupy jsou synchronizované a čtení vrací
 * kopie.</p>
 */
public final class ContainerView {

    /** Počet slotů hráčovy sekce připojené za slot(y) kontejneru. */
    public static final int PLAYER_SECTION = 36;

    private final int containerId;
    private final ContainerType type;

    private ItemStack[] slots = new ItemStack[0];
    private int stateId;
    private boolean contentLoaded;
    private long revision;
    private final Map<Integer, Integer> properties = new HashMap<>();
    private List<VillagerTrade> trades = List.of();

    /**
     * @param containerId id okna přidělené serverem
     * @param type        typ okna z OpenScreen paketu
     */
    ContainerView(int containerId, ContainerType type) {
        this.containerId = containerId;
        this.type = type;
    }

    /** @return id okna */
    public int containerId() {
        return containerId;
    }

    /** @return typ okna */
    public ContainerType type() {
        return type;
    }

    /** @return {@code true} jakmile dorazil kompletní obsah (SetContent) */
    public synchronized boolean contentLoaded() {
        return contentLoaded;
    }

    /** @return poslední stateId od serveru (echo v container klicích) */
    public synchronized int stateId() {
        return stateId;
    }

    /** @return počítadlo změn – roste s každou aktualizací od serveru */
    public synchronized long revision() {
        return revision;
    }

    /** @return počet slotů samotného kontejneru (bez sekce hráče) */
    public synchronized int containerSlots() {
        return Math.max(0, slots.length - PLAYER_SECTION);
    }

    /** @return celkový počet slotů okna */
    public synchronized int totalSlots() {
        return slots.length;
    }

    /**
     * @param slot index slotu v souřadnicích okna
     * @return obsah slotu, nebo {@code null}
     */
    public synchronized ItemStack slot(int slot) {
        return slot >= 0 && slot < slots.length ? slots[slot] : null;
    }

    /**
     * @param property index vlastnosti okna (SetData – např. průběh tavení,
     *                 ceny enchantů)
     * @return hodnota, nebo -1 pokud ještě nedorazila
     */
    public synchronized int property(int property) {
        return properties.getOrDefault(property, -1);
    }

    /** @return nabídky obchodů (jen okno MERCHANT; jinak prázdné) */
    public synchronized List<VillagerTrade> trades() {
        return trades;
    }

    /** Kompletní obsah okna (SetContent). */
    synchronized void content(int stateId, ItemStack[] items) {
        this.slots = items.clone();
        this.stateId = stateId;
        this.contentLoaded = true;
        this.revision++;
    }

    /** Jednotlivý slot (SetSlot). */
    synchronized void slot(int stateId, int slot, ItemStack item) {
        if (slot >= 0 && slot < slots.length) {
            slots[slot] = item;
        }
        this.stateId = stateId;
        this.revision++;
    }

    /** Vlastnost okna (SetData). */
    synchronized void property(int property, int value) {
        properties.put(property, value);
        revision++;
    }

    /** Nabídky obchodů (MerchantOffers). */
    synchronized void trades(List<VillagerTrade> offers) {
        this.trades = List.copyOf(offers);
        revision++;
    }
}
