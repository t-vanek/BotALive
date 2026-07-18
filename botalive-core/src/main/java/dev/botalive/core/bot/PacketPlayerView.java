package dev.botalive.core.bot;

import dev.botalive.core.inventory.ClientInventory;
import dev.botalive.core.network.BotClientState;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.BlockTraits;
import dev.botalive.core.world.WorldView;
import dev.botalive.core.world.state.ItemMapper;
import org.bukkit.Location;
import org.bukkit.Material;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;

/**
 * Klientská náhrada server-side snapshotu pro paketový režim.
 *
 * <p>Na cizím serveru bot nemá Bukkit {@code Player}a – ale skoro všechna
 * data snapshotu máme z paketů: inventář ({@link ClientInventory} + překlad
 * item ID přes {@link ItemMapper}), vitály a čas světa
 * ({@link BotClientState}), lávu z world modelu. Sestavením
 * {@link ServerSideView.Snapshot} ze stejných polí funguje celá rozhodovací
 * vrstva (cíle, výběr nástrojů, jídlo) beze změny i na cizím serveru.</p>
 *
 * <p>Chybějící pole: {@code onFire}/{@code sleeping} (entity metadata
 * neparsujeme) zůstávají {@code false} a {@code Location} nemá Bukkit svět –
 * cíle polohu čtou z {@code ctx.position()}, snapshot ji nikde nepoužívá.</p>
 */
final class PacketPlayerView {

    private PacketPlayerView() {
    }

    /**
     * Sestaví snapshot z klientských dat.
     *
     * @param inventory klientský model inventáře
     * @param mapper    překlad item ID → materiál ({@code null} = bez itemů)
     * @param state     protokolový stav bota
     * @param position  aktuální pozice bota (nohy)
     * @param world     pohled na svět ({@code null} před spawnem)
     * @return snapshot kompatibilní se server-side pohledem
     */
    static ServerSideView.Snapshot capture(ClientInventory inventory, ItemMapper mapper,
                                           BotClientState state, Vec3 position,
                                           WorldView world) {
        Material[] hotbar = new Material[9];
        int[] hotbarCounts = new int[9];
        for (int i = 0; i < 9; i++) {
            ItemStack item = inventory.hotbar(i);
            if (item != null && mapper != null) {
                hotbar[i] = mapper.materialOf(item.getId());
                hotbarCounts[i] = item.getAmount();
            }
        }
        Material[] main = new Material[27];
        int[] mainCounts = new int[27];
        for (int i = 0; i < 27; i++) {
            ItemStack item = inventory.slot(9 + i);
            if (item != null && mapper != null) {
                main[i] = mapper.materialOf(item.getId());
                mainCounts[i] = item.getAmount();
            }
        }
        ItemStack offhandItem = inventory.slot(45);
        Material offhand = offhandItem != null && mapper != null
                ? mapper.materialOf(offhandItem.getId()) : null;

        // Sloty brnění okna inventáře: 5 helma, 6 prsník, 7 kalhoty, 8 boty
        // → Bukkit pořadí [boty, kalhoty, prsník, helma].
        Material[] armor = new Material[4];
        for (int i = 0; i < 4; i++) {
            ItemStack piece = inventory.slot(5 + i);
            if (piece != null && mapper != null) {
                armor[3 - i] = mapper.materialOf(piece.getId());
            }
        }

        boolean inLava = false;
        if (world != null) {
            BlockTraits feet = world.traitsAt(position.toBlockPos());
            inLava = feet.liquid() && feet.hazard();
        }
        return new ServerSideView.Snapshot(
                new Location(null, position.x(), position.y(), position.z()),
                hotbar, hotbarCounts, main, mainCounts, offhand, armor,
                null, 0, // opotřebení jen v server režimu (klientský model ho nečte)
                state.health(),
                state.food(),
                state.expLevel(),
                false,
                inLava,
                false,
                state.worldTime(),
                System.currentTimeMillis());
    }
}
