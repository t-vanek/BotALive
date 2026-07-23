package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.WorldView;
import org.bukkit.Material;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;

import java.util.Map;
import java.util.Optional;
import dev.botalive.core.pathfinding.PathGoal;

/**
 * Jízda minecartem.
 *
 * <p>Bot najde vozík na kolejích (nebo ho položí z inventáře na kolej –
 * UseItemOn, jako hráč pravým klikem), nasedne a jede: klientská simulace
 * {@link dev.botalive.core.vehicle.MinecartPhysics} sleduje koleje včetně
 * zatáček, svahů a napájecích kolejí. Jízda končí na konci trati nebo když
 * vozík zastaví (mrtvá trať bez pohonu); bot vysedne a místo si zapamatuje.</p>
 */
public final class MinecartRideGoal extends AbstractGoal {

    private enum Phase { FIND, GO_TO_CART, MOUNT, PLACE, RIDE, DISMOUNT, DONE }

    private Phase phase = Phase.FIND;
    private Integer cartEntityId;
    private int waitTicks;
    private int attempts;
    private int cooldownTicks;

    /** Vytvoří cíl. */
    public MinecartRideGoal() {
        super("minecart");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        if (ctx.worldView() == null || ctx.clientState().dead()) {
            return 0;
        }
        boolean cartNearby = findCartEntity(ctx).isPresent();
        boolean canPlace = hasCartItem(ctx) && nearestRail(ctx, 6) != null;
        if (!cartNearby && !canPlace) {
            return 0;
        }
        double curiosity = bot.personality().trait(Trait.CURIOSITY);
        return 4 + curiosity * 11;
    }

