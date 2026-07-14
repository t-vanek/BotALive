package dev.botalive.core.station;

import dev.botalive.core.ai.BotContext;
import dev.botalive.core.container.ContainerView;
import dev.botalive.core.inventory.FurnaceService;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.state.ItemMapper;
import org.bukkit.Material;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType;

import java.util.concurrent.CompletableFuture;

/**
 * Paketová stanice pecí – vkládání a vybírání container kliky.
 *
 * <p>Rozvržení okna pece: 0 = surovina, 1 = palivo, 2 = výsledek, dále
 * hráčova sekce. Shift-klik z hráčovy sekce vanilla směruje sám (tavitelné
 * do 0, palivo do 1), takže vložení je jeden klik na správný stack.
 * Klasifikaci surovin/paliva sdílíme se server-side implementací
 * ({@link FurnaceService#isSmeltable}, {@link FurnaceService#isFuel}).</p>
 */
public final class PacketFurnaceStation implements FurnaceStation {

    private static final int SLOT_INPUT = 0;
    private static final int SLOT_FUEL = 1;
    private static final int SLOT_RESULT = 2;

    @Override
    public CompletableFuture<InsertReport> insert(BotContext ctx, String worldName, BlockPos pos) {
        return StationFlow.run("smelt-in-" + ctx.bot().name(), InsertReport.EMPTY, () -> {
            ItemMapper mapper = ctx.itemMapper();
            if (mapper == null) {
                return InsertReport.EMPTY;
            }
            ContainerView view = awaitFurnace(ctx);
            if (view == null) {
                return InsertReport.EMPTY;
            }
            int inserted = moveFirstMatching(ctx, view, mapper, FurnaceService::isSmeltable,
                    SLOT_INPUT);
            StationFlow.humanPause();
            int fueled = moveFirstMatching(ctx, view, mapper, FurnaceService::isFuel, SLOT_FUEL);
            return new InsertReport(inserted, fueled);
        });
    }

    @Override
    public CompletableFuture<Integer> collect(BotContext ctx, String worldName, BlockPos pos) {
        return StationFlow.run("smelt-out-" + ctx.bot().name(), 0, () -> {
            ContainerView view = awaitFurnace(ctx);
            if (view == null) {
                return 0;
            }
            int amount = Windows.amountAt(view, SLOT_RESULT);
            if (amount == 0) {
                return 0;
            }
            ctx.clicker().shiftClick(view.containerId(), SLOT_RESULT);
            StationFlow.await(() -> Windows.amountAt(view, SLOT_RESULT) < amount, 800);
            return amount - Windows.amountAt(view, SLOT_RESULT);
        });
    }

    /** Počká na otevřené okno pece (pec, tavicí pec i udírna). */
    private static ContainerView awaitFurnace(BotContext ctx) throws InterruptedException {
        return StationFlow.awaitWindow(ctx, v -> v.type() == ContainerType.FURNACE
                || v.type() == ContainerType.BLAST_FURNACE
                || v.type() == ContainerType.SMOKER, 3_000);
    }

    /**
     * Shift-klikne první vyhovující stack hráčovy sekce; cílový slot musí být
     * prázdný nebo stejného typu (jinak by se přesun stejně nekonal).
     */
    private static int moveFirstMatching(BotContext ctx, ContainerView view, ItemMapper mapper,
                                         java.util.function.Predicate<Material> filter,
                                         int targetSlot) throws InterruptedException {
        Material targetType = Windows.materialAt(view, mapper, targetSlot);
        for (int slot = Windows.playerStart(view); slot < view.totalSlots(); slot++) {
            Material material = Windows.materialAt(view, mapper, slot);
            if (material == null || !filter.test(material)) {
                continue;
            }
            if (targetType != null && targetType != material) {
                continue;
            }
            int before = Windows.amountAt(view, slot);
            ctx.clicker().shiftClick(view.containerId(), slot);
            final int slotIndex = slot;
            StationFlow.await(() -> Windows.amountAt(view, slotIndex) != before, 800);
            return Math.max(0, before - Windows.amountAt(view, slot));
        }
        return 0;
    }
}
