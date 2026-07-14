package dev.botalive.core.container;

import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Testy sledování otevřených oken kontejnerů.
 */
class ContainerTrackerTest {

    @Test
    void otevreniANacteniObsahu() {
        ContainerTracker tracker = new ContainerTracker();
        tracker.onOpenScreen(3, ContainerType.GENERIC_9X3);

        ContainerView view = tracker.open();
        assertEquals(3, view.containerId());
        assertFalse(view.contentLoaded());

        ItemStack[] items = new ItemStack[63]; // 27 truhla + 36 hráč
        items[0] = new ItemStack(42, 5);
        tracker.onSetContent(3, 7, items);

        assertTrue(view.contentLoaded());
        assertEquals(7, view.stateId());
        assertEquals(27, view.containerSlots());
        assertEquals(63, view.totalSlots());
        assertEquals(42, view.slot(0).getId());
        assertNull(view.slot(1));
    }

    @Test
    void setSlotAktualizujeStateId() {
        ContainerTracker tracker = new ContainerTracker();
        tracker.onOpenScreen(2, ContainerType.FURNACE);
        tracker.onSetContent(2, 1, new ItemStack[39]);
        long revision = tracker.open().revision();

        tracker.onSetSlot(2, 9, 2, new ItemStack(10, 3));
        assertEquals(9, tracker.open().stateId());
        assertEquals(3, tracker.open().slot(2).getAmount());
        assertTrue(tracker.open().revision() > revision);
    }

    @Test
    void okno0DrziJenStateId() {
        ContainerTracker tracker = new ContainerTracker();
        tracker.onSetContent(0, 12, new ItemStack[46]);
        assertEquals(12, tracker.inventoryStateId());
        assertNull(tracker.open());

        tracker.onSetSlot(0, 13, 5, null);
        assertEquals(13, tracker.inventoryStateId());
    }

    @Test
    void cizeOknoSeIgnoruje() {
        ContainerTracker tracker = new ContainerTracker();
        tracker.onOpenScreen(4, ContainerType.GENERIC_9X1);
        tracker.onSetContent(9, 5, new ItemStack[45]); // jiné id
        assertFalse(tracker.open().contentLoaded());
    }

    @Test
    void vlastnostiATrade() {
        ContainerTracker tracker = new ContainerTracker();
        tracker.onOpenScreen(5, ContainerType.ENCHANTMENT);
        assertEquals(-1, tracker.open().property(0));
        tracker.onSetData(5, 0, 8);
        assertEquals(8, tracker.open().property(0));

        tracker.onClosed();
        assertNull(tracker.open());
    }

    @Test
    void noveOknoNahrazujeStare() {
        ContainerTracker tracker = new ContainerTracker();
        tracker.onOpenScreen(1, ContainerType.GENERIC_9X3);
        tracker.onOpenScreen(2, ContainerType.CRAFTING);
        assertEquals(2, tracker.open().containerId());
        assertEquals(ContainerType.CRAFTING, tracker.open().type());
    }
}
