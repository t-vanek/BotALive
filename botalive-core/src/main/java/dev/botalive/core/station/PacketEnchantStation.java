package dev.botalive.core.station;

import dev.botalive.core.ai.BotContext;
import dev.botalive.core.container.ContainerView;
import dev.botalive.core.inventory.EnchantService;
import dev.botalive.core.world.state.ItemMapper;
import org.bukkit.Material;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Paketová stanice enchantování – skutečné UI enchantovacího stolu.
 *
 * <p>Rozvržení okna: 0 = předmět, 1 = lapis; vlastnosti okna (SetData)
 * 0–2 nesou ceny tří nabídek v levelech. Tok: shift-klik výbavy (vanilla ji
 * směruje do slotu 0), shift-klik lapisu (slot 1), počkat na ceny, vybrat
 * nejdražší dosažitelnou nabídku (button klik = volba enchantu), počkat na
 * potvrzení (pokles levelů ze SetExperience) a vzít si předmět i zbylý
 * lapis zpět. Klasifikace výbavy je sdílená
 * ({@link EnchantService#isEnchantable}).</p>
 */
public final class PacketEnchantStation implements EnchantStation {

    private static final int SLOT_ITEM = 0;
    private static final int SLOT_LAPIS = 1;

    @Override
    public CompletableFuture<EnchantReport> enchantBest(BotContext ctx) {
        return StationFlow.run("enchant-" + ctx.bot().name(), EnchantReport.EMPTY, () -> {
            ItemMapper mapper = ctx.itemMapper();
            if (mapper == null) {
                return EnchantReport.EMPTY;
            }
            ContainerView view = StationFlow.awaitWindow(ctx,
                    v -> v.type() == ContainerType.ENCHANTMENT, 3_000);
            if (view == null) {
                return EnchantReport.EMPTY;
            }
            // Výbava do slotu 0, lapis do slotu 1 (shift-klik směruje vanilla).
            int itemSlot = findPlayerSlot(view, mapper, EnchantService::isEnchantable);
            int lapisSlot = findPlayerSlot(view, mapper, m -> m == Material.LAPIS_LAZULI);
            if (itemSlot < 0 || lapisSlot < 0) {
                return EnchantReport.EMPTY;
            }
            Material itemMaterial = Windows.materialAt(view, mapper, itemSlot);
            ctx.clicker().shiftClick(view.containerId(), itemSlot);
            StationFlow.humanPause();
            ctx.clicker().shiftClick(view.containerId(), lapisSlot);
            StationFlow.await(() -> Windows.amountAt(view, SLOT_ITEM) > 0
                    && Windows.amountAt(view, SLOT_LAPIS) > 0, 800);

            // Ceny nabídek (vlastnosti 0–2) a výběr nejdražší dosažitelné.
            StationFlow.await(() -> view.property(0) > 0, 1_200);
            int levels = ctx.clientState().expLevel();
            int lapis = Windows.amountAt(view, SLOT_LAPIS);
            int choice = -1;
            for (int option = 2; option >= 0; option--) {
                int cost = view.property(option);
                if (cost > 0 && levels >= cost && lapis >= option + 1) {
                    choice = option;
                    break;
                }
            }
            if (choice < 0) {
                takeBack(ctx, view);
                return EnchantReport.EMPTY;
            }
            ctx.clicker().clickButton(view.containerId(), choice);
            // Potvrzení: server strhne levely (SetExperience) – čekáme na pokles.
            boolean confirmed = StationFlow.await(
                    () -> ctx.clientState().expLevel() < levels, 1_500);
            StationFlow.humanPause();
            takeBack(ctx, view);
            if (!confirmed) {
                return EnchantReport.EMPTY;
            }
            String name = itemMaterial == null ? "výbava"
                    : itemMaterial.name().toLowerCase(Locale.ROOT);
            return new EnchantReport(name + " (enchant stůl)", choice + 1);
        });
    }

    /** Vrátí předmět i zbylý lapis do inventáře. */
    private static void takeBack(BotContext ctx, ContainerView view) throws InterruptedException {
        if (Windows.amountAt(view, SLOT_ITEM) > 0) {
            ctx.clicker().shiftClick(view.containerId(), SLOT_ITEM);
            StationFlow.humanPause();
        }
        if (Windows.amountAt(view, SLOT_LAPIS) > 0) {
            ctx.clicker().shiftClick(view.containerId(), SLOT_LAPIS);
        }
    }

    /** Najde slot hráčovy sekce s vyhovujícím materiálem. */
    private static int findPlayerSlot(ContainerView view, ItemMapper mapper,
                                      java.util.function.Predicate<Material> filter) {
        for (int slot = Windows.playerStart(view); slot < view.totalSlots(); slot++) {
            Material material = Windows.materialAt(view, mapper, slot);
            if (material != null && filter.test(material)) {
                return slot;
            }
        }
        return -1;
    }
}
