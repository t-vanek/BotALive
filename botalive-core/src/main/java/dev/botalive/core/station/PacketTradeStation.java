package dev.botalive.core.station;

import dev.botalive.core.ai.BotContext;
import dev.botalive.core.container.ContainerView;
import dev.botalive.core.world.state.ItemMapper;
import org.bukkit.Material;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.VillagerTrade;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Paketová stanice obchodu s vesničany.
 *
 * <p>Cíl ({@code TradeGoal}) vesničana oslovil interact paketem – server
 * otevřel okno MERCHANT a poslal nabídky (MerchantOffers). Výběr obchodu
 * jde přes {@code ServerboundSelectTradePacket}: vanilla server po něm
 * <b>sám přesune suroviny</b> hráče do obchodních slotů (0, 1) – nemusíme
 * skládat kliky. Pak stačí shift-klik na výsledek (slot 2); nedoplněný
 * výsledek znamená „nemám na to / vyprodáno" a jde se dál. Strategie je
 * stejná jako server-side: přednostně prodávat za smaragdy, při hladu
 * nakupovat jídlo.</p>
 */
public final class PacketTradeStation implements TradeStation {

    private static final int SLOT_RESULT = 2;

    @Override
    public CompletableFuture<TradeReport> trade(BotContext ctx, UUID villagerId, int maxTrades) {
        return StationFlow.run("trade-" + ctx.bot().name(), TradeReport.EMPTY, () -> {
            ItemMapper mapper = ctx.itemMapper();
            if (mapper == null) {
                return TradeReport.EMPTY;
            }
            ContainerView view = StationFlow.awaitWindow(ctx,
                    v -> v.type() == ContainerType.MERCHANT, 3_000);
            if (view == null) {
                return TradeReport.EMPTY;
            }
            StationFlow.await(() -> !view.trades().isEmpty(), 1_500);

            boolean hungry = ctx.clientState().food() < 14;
            int trades = 0;
            int emeralds = 0;
            int food = 0;

            List<VillagerTrade> offers = view.trades();
            for (int i = 0; i < offers.size() && trades < maxTrades; i++) {
                VillagerTrade offer = offers.get(i);
                if (offer.isOutOfStock() || offer.getResult() == null) {
                    continue;
                }
                Material result = mapper.materialOf(offer.getResult().getId());
                boolean selling = result == Material.EMERALD;
                boolean buyingFood = hungry && result != null && result.isEdible();
                if (!selling && !buyingFood) {
                    continue;
                }
                while (trades < maxTrades) {
                    ctx.clicker().selectTrade(i); // server sám doplní suroviny
                    StationFlow.humanPause();
                    if (!StationFlow.await(() -> Windows.amountAt(view, SLOT_RESULT) > 0, 800)) {
                        break; // nemá suroviny / obchod vyčerpán
                    }
                    int gained = Windows.amountAt(view, SLOT_RESULT);
                    ctx.clicker().shiftClick(view.containerId(), SLOT_RESULT);
                    StationFlow.await(() -> Windows.amountAt(view, SLOT_RESULT) == 0, 800);
                    StationFlow.humanPause();
                    trades++;
                    if (selling) {
                        emeralds += gained;
                    } else {
                        food += gained;
                    }
                }
            }
            // Vrátit případné suroviny z obchodních slotů do inventáře.
            for (int slot = 0; slot < SLOT_RESULT; slot++) {
                if (Windows.amountAt(view, slot) > 0) {
                    ctx.clicker().shiftClick(view.containerId(), slot);
                    StationFlow.humanPause();
                }
            }
            return new TradeReport(trades, emeralds, food);
        });
    }
}
