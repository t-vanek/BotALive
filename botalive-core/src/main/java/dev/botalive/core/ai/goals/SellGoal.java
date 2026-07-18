package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.bot.ServerSideView;
import dev.botalive.core.chat.PhraseCategory;
import dev.botalive.core.economy.MarketBoard;
import dev.botalive.core.economy.MarketPrices;
import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.inventory.InventoryHelper;
import dev.botalive.core.pvp.PvpCoordinator;
import org.bukkit.Material;

import java.util.Locale;
import java.util.Optional;

/**
 * Prodej přebytků na trhu – chamtivá sestra {@code ShareGoal}.
 *
 * <p>Bot s přebytkem (jídlo, uhlí, železo) a publikem okolo vyvěsí nabídku
 * na {@link MarketBoard}, zavolá ji do chatu a chvíli počká. Když si ji
 * někdo zamluví, počká na něj, při předávce proběhnou peníze
 * ({@link MarketBoard#settle}) a zboží se hází kus po kuse jako u sdílení.
 * Kamarádi dostávají slevu; vydařený obchod obě strany sbližuje.</p>
 */
public final class SellGoal extends AbstractGoal {

    /** Nabídnuté zboží: materiál, počet, plná cena. */
    private record Sale(Material material, int count, double price) {
    }

    private enum Phase { OFFER, WAIT, HANDOVER, DONE }

    private final MarketBoard market;

    private Phase phase = Phase.OFFER;
    private Sale sale;
    private MarketBoard.Deal deal;
    private int waitTicks;
    private int handoverTicks;
    private int given;
    private int giveTicks;
    private int cooldownTicks;

    /**
     * @param market tržiště
     */
    public SellGoal(MarketBoard market) {
        super("sell");
        this.market = market;
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (!tradeEnabled(ctx)) {
            return 0;
        }
        // Rozjednaný obchod se dotahuje přednostně (kupec už jde).
        if (market.pendingDeal(bot.id()).isPresent()) {
            return 22;
        }
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        if (pickSale(ctx, bot) == null) {
            return 0;
        }
        // Bez publika nemá smysl vyvolávat.
        Optional<TrackedEntity> audience = ctx.entities()
                .nearest(ctx.position(), 16, TrackedEntity::isPlayer);
        if (audience.isEmpty()) {
            return 0;
        }
        double greed = bot.personality().trait(Trait.GREED);
        return 4 + greed * 9;
    }

    @Override
    public void start(Bot bot) {
        phase = Phase.OFFER;
        sale = null;
        deal = null;
        given = 0;
        giveTicks = 0;
        handoverTicks = 0;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        switch (phase) {
            case OFFER -> offer(ctx, bot);
            case WAIT -> waitForBuyer(ctx, bot);
            case HANDOVER -> handover(ctx, bot);
            case DONE -> {
            }
        }
    }

    @Override
    public void stop(Bot bot) {
        // Nedokončený prodej nenechávat viset na nástěnce.
        if (phase == Phase.OFFER || phase == Phase.WAIT) {
            market.withdraw(bot.id());
        }
        super.stop(bot);
    }

    @Override
    public boolean finished(Bot bot) {
        return phase == Phase.DONE;
    }

    @Override
    public String explain(Bot bot) {
        return switch (phase) {
            case OFFER, WAIT -> "prodávám na trhu"
                    + (sale == null ? "" : " – " + describe(sale));
            case HANDOVER -> "předávám zboží kupci";
            case DONE -> null;
        };
    }

    // ==================================================================

    private void offer(BotContext ctx, Bot bot) {
        // Rozjednaný obchod z minula (cíl mezitím přerušen) – rovnou předávka.
        var pending = market.pendingDeal(bot.id());
        if (pending.isPresent()) {
            deal = pending.get();
            sale = new Sale(deal.offer().material(), deal.offer().count(),
                    deal.offer().price());
            phase = Phase.HANDOVER;
            return;
        }
        sale = pickSale(ctx, bot);
        if (sale == null || ctx.worldView() == null) {
            cooldownTicks = 2400;
            phase = Phase.DONE;
            return;
        }
        market.post(bot.id(), bot.name(), ctx.worldView().worldName(),
                ctx.position().toBlockPos(), sale.material(), sale.count(),
                sale.price());
        ctx.chat().sayFrom(PhraseCategory.MARKET_OFFER, describe(sale));
        waitTicks = 900; // ~45 s vyvolávání
        phase = Phase.WAIT;
    }

