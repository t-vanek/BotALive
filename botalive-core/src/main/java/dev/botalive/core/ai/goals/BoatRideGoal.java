package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.vehicle.Boats;
import dev.botalive.core.world.WorldView;

import java.util.Map;
import java.util.Optional;

/**
 * Plavba lodí – bot použije loď k výpravě po vodní ploše.
 *
 * <p>Postup: najde loď v okolí (nebo ji položí z inventáře na vodu – UseItem
 * s pohledem na hladinu, přesně jako hráč), nasedne (interact paket, server
 * potvrdí přes SetPassengers), vybere si nejdelší volný vodní koridor a pluje
 * ({@link dev.botalive.core.vehicle.VehicleController} – klientská simulace
 * s MoveVehicle pakety). U břehu nebo cíle vysedne (sneak) a místo si
 * zapamatuje. Zvědaví boti se plaví ochotněji.</p>
 */
public final class BoatRideGoal extends AbstractGoal {

    private enum Phase { FIND, GO_TO_BOAT, MOUNT, PLACE, CRUISE, DISMOUNT, DONE }

    private Phase phase = Phase.FIND;
    private Integer boatEntityId;
    private int waitTicks;
    private int attempts;
    private int cooldownTicks;

    /** Vytvoří cíl. */
    public BoatRideGoal() {
        super("boat");
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
        // Vyžaduje vodní plochu poblíž a dostupnou loď (entita nebo item).
        if (!nearOpenWater(ctx)) {
            return 0;
        }
        boolean boatNearby = findBoatEntity(ctx).isPresent();
        boolean boatInInventory = hasBoatItem(ctx);
        if (!boatNearby && !boatInInventory) {
            return 0;
        }
        double curiosity = bot.personality().trait(Trait.CURIOSITY);
        return 4 + curiosity * 12;
    }

    @Override
    public void start(Bot bot) {
        phase = Phase.FIND;
        boatEntityId = null;
        attempts = 0;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        switch (phase) {
            case FIND -> {
                Optional<TrackedEntity> boat = findBoatEntity(ctx);
                if (boat.isPresent()) {
                    boatEntityId = boat.get().entityId();
                    phase = Phase.GO_TO_BOAT;
                } else if (hasBoatItem(ctx)) {
                    phase = Phase.PLACE;
                    waitTicks = 0;
                } else {
                    finish(ctx, 1200);
                }
            }
            case PLACE -> placeBoat(ctx);
            case GO_TO_BOAT -> {
                Optional<TrackedEntity> boat = trackedBoat(ctx);
                if (boat.isEmpty()) {
                    phase = Phase.FIND;
                    return;
                }
                Vec3 boatPos = boat.get().position();
                if (boatPos.distanceSquared(ctx.position()) > 2.5 * 2.5) {
                    ctx.navigator().navigateTo(ctx.position(), boatPos.toBlockPos());
                    if (!ctx.navigator().navigating()) {
                        finish(ctx, 1200); // loď nedosažitelná (uprostřed jezera)
                    }
                    return;
                }
                ctx.navigator().stop();
                ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0), boatPos);
                ctx.actions().interactEntity(boatEntityId);
                waitTicks = 25;
                phase = Phase.MOUNT;
            }
            case MOUNT -> {
                if (ctx.vehicle().mounted()) {
                    startCruise(ctx, bot);
                    return;
                }
                if (--waitTicks <= 0) {
                    if (++attempts >= 3) {
                        finish(ctx, 1800);
                        return;
                    }
                    ctx.actions().interactEntity(boatEntityId);
                    waitTicks = 25;
                }
            }
            case CRUISE -> {
                if (!ctx.vehicle().mounted()) {
                    finish(ctx, 2400); // vypadl z lodi
                    return;
                }
                if (ctx.vehicle().arrived() || ctx.vehicle().aground()
                        || !ctx.vehicle().waterAhead(5)) {
                    ctx.vehicle().stopCruise();
                    ctx.vehicle().requestDismount();
                    waitTicks = 40;
                    phase = Phase.DISMOUNT;
                }
            }
            case DISMOUNT -> {
                if (!ctx.vehicle().mounted()) {
                    rememberVoyage(ctx, bot);
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

    /** Položení lodi z inventáře na hladinu (UseItem s pohledem na vodu). */
    private void placeBoat(BotContext ctx) {
        var snapshot = ctx.serverView().latest();
        if (snapshot == null) {
            finish(ctx, 600);
            return;
        }
        BlockPos water = Boats.nearestWater(ctx.worldView(), ctx.position().toBlockPos(), 4);
        if (water == null || !snapshot.hasItem(Boats::isBoatItem)) {
            finish(ctx, 1200);
            return;
        }
        if (waitTicks == 0) {
            // Vzít loď do ruky – i z batohu (equipMatching ji přitáhne do hotbaru).
            if (!ctx.inventory().equipMatching(snapshot, Boats::isBoatItem)) {
                finish(ctx, 1200);
                return;
            }
            ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0), water.center().add(0, 0.9, 0));
            waitTicks++;
        } else if (waitTicks++ >= 8) {
            // Pohled ustálený na hladině → položit loď.
            ctx.actions().useItem(ctx.humanizer().yaw(), ctx.humanizer().pitch());
            phase = Phase.FIND; // server spawne loď → najde ji tracker
            waitTicks = 0;
            if (++attempts >= 4) {
                finish(ctx, 1800);
            }
        }
    }

