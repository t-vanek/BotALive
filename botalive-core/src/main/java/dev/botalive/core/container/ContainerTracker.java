package dev.botalive.core.container;

import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.VillagerTrade;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

import java.util.List;

/**
 * Per-bot sledování otevřených oken kontejnerů.
 *
 * <p>Síťové vlákno sem směruje container pakety ({@code BotSessionListener});
 * stanice paketového survivalu ({@code core/station}) z něj čtou stav
 * a stateId pro kliky. Okno 0 (vlastní inventář hráče) tu má jen stateId –
 * jeho obsah drží {@code ClientInventory}.</p>
 */
public final class ContainerTracker {

    private volatile ContainerView open;

    /** Poslední stateId okna 0 (inventář hráče) – echo v container klicích. */
    private volatile int inventoryStateId;

    /** @return právě otevřené okno (≠ 0), nebo {@code null} */
    public ContainerView open() {
        return open;
    }

    /** @return poslední stateId okna 0 */
    public int inventoryStateId() {
        return inventoryStateId;
    }

    /** OpenScreen – server otevřel nové okno (staré tím zaniká). */
    public void onOpenScreen(int containerId, ContainerType type) {
        open = new ContainerView(containerId, type);
    }

    /** SetContent – kompletní obsah okna. */
    public void onSetContent(int containerId, int stateId, ItemStack[] items) {
        if (containerId == 0) {
            inventoryStateId = stateId;
            return;
        }
        ContainerView view = open;
        if (view != null && view.containerId() == containerId) {
            view.content(stateId, items);
        }
    }

    /** SetSlot – jeden slot okna. */
    public void onSetSlot(int containerId, int stateId, int slot, ItemStack item) {
        if (containerId == 0) {
            inventoryStateId = stateId;
            return;
        }
        ContainerView view = open;
        if (view != null && view.containerId() == containerId) {
            view.slot(stateId, slot, item);
        }
    }

    /** SetData – vlastnost okna (průběh tavení, ceny enchantů…). */
    public void onSetData(int containerId, int property, int value) {
        ContainerView view = open;
        if (view != null && view.containerId() == containerId) {
            view.property(property, value);
        }
    }

    /** MerchantOffers – nabídky obchodů vesničana. */
    public void onTrades(int containerId, List<VillagerTrade> offers) {
        ContainerView view = open;
        if (view != null && view.containerId() == containerId) {
            view.trades(offers);
        }
    }

    /** ContainerClose (clientbound) / odpojení – okno zaniká. */
    public void onClosed() {
        open = null;
    }
}
