package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.ai.BotNeeds;
import dev.botalive.core.chat.PhraseCategory;
import dev.botalive.core.economy.MarketBoard;
import dev.botalive.core.inventory.InventoryHelper;
import dev.botalive.core.util.BlockPos;

import java.util.Optional;
import dev.botalive.core.pathfinding.PathGoal;

/**
 * Nákup na trhu – když je jednodušší si věc koupit než vyrobit.
 *
 * <p>Hladový bot s penězi si koupí jídlo z cizí nabídky (slušnější než
 * krást); línější boti si koupí i železo, místo aby ho sami kopali. Zamluví
 * si nabídku na {@link MarketBoard} (první bere), dojde k prodejci, peníze
 * se převedou při předávce a zboží posbírá ze země.</p>
 */
public final class BuyGoal extends AbstractGoal {

    private enum Phase { TRAVEL, PICKUP, DONE }

    private final MarketBoard market;

    private Phase phase = Phase.TRAVEL;
    private MarketBoard.Offer offer;
    private int travelTicks;
    private int lingerTicks;
    private boolean wasClose;
    private int cooldownTicks;

    /**
     * @param market tržiště
     */
    public BuyGoal(MarketBoard market) {
        super("buy");
        this.market = market;
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (!ctx.config().economy().enabled() || !ctx.config().economy().botTrade()) {
            return 0;
        }
        // Trh se odehrává doma v overworldu.
        if (outsideOverworld(ctx)) {
            return 0;
        }
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        Optional<MarketBoard.Offer> wanted = findWantedOffer(ctx, bot);
        if (wanted.isEmpty()) {
            return 0;
        }
        if (InventoryHelper.isFood(wanted.get().material())) {
            int food = InventoryHelper.countEstimate(ctx.serverView().latest(),
                    InventoryHelper::isFood);
            return 13 + Math.max(0, 4 - food) * 3;
        }
        double laziness = bot.personality().trait(Trait.LAZINESS);
        return 7 + laziness * 6;
    }

    @Override
    public void start(Bot bot) {
        BotContext ctx = ctx(bot);
        phase = Phase.TRAVEL;
        travelTicks = 0;
        lingerTicks = 0;
        wasClose = false;
        offer = findWantedOffer(ctx, bot).orElse(null);
        if (offer == null || !market.claim(offer.id(), bot.id(), bot.name())) {
            offer = null; // někdo byl rychlejší
            cooldownTicks = 1200;
            phase = Phase.DONE;
            return;
        }
        if (ctx.rng().chance(0.6)) {
            ctx.chat().sayFrom(PhraseCategory.MARKET_DEAL, offer.sellerName());
        }
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        if (offer == null) {
            phase = Phase.DONE;
            return;
        }
        switch (phase) {
            case TRAVEL -> travel(ctx, bot);
            case PICKUP -> pickup(ctx, bot);
            case DONE -> {
            }
        }
    }

    @Override
    public boolean finished(Bot bot) {
        return phase == Phase.DONE;
    }

    @Override
    public String explain(Bot bot) {
        return switch (phase) {
            case TRAVEL -> "jdu na trh nakoupit"
                    + (offer == null ? "" : " (" + offer.material().name()
                    .toLowerCase(java.util.Locale.ROOT) + " od " + offer.sellerName() + ")");
            case PICKUP -> "přebírám nákup";
            case DONE -> null;
        };
    }

    // ==================================================================

    private void travel(BotContext ctx, Bot bot) {
        if (++travelTicks > 900) {
            cooldownTicks = 2400; // nestihl to – deal vyprší TTL sám
            phase = Phase.DONE;
            return;
        }
        // Jde se za prodejcem (entita má přednost, spot z nabídky je záloha).
        var sellerPos = ctx.entities().byUuid(offer.seller())
                .map(e -> e.position())
                .filter(java.util.Objects::nonNull)
                .map(p -> p.toBlockPos())
                .orElse(offer.pos());
        if (ctx.position().toBlockPos().distanceSquared(sellerPos) <= 2 * 2) {
            ctx.navigator().stop();
            wasClose = true;
            phase = Phase.PICKUP;
            return;
        }
        ctx.navigator().navigateTo(ctx.position(), PathGoal.near(sellerPos, 2));
        if (!ctx.navigator().navigating()) {
            cooldownTicks = 2400;
            phase = Phase.DONE;
        }
    }

    private void pickup(BotContext ctx, Bot bot) {
        // Sbírá se, co prodejce hází; konec, když obchod skončil a zem je čistá.
        var item = ctx.entities().nearest(ctx.position(), 8,
                dev.botalive.core.entity.TrackedEntity::isItem);
        if (item.isPresent() && item.get().position() != null) {
            ctx.navigator().navigateTo(ctx.position(),
                    item.get().position().toBlockPos());
            lingerTicks = 0;
            return;
        }
        if (market.hasDeal(offer.seller())) {
            // Prodejce ještě hází / řeší peníze – vydržet u pultu.
            if (++lingerTicks > 700) {
                cooldownTicks = 2400;
                phase = Phase.DONE;
            }
            return;
        }
        if (++lingerTicks > 40) {
            finishPurchase(ctx, bot);
        }
    }

    /** Nákup uzavřen: obchod sbližuje i kupce. */
    private void finishPurchase(BotContext ctx, Bot bot) {
        if (wasClose && ctx.worldView() != null) {
            BlockPos pos = ctx.position().toBlockPos();
            bot.memory().remember(MemoryKind.FRIEND, ctx.worldView().worldName(),
                    pos.x(), pos.y(), pos.z(), offer.seller(),
                    java.util.Map.of("via", "trade"), 0.3);
        }
        cooldownTicks = 1200;
        phase = Phase.DONE;
    }

    /** Nabídka, která botovi dává smysl a má na ni peníze. */
    private Optional<MarketBoard.Offer> findWantedOffer(BotContext ctx, Bot bot) {
        if (ctx.worldView() == null) {
            return Optional.empty();
        }
        var snapshot = ctx.serverView().latest();
        if (snapshot == null) {
            return Optional.empty();
        }
        double balance = bot.wallet().balance();
        int food = InventoryHelper.countEstimate(snapshot, InventoryHelper::isFood);
        BotNeeds needs = BotNeeds.assess(snapshot);
        boolean wantsIron = needs.pickaxeTier() == 3 && !needs.hasIronMaterial();
        int fuel = InventoryHelper.countEstimate(snapshot,
                dev.botalive.core.inventory.FurnaceService::isFuel);
        // Poptávka: jídlo je univerzální (každý jí – hlavní likvidita trhu),
        // železo pro krumpáč a palivo do zásoby, když dochází. Dřív se kupovalo
        // jen jídlo pod 4 a železo → nabídky (většinou chleba) nikdo nebral a
        // nástěnka jen expirovala.
        return market.findNearby(ctx.worldView().worldName(),
                ctx.position().toBlockPos(), 48, bot.id(), o -> {
                    if (o.price() > balance) {
                        return false;
                    }
                    if (InventoryHelper.isFood(o.material())) {
                        return food < 8;
                    }
                    if (o.material() == org.bukkit.Material.IRON_INGOT) {
                        return wantsIron;
                    }
                    return fuel < 4
                            && dev.botalive.core.inventory.FurnaceService.isFuel(o.material());
                });
    }
}