    /** Zahájí plavbu: vybere nejdelší vodní koridor a spustí simulaci. */
    private void startCruise(BotContext ctx, Bot bot) {
        Optional<TrackedEntity> boat = trackedBoat(ctx);
        Vec3 boatPos = boat.map(TrackedEntity::position).orElse(ctx.position());
        float boatYaw = boat.map(TrackedEntity::yaw).orElse(ctx.humanizer().yaw());

        Vec3 target = pickWaterTarget(ctx, boatPos);
        if (target == null) {
            ctx.vehicle().requestDismount();
            waitTicks = 40;
            phase = Phase.DISMOUNT;
            return;
        }
        ctx.vehicle().startCruise(ctx.worldView(), boatPos, boatYaw, target);
        phase = Phase.CRUISE;
    }

    /** Nejdelší volný vodní paprsek z pozice lodi (vzorkuje 12 směrů). */
    private Vec3 pickWaterTarget(BotContext ctx, Vec3 from) {
        WorldView world = ctx.worldView();
        int waterY = (int) Math.floor(from.y());
        double bestDistance = 0;
        Vec3 best = null;
        for (int i = 0; i < 12; i++) {
            double angle = ctx.rng().range(0, Math.PI * 2);
            double dx = Math.cos(angle);
            double dz = Math.sin(angle);
            double distance = 0;
            for (double step = 4; step <= 96; step += 4) {
                BlockPos probe = new BlockPos((int) (from.x() + dx * step), waterY,
                        (int) (from.z() + dz * step));
                var traits = world.traitsAt(probe);
                var below = world.traitsAt(probe.down());
                if ((traits.liquid() && !traits.hazard())
                        || (below.liquid() && !below.hazard())) {
                    distance = step;
                } else {
                    break;
                }
            }
            if (distance > bestDistance) {
                bestDistance = distance;
                best = from.add(dx * distance * 0.8, 0, dz * distance * 0.8);
            }
        }
        return bestDistance >= 12 ? best : null; // krátké louže nestojí za plavbu
    }

    /** Uloží cíl plavby do paměti navštívených míst. */
    private void rememberVoyage(BotContext ctx, Bot bot) {
        Vec3 pos = ctx.position();
        if (ctx.worldView() != null) {
            bot.memory().remember(MemoryKind.VISITED_PLACE, ctx.worldView().worldName(),
                    (int) pos.x(), (int) pos.y(), (int) pos.z(), null,
                    Map.of("via", "boat"), 0.4);
        }
    }

    private Optional<TrackedEntity> trackedBoat(BotContext ctx) {
        return boatEntityId == null ? Optional.empty() : ctx.entities().byId(boatEntityId);
    }

    private Optional<TrackedEntity> findBoatEntity(BotContext ctx) {
        Optional<TrackedEntity> boat = ctx.entities().nearest(ctx.position(), 16,
                e -> Boats.isBoatType(e.type().name()));
        boat.ifPresent(b -> boatEntityId = b.entityId());
        return boat;
    }

    private boolean hasBoatItem(BotContext ctx) {
        var snapshot = ctx.serverView().latest();
        return snapshot != null && snapshot.hasItem(Boats::isBoatItem);
    }

    /** Otevřená voda poblíž: aspoň ~třetina vzorků v okolí je hladina. */
    private boolean nearOpenWater(BotContext ctx) {
        WorldView world = ctx.worldView();
        BlockPos feet = ctx.position().toBlockPos();
        int water = 0;
        int samples = 0;
        for (int dx = -6; dx <= 6; dx += 2) {
            for (int dz = -6; dz <= 6; dz += 2) {
                samples++;
                var traits = world.traitsAt(feet.offset(dx, -1, dz));
                if (traits.liquid() && !traits.hazard()) {
                    water++;
                }
            }
        }
        return water >= samples / 3;
    }
}
