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
 *
 * <p>Kupcem může být i skutečný hráč: na vyvolávanou nabídku odpoví
 * „beru!", chat mu ji zamluví ({@code ChatContext.marketBuyRequest}) a bot
 * si u pultu řekne o peníze přes {@code /pay}. Příchozí platbu ověří na
 * vlastním účtu (Vault zrcadlo, porovnání zůstatku před/po s timeoutem) a
 * teprve pak zboží vydá – bez peněz žádné zboží.</p>
 */
public final class SellGoal extends AbstractGoal {

    /** Nabídnuté zboží: materiál, počet, plná cena. */
    private record Sale(Material material, int count, double price) {
    }

    /**
     * Kolik ticků má bot-kupec na dojití a předávku.
     *
     * <p>MUSÍ být větší než cestovní rozpočet kupce ({@code BuyGoal}: 900) –
     * jinak prodejce stáhne nabídku dřív, než kupec vůbec dojde, a každý obchod
     * na vzdálenost přes ~35 s cesty systematicky selhal. Zároveň se musí vejít
     * pod {@code MarketBoard.DEAL_TTL_MS} (90 s = 1800 ticků).
     */
    private static final int BOT_HANDOVER_TICKS = 1000;
    /** Hráč potřebuje čas dojít a napsat /pay (~90 s). */
    private static final int PLAYER_HANDOVER_TICKS = 1800;

    private enum Phase { OFFER, WAIT, HANDOVER, DONE }

    private final MarketBoard market;
    private final dev.botalive.core.social.SocialGraph graph;

    private Phase phase = Phase.OFFER;
    private Sale sale;
    private MarketBoard.Deal deal;
    private int waitTicks;
    private int handoverTicks;
    private int given;
    private int giveTicks;
    private int cooldownTicks;
    /** Platba hráče: zůstatek před výzvou k /pay (NaN = výzva ještě nepadla). */
    private double paymentBaseline = Double.NaN;
    /** Nabídka, ke které se váže {@link #paymentBaseline} (přežije přerušení cíle). */
    private long paymentOfferId = -1;
    /** Už zaplacená nabídka – při návratu k přerušené předávce se neplatí znovu. */
    private long paidOfferId = -1;
    private boolean paid;

    /**
     * @param market tržiště
     * @param graph  sociální adresář (rozlišení kupec-bot vs. kupec-hráč)
     */
    public SellGoal(MarketBoard market, dev.botalive.core.social.SocialGraph graph) {
        super("sell");
        this.market = market;
        this.graph = graph;
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (!tradeEnabled(ctx)) {
            return 0;
        }
        // Trh se odehrává doma v overworldu – z Netheru se neprodává.
        if (outsideOverworld(ctx)) {
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
        // Musí být v pásmu stash (8 + greed*10 + zaplněnost*1.5, až ~29):
        // obojí se spouští na TÉŽE podmínce (přebytek v inventáři), takže se
        // starým základem 4 + greed*9 (max 13) prodej nikdy nevyhrál – bot
        // přebytek vždy uložil do truhly, nabídka na trhu nevznikla a s ní ani
        // poptávka (BuyGoal nemá co koupit). Trh proto stál úplně.
        return 10 + greed * 14;
    }

    @Override
    public void start(Bot bot) {
        phase = Phase.OFFER;
        sale = null;
        deal = null;
        given = 0;
        giveTicks = 0;
        handoverTicks = 0;
        paid = false;
        // paymentBaseline se neresetuje – váže se k nabídce (paymentOfferId),
        // aby platba hráče přežila přerušení cíle (boj, útěk) mezi výzvou
        // a příchodem peněz.
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
            enterHandover(ctx, bot);
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
            enterHandover(ctx, bot);
            return;
        }
        if (--waitTicks <= 0) {
            market.withdraw(bot.id());
            cooldownTicks = 4800; // dnes to nikdo nechtěl
            phase = Phase.DONE;
        }
        // Stojí se na místě a vyhlíží zákazník – mikro-chování řeší humanizer.
    }

    /**
     * Vstup do předávky. Kupci-hráči se hned zapamatuje zůstatek (baseline)
     * a řekne cena – hráč smí poslat {@code /pay} klidně cestou k pultu,
     * platba se pozná porovnáním s baseline, ne okamžikem příchodu.
     */
    private void enterHandover(BotContext ctx, Bot bot) {
        handoverTicks = 0;
        phase = Phase.HANDOVER;
        if (paidOfferId == deal.offer().id()) {
            paid = true; // návrat k přerušené předávce – zaplaceno už bylo
            return;
        }
        if (graph != null && !graph.isBot(deal.buyer())
                && paymentOfferId != deal.offer().id()) {
            paymentOfferId = deal.offer().id();
            paymentBaseline = bot.wallet().balance();
            ctx.chat().sayFrom(PhraseCategory.MARKET_PAY_REQUEST,
                    priceLabel(priceFor(bot)));
        }
    }

