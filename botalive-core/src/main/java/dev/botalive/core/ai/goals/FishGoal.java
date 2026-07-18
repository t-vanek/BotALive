package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.WorldView;
import org.bukkit.Material;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;

import java.util.Optional;

/**
 * Rybaření – vanilla mechanika prutu a splávku.
 *
 * <p>Bot s prutem si stoupne k vodě, nahodí (UseItem → server spawne splávek),
 * sleduje vlastní splávek přes tracker entit a záběr pozná stejně jako hráč –
 * splávek sebou škubne dolů (odhad rychlosti z move paketů). Zasekne
 * s lidskou reakční prodlevou (UseItem), ryba přiletí a sebere se dotykem.
 * Po několika nahozeních session končí s cooldownem podle trpělivosti.</p>
 */
public final class FishGoal extends AbstractGoal {

    /** Škubnutí splávku při záběru (bloky/tick, směr dolů). */
    private static final double BITE_DIP_VELOCITY = -0.05;

    /** Kolik ticků po nahození ignorovat pohyb splávku (dopad, usazení). */
    private static final int SETTLE_TICKS = 50;

    /** Timeout jednoho nahození (žádný záběr). */
    private static final int CAST_TIMEOUT_TICKS = 1200;

    private enum Phase { FIND_SPOT, GO, CAST, WAIT_BITE, REEL, DONE }

    private Phase phase = Phase.FIND_SPOT;
    private BlockPos spot;
    private BlockPos water;
    private Integer bobberId;
    private int phaseTicks;
    private int reactionTicks;
    private int casts;
    private int cooldownTicks;

    /** Vytvoří cíl. */
    public FishGoal() {
        super("fish");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        // V Netheru není voda – prut zůstává v batohu.
        if (outsideOverworld(ctx)) {
            return 0;
        }
        if (ctx.worldView() == null || ctx.clientState().dead()) {
            return 0;
        }
        var snapshot = ctx.serverView().latest();
        if (snapshot == null || snapshot.findHotbarSlot(m -> m == Material.FISHING_ROD) < 0) {
            return 0;
        }
        if (nearestOpenWater(ctx) == null) {
            return 0;
        }
        double patience = bot.personality().trait(Trait.PATIENCE);
        double hungerPressure = Math.max(0, 15 - ctx.clientState().food());
        double utility = 4 + patience * 10 + hungerPressure;
        // Rybář ví, že v dešti ryby berou (rychlejší záběry) – ale v bouřce
        // se u vody s prutem nestojí.
        if (ctx.raining() && !ctx.thundering()) {
            utility *= 1.35;
        }
        return utility;
    }

