package dev.botalive.core.container;

import dev.botalive.core.network.BotConnection;
import it.unimi.dsi.fastutil.ints.Int2ObjectMaps;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.MoveToHotbarAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerButtonClickPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;

/**
 * Container kliky – „ruce" paketového survivalu.
 *
 * <p><b>Klíčové rozhodnutí – prázdná predikce:</b> protokol 26.1 posílá
 * v container klicích „hashed item stacky" – predikci klienta, jak budou
 * sloty po kliku vypadat. Server klik <b>vždy provede</b> a predikci používá
 * jen k rozhodnutí, které sloty musí klientovi doposlat. Posíláme proto
 * záměrně prázdnou predikci ({@code changedSlots} prázdné, {@code carriedItem}
 * {@code null}): server nám po každém kliku pošle korekce všeho, co se
 * změnilo, a náš model ({@link ContainerView}) zůstává autoritativně
 * synchronizovaný – bez počítání jediného hashe
 * a bez křehkosti vůči změnám hash algoritmu. Trade-off: pár SetSlot paketů
 * navíc po kliku, což je pro boty zanedbatelné.</p>
 */
public final class ContainerClicker {

    private final BotConnection connection;
    private final ContainerTracker tracker;

    /**
     * @param connection spojení bota
     * @param tracker    sledování otevřených oken (zdroj stateId)
     */
    public ContainerClicker(BotConnection connection, ContainerTracker tracker) {
        this.connection = connection;
        this.tracker = tracker;
    }

    /**
     * Levý klik na slot (zvednutí/položení celého stacku).
     *
     * @param containerId id okna (0 = inventář hráče)
     * @param slot        slot v souřadnicích okna
     */
    public void leftClick(int containerId, int slot) {
        click(containerId, slot, ContainerActionType.CLICK_ITEM, ClickItemAction.LEFT_CLICK);
    }

    /**
     * Pravý klik na slot (položení jednoho kusu / zvednutí půlky).
     *
     * @param containerId id okna
     * @param slot        slot v souřadnicích okna
     */
    public void rightClick(int containerId, int slot) {
        click(containerId, slot, ContainerActionType.CLICK_ITEM, ClickItemAction.RIGHT_CLICK);
    }

    /**
     * Shift-klik (rychlý přesun mezi kontejnerem a inventářem; vanilla
     * směruje podle typu okna – tavitelné do pece, lapis do enchant slotu…).
     *
     * @param containerId id okna
     * @param slot        slot v souřadnicích okna
     */
    public void shiftClick(int containerId, int slot) {
        click(containerId, slot, ContainerActionType.SHIFT_CLICK_ITEM, ShiftClickItemAction.LEFT_CLICK);
    }

    /**
     * Přesun slotu na hotbar pozici (klávesy 1–9 nad otevřeným oknem).
     *
     * @param containerId id okna
     * @param slot        zdrojový slot v souřadnicích okna
     * @param hotbarIndex cílový hotbar slot 0–8
     */
    public void moveToHotbar(int containerId, int slot, int hotbarIndex) {
        click(containerId, slot, ContainerActionType.MOVE_TO_HOTBAR_SLOT,
                MoveToHotbarAction.from(hotbarIndex));
    }

    /**
     * Klik na tlačítko okna (výběr enchantu, kamenořez…).
     *
     * @param containerId id okna
     * @param buttonId    index tlačítka
     */
    public void clickButton(int containerId, int buttonId) {
        connection.send(new ServerboundContainerButtonClickPacket(containerId, buttonId));
    }

    /**
     * Výběr obchodu v okně vesničana – vanilla server po něm sám přesune
     * suroviny hráče do obchodních slotů.
     *
     * @param tradeIndex index nabídky
     */
    public void selectTrade(int tradeIndex) {
        connection.send(new org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound
                .inventory.ServerboundSelectTradePacket(tradeIndex));
    }

    private void click(int containerId, int slot, ContainerActionType type,
                       org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerAction action) {
        int stateId = stateIdOf(containerId);
        connection.send(new ServerboundContainerClickPacket(containerId, stateId, slot, type,
                action, null, Int2ObjectMaps.emptyMap()));
    }

    private int stateIdOf(int containerId) {
        if (containerId == 0) {
            return tracker.inventoryStateId();
        }
        ContainerView view = tracker.open();
        return view != null && view.containerId() == containerId ? view.stateId() : 0;
    }
}
