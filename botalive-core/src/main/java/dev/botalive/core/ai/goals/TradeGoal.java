package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.station.TradeStation;
import dev.botalive.core.trade.TradeService;
import dev.botalive.core.util.Vec3;
import org.bukkit.Material;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Obchodování s vesničany.
 *
 * <p>Bot s prodejnými komoditami (plodiny, uhlí...) vyhledá vesničana, dojde
 * k němu, otevře obchod interact paketem, chvíli si „prohlíží nabídku"
 * a zobchoduje ({@link TradeService}). Vesnici si zapamatuje
 * ({@link MemoryKind#VILLAGE}) a výdělek se promítne do peněženky. Hladoví
 * boti se smaragdy nakupují jídlo. Chamtiví boti obchodují častěji.</p>
 */
public final class TradeGoal extends AbstractGoal {

    /** Hodnota jednoho smaragdu ve vnitřní ekonomice. */
    private static final double EMERALD_VALUE = 10.0;

    private enum Phase { FIND, GO, INTERACT, BROWSE, TRADE, CLOSE, DONE }

    private final TradeStation trades;

    private Phase phase = Phase.FIND;
    private UUID villagerUuid;
    private int villagerEntityId;
    private int waitTicks;
    private CompletableFuture<TradeStation.TradeReport> pending;
    private int cooldownTicks;

    /**
     * @param trades sdílená obchodní služba
     */
    public TradeGoal(TradeStation trades) {
        super("trade");
        this.trades = trades;
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        if (!ctx.config().economy().enabled() || ctx.clientState().dead()) {
            return 0;
        }
        Optional<TrackedEntity> villager = findVillager(ctx);
        if (villager.isEmpty()) {
            return 0;
        }
        var snapshot = ctx.serverView().latest();
        if (snapshot == null) {
            return 0;
        }
        boolean hasGoods = snapshot.hasItem(TradeService::isSellable);
        boolean needsFood = ctx.clientState().food() < 12
                && snapshot.hasItem(m -> m == Material.EMERALD);
        if (!hasGoods && !needsFood) {
            return 0;
        }
        double greed = bot.personality().trait(Trait.GREED);
        return (needsFood ? 14 : 5) + greed * 15;
    }

    @Override
    public void start(Bot bot) {
        phase = Phase.FIND;
        villagerUuid = null;
        pending = null;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        switch (phase) {
            case FIND -> {
                Optional<TrackedEntity> villager = findVillager(ctx);
                if (villager.isEmpty()) {
                    finish(ctx, 1200);
                    return;
                }
                villagerUuid = villager.get().uuid();
                villagerEntityId = villager.get().entityId();
                phase = Phase.GO;
            }
            case GO -> {
                Optional<TrackedEntity> villager = tracked(ctx);
                if (villager.isEmpty()) {
                    phase = Phase.FIND; // odešel z dohledu
                    return;
                }
                Vec3 pos = villager.get().position();
                ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0), pos.add(0, 1.6, 0));
                if (pos.distanceSquared(ctx.position()) > 2.5 * 2.5) {
                    ctx.navigator().navigateTo(ctx.position(), pos.toBlockPos());
                    if (!ctx.navigator().navigating()) {
                        finish(ctx, 1200);
                    }
                    return;
                }
                ctx.navigator().stop();
                waitTicks = ctx.rng().rangeInt(4, 12); // krátké zaváhání před klikem
                phase = Phase.INTERACT;
            }
            case INTERACT -> {
                if (--waitTicks > 0) {
                    return;
                }
                // Otevření obchodu – vesničan se otočí, okolí interakci vidí.
                ctx.actions().interactEntity(villagerEntityId);
                waitTicks = ctx.rng().rangeInt(20, 50); // „prohlíží nabídku"
                phase = Phase.BROWSE;
            }
            case BROWSE -> {
                if (--waitTicks <= 0) {
                    pending = trades.trade(ctx, villagerUuid, ctx.rng().rangeInt(1, 4));
                    phase = Phase.TRADE;
                }
            }
            case TRADE -> {
                if (pending == null || !pending.isDone()) {
                    return;
                }
                TradeStation.TradeReport report = pending.getNow(TradeStation.TradeReport.EMPTY);
                pending = null;
                if (report.emeraldsGained() > 0) {
                    bot.wallet().deposit(report.emeraldsGained() * EMERALD_VALUE,
                            "prodej vesničanovi");
                }
                rememberVillage(ctx, bot, report.trades() > 0);
                waitTicks = ctx.rng().rangeInt(5, 15);
                phase = Phase.CLOSE;
            }
            case CLOSE -> {
                if (--waitTicks <= 0) {
                    ctx.actions().closeContainer();
                    finish(ctx, ctx.rng().rangeInt(2400, 6000));
                }
            }
            case DONE -> {
                // finished() ukončí
            }
        }
    }

    @Override
    public void stop(Bot bot) {
        ctx(bot).actions().closeContainer();
        super.stop(bot);
    }

    @Override
    public boolean finished(Bot bot) {
        return phase == Phase.DONE;
    }

    private void finish(BotContext ctx, int cooldown) {
        cooldownTicks = cooldown;
        phase = Phase.DONE;
    }

    /** Zapamatuje si vesnici (obchodovatelný vesničan = jádro vesnice). */
    private void rememberVillage(BotContext ctx, Bot bot, boolean traded) {
        Optional<TrackedEntity> villager = tracked(ctx);
        if (villager.isEmpty() || ctx.worldView() == null) {
            return;
        }
        Vec3 pos = villager.get().position();
        bot.memory().remember(MemoryKind.VILLAGE, ctx.worldView().worldName(),
                (int) pos.x(), (int) pos.y(), (int) pos.z(), villagerUuid,
                Map.of("traded", String.valueOf(traded)), traded ? 0.8 : 0.5);
    }

    private Optional<TrackedEntity> tracked(BotContext ctx) {
        return villagerUuid == null ? Optional.empty() : ctx.entities().byUuid(villagerUuid);
    }

    private Optional<TrackedEntity> findVillager(BotContext ctx) {
        return ctx.entities().nearest(ctx.position(),
                ctx.config().ai().viewDistanceBlocks(),
                e -> e.type() == EntityType.VILLAGER && e.uuid() != null);
    }
}
