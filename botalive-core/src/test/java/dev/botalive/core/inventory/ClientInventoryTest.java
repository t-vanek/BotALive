package dev.botalive.core.inventory;

import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Testy mapování slotů klientského inventáře (okno 0 vs. číslování
 * ClientboundSetPlayerInventoryPacket).
 */
class ClientInventoryTest {

    @Test
    void hotbarSeMapujeNaOknove36az44() {
        ClientInventory inventory = new ClientInventory();
        inventory.setPlayerSlot(0, new ItemStack(1, 1));
        inventory.setPlayerSlot(8, new ItemStack(2, 1));
        assertEquals(1, inventory.slot(36).getId());
        assertEquals(2, inventory.slot(44).getId());
        assertEquals(1, inventory.hotbar(0).getId());
    }

    @Test
    void hlavniInventarJeShodny() {
        ClientInventory inventory = new ClientInventory();
        inventory.setPlayerSlot(9, new ItemStack(3, 1));
        inventory.setPlayerSlot(35, new ItemStack(4, 1));
        assertEquals(3, inventory.slot(9).getId());
        assertEquals(4, inventory.slot(35).getId());
    }

    @Test
    void zbrojJeObracene() {
        ClientInventory inventory = new ClientInventory();
        inventory.setPlayerSlot(36, new ItemStack(5, 1)); // boty
        inventory.setPlayerSlot(39, new ItemStack(6, 1)); // helma
        assertEquals(5, inventory.slot(8).getId());
        assertEquals(6, inventory.slot(5).getId());
    }

    @Test
    void druhaRukaAOkraje() {
        ClientInventory inventory = new ClientInventory();
        inventory.setPlayerSlot(40, new ItemStack(7, 1));
        assertEquals(7, inventory.slot(45).getId());
        inventory.setPlayerSlot(41, new ItemStack(8, 1)); // mimo rozsah – ignorováno
        inventory.setPlayerSlot(-1, new ItemStack(9, 1));
        assertNull(inventory.slot(46));
    }
}