    private void handover(BotContext ctx, Bot bot) {
        boolean playerBuyer = graph != null && !graph.isBot(deal.buyer());
        int limit = playerBuyer ? PLAYER_HANDOVER_TICKS : BOT_HANDOVER_TICKS;
        Optional<TrackedEntity> buyer = ctx.entities().byUuid(deal.buyer());
        handoverTicks++;
        boolean gone = buyer.isEmpty() || handoverTicks > limit;
        if (gone && !paid && playerBuyer && playerPaymentArrived(ctx, bot)) {
            paid = true; // platba dorazila na poslední chvíli
        }
        if (gone && !paid) {
            // Hráč, který slíbil koupi a nezaplatil, si vyslechne svoje.
            if (playerBuyer) {
                ctx.chat().sayFrom(PhraseCategory.MARKET_DECLINE, deal.buyerName());
            }
            market.withdraw(bot.id());
            cooldownTicks = 2400;
            phase = Phase.DONE;
            return;
        }
        if (!gone) {
            var buyerPos = buyer.get().position();
            if (buyerPos == null) {
                return;
            }
            ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0),
                    buyerPos.add(0, 1.5, 0));
            // Platba hráče se hlídá už během jeho cesty (mohl poslat /pay hned).
            if (playerBuyer && !paid && playerPaymentArrived(ctx, bot)) {
                paid = true;
            }
            if (ctx.position().distanceSquared(buyerPos) > 3.5 * 3.5) {
                return; // kupec ještě dochází
            }
        }
        // Zaplaceno, ale kupec do limitu nedošel/zmizel („gone" a „paid"
        // zároveň): zboží se vyloží u pultu – co je zaplacené, patří kupci.
        // Kupec u pultu: nejdřív peníze (kamarádská sleva), pak zboží.
        if (given == 0 && !paid) {
            if (playerBuyer) {
                return; // čeká se na /pay; timeout hlídá handoverTicks
            }
            if (!market.settle(deal, priceFor(bot))) {
                // Kupec na to nemá – obchod zrušit.
                ctx.chat().sayFrom(PhraseCategory.MARKET_DECLINE, deal.buyerName());
                market.withdraw(bot.id());
                cooldownTicks = 2400;
                phase = Phase.DONE;
                return;
            }
            paid = true;
            paidOfferId = deal.offer().id();
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

    /** Cena pro aktuálního kupce (kamarádi – boti i hráči – mají slevu). */
    private double priceFor(Bot bot) {
        boolean friend = bot.memory().recallAbout(deal.buyer()).stream()
                .anyMatch(r -> r.kind() == MemoryKind.FRIEND
                        && r.importance() >= PvpCoordinator.ALLY_THRESHOLD);
        return friend ? MarketPrices.friendly(deal.offer().price())
                : deal.offer().price();
    }

    /**
     * Dorazila platba hráče? Porovnává zůstatek s baseline z {@link #enterHandover}
     * (Vault zrcadlo se periodicky srovnává se serverovou ekonomikou, protože
     * {@code /pay} se na něm projeví až po resyncu).
     */
    private boolean playerPaymentArrived(BotContext ctx, Bot bot) {
        if (Double.isNaN(paymentBaseline) || paymentOfferId != deal.offer().id()) {
            return false;
        }
        if (handoverTicks % 40 == 0
                && bot.wallet() instanceof dev.botalive.core.economy.VaultBotWallet vault) {
            vault.refresh();
        }
        if (bot.wallet().balance() >= paymentBaseline + priceFor(bot) - 0.001) {
            paymentBaseline = Double.NaN;
            paymentOfferId = -1;
            paidOfferId = deal.offer().id();
            return true;
        }
        return false;
    }

    /** Zboží předáno: uzavřít, sblížit se, hláška. */
    private void completeSale(BotContext ctx, Bot bot) {
        market.completeDeal(bot.id());
        paidOfferId = -1;
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
                Material.COOKED_MUTTON, Material.BAKED_POTATO,
                Material.COOKED_COD, Material.COOKED_SALMON, Material.COD, Material.SALMON}) {
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
        // Kořist z výprav: perly nad rezervu (hod perlou/oči Enderu jednou
        // přijdou – 12 kusů si bot nechává), quartz jako uhlí.
        if (InventoryHelper.countEstimate(snapshot, m -> m == Material.ENDER_PEARL) > 12) {
            return new Sale(Material.ENDER_PEARL, 4,
                    MarketPrices.price(Material.ENDER_PEARL, 4, greed));
        }
        if (InventoryHelper.countEstimate(snapshot, m -> m == Material.QUARTZ) > 24) {
            return new Sale(Material.QUARTZ, 12,
                    MarketPrices.price(Material.QUARTZ, 12, greed));
        }
        return null;
    }

    private static String describe(Sale sale) {
        return sale.count() + "x " + sale.material().name().toLowerCase(Locale.ROOT)
                + " za " + priceLabel(sale.price());
    }

    /** Cena bez zbytečných desetinných míst („12", ne „12.0"). */
    private static String priceLabel(double price) {
        return price == Math.floor(price)
                ? String.valueOf((long) price)
                : String.valueOf(price);
    }

    private static boolean tradeEnabled(BotContext ctx) {
        return ctx.config().economy().enabled() && ctx.config().economy().botTrade();
    }
}