    private void waitForBuyer(BotContext ctx, Bot bot) {
        var pending = market.pendingDeal(bot.id());
        if (pending.isPresent()) {
            deal = pending.get();
            handoverTicks = 0;
            phase = Phase.HANDOVER;
            return;
        }
        if (--waitTicks <= 0) {
            market.withdraw(bot.id());
            cooldownTicks = 4800; // dnes to nikdo nechtěl
            phase = Phase.DONE;
        }
        // Stojí se na místě a vyhlíží zákazník – mikro-chování řeší humanizer.
    }

    private void handover(BotContext ctx, Bot bot) {
        Optional<TrackedEntity> buyer = ctx.entities().byUuid(deal.buyer());
        if (buyer.isEmpty() || ++handoverTicks > 700) {
            market.withdraw(bot.id());
            cooldownTicks = 2400;
            phase = Phase.DONE;
            return;
        }
        var buyerPos = buyer.get().position();
        if (buyerPos == null) {
            return;
        }
        ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0),
                buyerPos.add(0, 1.5, 0));
        if (ctx.position().distanceSquared(buyerPos) > 3.5 * 3.5) {
            return; // kupec ještě dochází
        }
        // Kupec u pultu: nejdřív peníze (kamarádská sleva), pak zboží.
        if (given == 0) {
            boolean friend = bot.memory().recallAbout(deal.buyer()).stream()
                    .anyMatch(r -> r.kind() == MemoryKind.FRIEND
                            && r.importance() >= PvpCoordinator.ALLY_THRESHOLD);
            double price = friend
                    ? MarketPrices.friendly(deal.offer().price())
                    : deal.offer().price();
            if (!market.settle(deal, price)) {
                // Kupec na to nemá – obchod zrušit.
                ctx.chat().sayFrom(PhraseCategory.MARKET_DECLINE, deal.buyerName());
                market.withdraw(bot.id());
                cooldownTicks = 2400;
                phase = Phase.DONE;
                return;
            }
        }
        if (--giveTicks > 0) {
            return;
        }
        if (given >= deal.offer().count()
                || !ctx.inventory().equipItem(ctx.serverView().latest(),
                deal.offer().material())) {
            completeSale(ctx, bot);
            return;
        }
        ctx.actions().dropItem();
        given++;
        giveTicks = ctx.rng().rangeInt(6, 12);
    }

    /** Zboží předáno: uzavřít, sblížit se, hláška. */
    private void completeSale(BotContext ctx, Bot bot) {
        market.completeDeal(bot.id());
        if (ctx.worldView() != null && deal != null) {
            var pos = ctx.position();
            bot.memory().remember(MemoryKind.FRIEND, ctx.worldView().worldName(),
                    (int) pos.x(), (int) pos.y(), (int) pos.z(), deal.buyer(),
                    java.util.Map.of("via", "trade"), 0.3);
        }
        if (ctx.rng().chance(0.6)) {
            ctx.chat().sayFrom(PhraseCategory.MARKET_DEAL, deal == null
                    ? null : deal.buyerName());
        }
        cooldownTicks = 6000;
        phase = Phase.DONE;
    }

    /** Najde přebytek k prodeji (jídlo → uhlí → železo), nebo {@code null}. */
    private Sale pickSale(BotContext ctx, Bot bot) {
        ServerSideView.Snapshot snapshot = ctx.serverView().latest();
        if (snapshot == null) {
            return null;
        }
        double greed = bot.personality().trait(Trait.GREED);
        int totalFood = InventoryHelper.countEstimate(snapshot, InventoryHelper::isFood);
        for (Material material : new Material[]{Material.BREAD, Material.COOKED_BEEF,
                Material.COOKED_PORKCHOP, Material.COOKED_CHICKEN,
                Material.COOKED_MUTTON, Material.BAKED_POTATO}) {
            if (totalFood > 12
                    && InventoryHelper.countEstimate(snapshot, m -> m == material) >= 6) {
                return new Sale(material, 5, MarketPrices.price(material, 5, greed));
            }
        }
        if (InventoryHelper.countEstimate(snapshot, m -> m == Material.COAL) > 20) {
            return new Sale(Material.COAL, 8,
                    MarketPrices.price(Material.COAL, 8, greed));
        }
        if (InventoryHelper.countEstimate(snapshot, m -> m == Material.IRON_INGOT) > 14) {
            return new Sale(Material.IRON_INGOT, 6,
                    MarketPrices.price(Material.IRON_INGOT, 6, greed));
        }
        return null;
    }

    private static String describe(Sale sale) {
        String price = sale.price() == Math.floor(sale.price())
                ? String.valueOf((long) sale.price())
                : String.valueOf(sale.price());
        return sale.count() + "x " + sale.material().name().toLowerCase(Locale.ROOT)
                + " za " + price;
    }

    private static boolean tradeEnabled(BotContext ctx) {
        return ctx.config().economy().enabled() && ctx.config().economy().botTrade();
    }
}
