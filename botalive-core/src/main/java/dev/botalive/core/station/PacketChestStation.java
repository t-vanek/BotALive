package dev.botalive.core.station;

import dev.botalive.core.ai.BotContext;
import dev.botalive.core.container.ContainerView;
import dev.botalive.core.inventory.ContainerService;
import dev.botalive.core.inventory.InventoryHelper;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.state.ItemMapper;
import org.bukkit.Material;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType;

import java.util.concurrent.CompletableFuture;

/**
 * Paketová stanice truhel – ukládání přebytků container kliky.
 *
 * <p>Cíl ({@code StashGoal}) truhlu otevřel interact paketem; tady počkáme
 * na otevřené okno, projdeme hráčovu sekci a přebytky shift-klikem přesuneme
 * do kontejneru. Plnou truhlu poznáme podle toho, že se slot po shift-kliku
 * nezmění. Klasifikace přebytků je sdílená se server-side implementací
 * ({@link ContainerService#isJunk}).</p>
 */
public final class PacketChestStation implements ChestStation {

    /** Kolik kusů stavebního materiálu si bot nechává (parita se server-side). */
    private static final int KEEP_BUILDING_BLOCKS = 32;

    @Override
    public CompletableFuture<Integer> depositJunk(BotContext ctx, String worldName,
                                                  BlockPos chestPos) {
        return StationFlow.run("stash-" + ctx.bot().name(), 0, () -> {
            ItemMapper mapper = ctx.itemMapper();
            if (mapper == null) {
                return 0;
            }
            ContainerView view = StationFlow.awaitWindow(ctx,
                    v -> v.containerSlots() > 0 && v.type() != ContainerType.MERCHANT, 3_000);
            if (view == null) {
                return 0;
            }
            int moved = 0;
            int keptBuilding = 0;
            for (int slot = Windows.playerStart(view); slot < view.totalSlots(); slot++) {
                Material material = Windows.materialAt(view, mapper, slot);
                if (material == null || !ContainerService.isJunk(material)) {
                    continue;
                }
                int before = Windows.amountAt(view, slot);
                if (InventoryHelper.isBuildingBlock(material) && keptBuilding < KEEP_BUILDING_BLOCKS) {
                    keptBuilding += before;
                    continue;
                }
                ctx.clicker().shiftClick(view.containerId(), slot);
                StationFlow.humanPause();
                final int slotIndex = slot;
                StationFlow.await(() -> Windows.amountAt(view, slotIndex) != before, 600);
                int after = Windows.amountAt(view, slot);
                moved += Math.max(0, before - after);
                if (after == before) {
                    break; // truhla je plná – server klik nepřijal
                }
            }
            return moved;
        });
    }
}
