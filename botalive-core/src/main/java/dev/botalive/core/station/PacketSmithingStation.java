package dev.botalive.core.station;

import dev.botalive.core.ai.BotContext;
import dev.botalive.core.container.ContainerView;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.state.ItemMapper;
import org.bukkit.Material;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType;

import java.util.concurrent.CompletableFuture;

/**
 * Paketová stanice kovářského stolu – povýšení na netherit container kliky.
 *
 * <p>Rozvržení okna: 0 = šablona, 1 = povyšovaný kus, 2 = ingot,
 * 3 = výsledek, dále hráčova sekce. Vstupy se přenášejí explicitní dvojicí
 * kliků (zvednout stack → položit do slotu) – deterministické bez ohledu na
 * shift-click routing; výsledek se vybírá shift-klikem (server při něm
 * spotřebuje vstupy).</p>
 */
public final class PacketSmithingStation implements SmithingStation {

    private static final int SLOT_TEMPLATE = 0;
    private static final int SLOT_BASE = 1;
    private static final int SLOT_ADDITION = 2;
    private static final int SLOT_RESULT = 3;

    @Override
    public CompletableFuture<UpgradeReport> upgrade(BotContext ctx, String worldName,
                                                    BlockPos pos) {
        return StationFlow.run("smith-" + ctx.bot().name(), UpgradeReport.NONE, () -> {
            ItemMapper mapper = ctx.itemMapper();
            if (mapper == null) {
                return UpgradeReport.NONE;
            }
            ContainerView view = StationFlow.awaitWindow(ctx,
                    v -> v.type() == ContainerType.SMITHING, 3_000);
            if (view == null) {
                return UpgradeReport.NONE;
            }
            Windows.SlotMaps items = Windows.playerSection(view, mapper);
            Material base = firstUpgradable(items);
            int templateSlot = items.findSlot(
                    m -> m == Material.NETHERITE_UPGRADE_SMITHING_TEMPLATE);
            int baseSlot = base == null ? -1 : items.findSlot(m -> m == base);
            int ingotSlot = items.findSlot(m -> m == Material.NETHERITE_INGOT);
            if (templateSlot < 0 || baseSlot < 0 || ingotSlot < 0) {
                return UpgradeReport.NONE;
            }
            if (!place(ctx, view, templateSlot, SLOT_TEMPLATE)
                    || !place(ctx, view, baseSlot, SLOT_BASE)
                    || !place(ctx, view, ingotSlot, SLOT_ADDITION)) {
                return UpgradeReport.NONE;
            }
            // Server dopočítá výsledek; jeho výběr spotřebuje vstupy.
            if (!StationFlow.await(() -> Windows.amountAt(view, SLOT_RESULT) > 0, 1_500)) {
                return UpgradeReport.NONE;
            }
            Material result = Windows.materialAt(view, mapper, SLOT_RESULT);
            StationFlow.humanPause();
            ctx.clicker().shiftClick(view.containerId(), SLOT_RESULT);
            StationFlow.await(() -> Windows.amountAt(view, SLOT_RESULT) == 0, 1_000);
            return new UpgradeReport(base, result);
        });
    }

    /** Nejcennější diamantový kus v hráčově sekci dle {@link #UPGRADE_ORDER}. */
    private static Material firstUpgradable(Windows.SlotMaps items) {
        for (String candidate : UPGRADE_ORDER) {
            Material material = Material.valueOf(candidate);
            if (items.findSlot(m -> m == material) >= 0) {
                return material;
            }
        }
        return null;
    }

    /** Přenese stack dvojicí kliků (zvednout → položit) a počká na potvrzení. */
    private static boolean place(BotContext ctx, ContainerView view, int from, int to)
            throws InterruptedException {
        ctx.clicker().leftClick(view.containerId(), from);
        StationFlow.humanPause();
        ctx.clicker().leftClick(view.containerId(), to);
        return StationFlow.await(() -> Windows.amountAt(view, to) > 0, 800);
    }
}