    @Override
    public void start(Bot bot) {
        phase = Phase.FIND;
        cartEntityId = null;
        attempts = 0;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        switch (phase) {
            case FIND -> {
                Optional<TrackedEntity> cart = findCartEntity(ctx);
                if (cart.isPresent()) {
                    cartEntityId = cart.get().entityId();
                    phase = Phase.GO_TO_CART;
                } else if (hasCartItem(ctx)) {
                    phase = Phase.PLACE;
                    waitTicks = 0;
                } else {
                    finish(ctx, 1200);
                }
            }
            case PLACE -> placeCart(ctx);
            case GO_TO_CART -> {
                Optional<TrackedEntity> cart = trackedCart(ctx);
                if (cart.isEmpty()) {
                    phase = Phase.FIND;
                    return;
                }
                Vec3 cartPos = cart.get().position();
                if (cartPos.distanceSquared(ctx.position()) > 2.5 * 2.5) {
                    ctx.navigator().navigateTo(ctx.position(), PathGoal.near(cartPos.toBlockPos(), 1));
                    if (!ctx.navigator().navigating()) {
                        finish(ctx, 1200);
                    }
                    return;
                }
                ctx.navigator().stop();
                ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0), cartPos);
                ctx.actions().interactEntity(cartEntityId);
                waitTicks = 25;
                phase = Phase.MOUNT;
            }
            case MOUNT -> {
                if (ctx.vehicle().mounted()) {
                    Vec3 cartPos = trackedCart(ctx).map(TrackedEntity::position)
                            .orElse(ctx.position());
                    if (ctx.vehicle().startRailRide(ctx.worldView(), cartPos)) {
                        phase = Phase.RIDE;
                    } else {
                        // Vozík mimo koleje – nemá smysl v něm sedět.
                        ctx.vehicle().requestDismount();
                        waitTicks = 40;
                        phase = Phase.DISMOUNT;
                    }
                    return;
                }
                if (--waitTicks <= 0) {
                    if (++attempts >= 3) {
                        finish(ctx, 1800);
                        return;
                    }
                    ctx.actions().interactEntity(cartEntityId);
                    waitTicks = 25;
                }
            }
            case RIDE -> {
                if (!ctx.vehicle().mounted()) {
                    finish(ctx, 2400); // vypadl (rozbitý vozík apod.)
                    return;
                }
                if (ctx.vehicle().railRideFinished()) {
                    ctx.vehicle().stopCruise();
                    ctx.vehicle().requestDismount();
                    waitTicks = 40;
                    phase = Phase.DISMOUNT;
                }
            }
            case DISMOUNT -> {
                if (!ctx.vehicle().mounted()) {
                    rememberRide(ctx, bot);
                    finish(ctx, ctx.rng().rangeInt(3600, 9600));
                    return;
                }
                if (--waitTicks <= 0) {
                    ctx.vehicle().requestDismount();
                    waitTicks = 40;
                }
            }
            case DONE -> {
                // finished() ukončí
            }
        }
    }

    @Override
    public void stop(Bot bot) {
        BotContext ctx = ctx(bot);
        ctx.vehicle().stopCruise();
        if (ctx.vehicle().mounted()) {
            ctx.vehicle().requestDismount();
        }
        super.stop(bot);
    }

    @Override
    public boolean finished(Bot bot) {
        return phase == Phase.DONE;
    }

    // -------------------------------------------------------------- pomocné

    private void finish(BotContext ctx, int cooldown) {
        cooldownTicks = cooldown;
        phase = Phase.DONE;
    }

    /** Položení vozíku z inventáře na kolej (UseItemOn – pravý klik hráče). */
    private void placeCart(BotContext ctx) {
        var snapshot = ctx.serverView().latest();
        if (snapshot == null) {
            finish(ctx, 600);
            return;
        }
        int slot = snapshot.findHotbarSlot(m -> m == Material.MINECART);
        BlockPos rail = nearestRail(ctx, 6);
        if (slot < 0 || rail == null) {
            finish(ctx, 1200);
            return;
        }
        double distSq = rail.center().distanceSquared(ctx.position());
        if (distSq > 3.0 * 3.0) {
            ctx.navigator().navigateTo(ctx.position(), PathGoal.near(rail, 2));
            if (!ctx.navigator().navigating()) {
                finish(ctx, 1200);
            }
            return;
        }
        ctx.navigator().stop();
        if (waitTicks == 0) {
            ctx.actions().selectHotbar(slot);
            ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0), rail.center().add(0, 0.5, 0));
            waitTicks++;
        } else if (waitTicks++ >= 8) {
            ctx.actions().useItemOn(rail, Direction.UP);
            phase = Phase.FIND; // server spawne vozík → najde ho tracker
            waitTicks = 0;
            if (++attempts >= 4) {
                finish(ctx, 1800);
            }
        }
    }

    /** Uloží konec jízdy do paměti navštívených míst. */
    private void rememberRide(BotContext ctx, Bot bot) {
        Vec3 pos = ctx.position();
        if (ctx.worldView() != null) {
            bot.memory().remember(MemoryKind.VISITED_PLACE, ctx.worldView().worldName(),
                    (int) pos.x(), (int) pos.y(), (int) pos.z(), null,
                    Map.of("via", "minecart"), 0.4);
        }
    }

    private Optional<TrackedEntity> trackedCart(BotContext ctx) {
        return cartEntityId == null ? Optional.empty() : ctx.entities().byId(cartEntityId);
    }

    /** Prázdný jezdicí vozík na kolejích v okolí. */
    private Optional<TrackedEntity> findCartEntity(BotContext ctx) {
        WorldView world = ctx.worldView();
        Optional<TrackedEntity> cart = ctx.entities().nearest(ctx.position(), 16,
                e -> e.type() == EntityType.MINECART && onRail(world, e.position()));
        cart.ifPresent(c -> cartEntityId = c.entityId());
        return cart;
    }

    private static boolean onRail(WorldView world, Vec3 pos) {
        if (world == null) {
            return false;
        }
        BlockPos block = pos.toBlockPos();
        return isRail(world.materialAt(block)) || isRail(world.materialAt(block.down()));
    }

    private static boolean isRail(Material material) {
        return dev.botalive.core.inventory.Items.isRail(material);
    }

    private boolean hasCartItem(BotContext ctx) {
        var snapshot = ctx.serverView().latest();
        return snapshot != null && snapshot.hasItem(m -> m == Material.MINECART);
    }

    /** Nejbližší kolej v okolí (pro položení vozíku). */
    private BlockPos nearestRail(BotContext ctx, int radius) {
        WorldView world = ctx.worldView();
        BlockPos feet = ctx.position().toBlockPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = feet.offset(dx, dy, dz);
                    if (isRail(world.materialAt(pos))) {
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
}