    @Override
    public void start(Bot bot) {
        phase = Phase.FIND_SPOT;
        casts = 0;
        bobberId = null;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        phaseTicks++;
        switch (phase) {
            case FIND_SPOT -> {
                water = nearestOpenWater(ctx);
                if (water == null) {
                    finish(ctx, bot, 1200);
                    return;
                }
                spot = findStandableNear(ctx, water);
                if (spot == null) {
                    finish(ctx, bot, 1200);
                    return;
                }
                setPhase(Phase.GO);
            }
            case GO -> {
                if (spot.center().distanceSquared(ctx.position()) > 2.0 * 2.0) {
                    ctx.navigator().navigateTo(ctx.position(), spot);
                    if (!ctx.navigator().navigating()) {
                        finish(ctx, bot, 1200);
                    }
                    return;
                }
                ctx.navigator().stop();
                setPhase(Phase.CAST);
            }
            case CAST -> {
                var snapshot = ctx.serverView().latest();
                int slot = snapshot == null ? -1
                        : snapshot.findHotbarSlot(m -> m == Material.FISHING_ROD);
                if (slot < 0) {
                    finish(ctx, bot, 1200);
                    return;
                }
                ctx.actions().selectHotbar(slot);
                // Zamířit kus za vodní blok, ať splávek dopadne do vody.
                ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0),
                        water.center().add(0, 0.9, 0));
                if (phaseTicks >= 10) {
                    ctx.actions().useItem(ctx.humanizer().yaw(), ctx.humanizer().pitch());
                    bobberId = null;
                    casts++;
                    setPhase(Phase.WAIT_BITE);
                }
            }
            case WAIT_BITE -> {
                // Najít vlastní splávek (spawne se těsně po nahození).
                if (bobberId == null) {
                    Optional<TrackedEntity> bobber = ctx.entities().nearest(ctx.position(), 24,
                            e -> e.type() == EntityType.FISHING_BOBBER);
                    bobber.ifPresent(b -> bobberId = b.entityId());
                    if (bobberId == null && phaseTicks > 40) {
                        setPhase(Phase.CAST); // nahození selhalo (např. do bloku)
                    }
                    return;
                }
                Optional<TrackedEntity> bobber = ctx.entities().byId(bobberId);
                if (bobber.isEmpty()) {
                    setPhase(Phase.CAST); // splávek zmizel
                    return;
                }
                // Záběr: po usazení splávek prudce cukne dolů.
                if (phaseTicks > SETTLE_TICKS
                        && bobber.get().velocityEstimate().y() < BITE_DIP_VELOCITY) {
                    reactionTicks = (int) (ctx.humanizer().reactionDelayMs(250, 120) / 50);
                    setPhase(Phase.REEL);
                    return;
                }
                if (phaseTicks > CAST_TIMEOUT_TICKS) {
                    ctx.actions().useItem(ctx.humanizer().yaw(), ctx.humanizer().pitch()); // smotat
                    setPhase(casts >= maxCasts(bot) ? Phase.DONE : Phase.CAST);
                }
            }
            case REEL -> {
                if (--reactionTicks <= 0) {
                    ctx.actions().useItem(ctx.humanizer().yaw(), ctx.humanizer().pitch()); // zásek
                    setPhase(casts >= maxCasts(bot) ? Phase.DONE : Phase.CAST);
                }
            }
            case DONE -> finish(ctx, bot, 0);
        }
    }

    @Override
    public void stop(Bot bot) {
        // Rozdělané nahození smotat, ať bot neodchází s nataženým vlascem.
        BotContext ctx = ctx(bot);
        if (phase == Phase.WAIT_BITE && bobberId != null) {
            ctx.actions().useItem(ctx.humanizer().yaw(), ctx.humanizer().pitch());
        }
        super.stop(bot);
    }

    @Override
    public boolean finished(Bot bot) {
        return phase == Phase.DONE && cooldownTicks > 0;
    }

    private void setPhase(Phase next) {
        phase = next;
        phaseTicks = 0;
    }

    private void finish(BotContext ctx, Bot bot, int minCooldown) {
        double patience = bot.personality().trait(Trait.PATIENCE);
        cooldownTicks = Math.max(minCooldown,
                ctx.rng().rangeInt(600, 1800 + (int) ((1 - patience) * 1800)));
        phase = Phase.DONE;
    }

    /** Počet nahození za session – trpěliví rybáři vydrží déle. */
    private int maxCasts(Bot bot) {
        return 2 + (int) (bot.personality().trait(Trait.PATIENCE) * 4);
    }

    /** Vodní blok s volnou hladinou v okolí. */
    private BlockPos nearestOpenWater(BotContext ctx) {
        WorldView world = ctx.worldView();
        BlockPos feet = ctx.position().toBlockPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -8; dx <= 8; dx++) {
            for (int dy = -3; dy <= 1; dy++) {
                for (int dz = -8; dz <= 8; dz++) {
                    BlockPos pos = feet.offset(dx, dy, dz);
                    var traits = world.traitsAt(pos);
                    if (traits.liquid() && !traits.hazard()
                            && world.traitsAt(pos.up()).passable()) {
                        double dist = pos.distanceSquared(feet);
                        if (dist < bestDist) {
                            bestDist = dist;
                            best = pos;
                        }
                    }
                }
            }
        }
        return best;
    }

    /** Pochozí místo na břehu poblíž vody. */
    private BlockPos findStandableNear(BotContext ctx, BlockPos waterPos) {
        WorldView world = ctx.worldView();
        for (int radius = 1; radius <= 4; radius++) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    for (int dy = -1; dy <= 2; dy++) {
                        BlockPos feet = waterPos.offset(dx, dy, dz);
                        if (world.traitsAt(feet).passable()
                                && world.traitsAt(feet.up()).passable()
                                && world.traitsAt(feet.down()).solid()) {
                            return feet;
                        }
                    }
                }
            }
        }
        return null;
    }

    @Override
    public String explain(dev.botalive.api.bot.Bot bot) {
        return "rybařím, snad něco zabere";
    }
}
