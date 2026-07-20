package dev.botalive.core.tasks;

import dev.botalive.core.ai.BotContext;
import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.inventory.InventoryHelper;
import dev.botalive.core.physics.EdgeGuard;
import dev.botalive.core.physics.MoveInput;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import org.bukkit.Material;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;

import java.util.Optional;

/**
 * Překonání lávového oceánu na osedlaném striderovi směrem k cíli navigace.
 *
 * <p>Lávová analogie {@link WaterCrossTask}: když navigace míří přes souvislou
 * lávu širší než {@link dev.botalive.core.vehicle.Striders#MIN_CROSS_WIDTH}
 * (mostit se nevyplatí, obejít není kudy) a poblíž se brouzdá strider, bot ho
 * jako hráč osedlá (pravý klik se sedlem – server sedlo spotřebuje), nasedne
 * s houbou na prutu v ruce a přejede na druhý břeh
 * ({@link dev.botalive.core.vehicle.VehicleController} – klientská simulace
 * {@link dev.botalive.core.vehicle.StriderPhysics} s MoveVehicle pakety).
 * Na břehu vysedne (sneak) a navigace si cestu přepočítá.</p>
 *
 * <p>Sedlo se ověřuje úbytkem v inventáři (metadata osedlání se nečtou) –
 * a strider osedlaný z dřívějšího nedotaženého pokusu se pozná tak, že se
 * na něj po pár marných sedláních prostě zkusí nasednout. Bez houby na prutu
 * task nezačíná: neřiditelný strider je loterie. Přiblížení ke břehu hlídá
 * {@link EdgeGuard} – bot kvůli nasedání nevkročí do lávy.</p>
 */
public final class LavaCrossTask implements VehicleTask {

    /** Tvrdý časový strop celé jízdy (ticky) – pojistka proti zacyklení. */
    private static final int BUDGET_TICKS = 2000;
    /** Na jak blízko ke striderovi dojít, než na něj bot klikne (bloky). */
    private static final double BOARD_DISTANCE = 3.0;
    /** Kolik ticků čekat na potvrzení osedlání/nasednutí, než klik zopakovat. */
    private static final int MOUNT_WAIT = 25;
    /** Kolik ticků čekat mezi pokusy o vysednutí. */
    private static final int DISMOUNT_WAIT = 40;
    /** Kolik ticků tlačit bota od lávy na břeh, než se přejezd uzavře. */
    private static final int STEP_OFF_TICKS = 40;
    /** Nejvýš tolik restartů jízdy přes falešné uváznutí (výpadky WorldView). */
    private static final int MAX_CRUISE_RESTARTS = 10;

    private enum Phase { PLAN, APPROACH, SADDLE, MOUNT, CRUISE, DISMOUNT, STEP_OFF, DONE }

    private final BlockPos destination;

    private Phase phase = Phase.PLAN;
    private MoveInput move = MoveInput.IDLE;
    private Integer striderEntityId;
    private int waitTicks;
    private int attempts;
    private int ticks;
    private int cruiseRestarts;
    private int saddleBaseline = -1;
    private Vec3 lastStuckPos;
    private boolean succeeded;

    /**
     * @param destination cíl navigace (kurz jízdy míří k němu; Y se ignoruje)
     */
    public LavaCrossTask(BlockPos destination) {
        this.destination = destination;
    }

    /** @return {@code true} pokud bot lávu přejel a vysedl na břehu */
    public boolean succeeded() {
        return succeeded;
    }

    @Override
    public MoveInput move() {
        return move;
    }

    @Override
    public boolean tick(BotContext ctx) {
        move = MoveInput.IDLE;
        if (ctx.worldView() == null || destination == null || ++ticks > BUDGET_TICKS) {
            return abort(ctx);
        }
        switch (phase) {
            case PLAN -> plan(ctx);
            case APPROACH -> approach(ctx);
            case SADDLE -> saddle(ctx);
            case MOUNT -> mount(ctx);
            case CRUISE -> cruise(ctx);
            case DISMOUNT -> dismount(ctx);
            case STEP_OFF -> stepOff(ctx);
            case DONE -> {
                return true;
            }
        }
        return phase == Phase.DONE;
    }

    /** Najít stridera poblíž; bez něj (nebo bez houby na prutu) task končí. */
    private void plan(BotContext ctx) {
        var snapshot = ctx.serverView().latest();
        if (snapshot == null
                || !snapshot.hasItem(dev.botalive.core.vehicle.Striders::isSteeringRod)) {
            phase = Phase.DONE; // bez řízení se na stridera neleze
            return;
        }
        Optional<TrackedEntity> strider = findStrider(ctx);
        if (strider.isEmpty()) {
            phase = Phase.DONE; // strider poblíž není – navigace to vyřeší jinak
            return;
        }
        striderEntityId = strider.get().entityId();
        saddleBaseline = InventoryHelper.countItem(snapshot, Material.SADDLE);
        phase = Phase.APPROACH;
    }

    /**
     * Dojít na dosah stridera – po břehu, nikdy do lávy ({@link EdgeGuard}).
     * Strider se brouzdá; když odejde z dosahu trackeru, task to vzdá.
     */
    private void approach(BotContext ctx) {
        Optional<TrackedEntity> strider = trackedStrider(ctx);
        if (strider.isEmpty()) {
            phase = Phase.DONE;
            return;
        }
        Vec3 striderPos = strider.get().position();
        Vec3 delta = striderPos.sub(ctx.position());
        if (delta.horizontalLength() > BOARD_DISTANCE) {
            MoveInput raw = new MoveInput(delta.horizontal().normalized(), false, false, false);
            move = EdgeGuard.apply(ctx.worldView(), ctx.position(), raw);
            return;
        }
        ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0), striderPos);
        waitTicks = MOUNT_WAIT;
        phase = saddleBaseline > 0 ? Phase.SADDLE : Phase.MOUNT;
        if (phase == Phase.MOUNT) {
            mountClick(ctx);
        }
    }

    /**
     * Osedlání: pravý klik se sedlem v ruce, server sedlo spotřebuje –
     * úbytek v inventáři je potvrzení. Po dvou marných pokusech se zkusí
     * rovnou nasednout (strider může být osedlaný z dřívějška).
     */
    private void saddle(BotContext ctx) {
        Optional<TrackedEntity> strider = trackedStrider(ctx);
        if (strider.isEmpty()) {
            phase = Phase.DONE;
            return;
        }
        var snapshot = ctx.serverView().latest();
        if (snapshot != null
                && InventoryHelper.countItem(snapshot, Material.SADDLE) < saddleBaseline) {
            mountClick(ctx); // sedlo zmizelo z inventáře → sedí na hřbetě
            phase = Phase.MOUNT;
            return;
        }
        if (--waitTicks <= 0) {
            if (++attempts > 2) {
                attempts = 0;
                mountClick(ctx); // možná už osedlaný – zkusit nasednout
                phase = Phase.MOUNT;
                return;
            }
            if (snapshot == null
                    || !ctx.inventory().equipItem(snapshot, Material.SADDLE)) {
                waitTicks = 8; // sedlo se teprve přitahuje do hotbaru
                return;
            }
            ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0),
                    strider.get().position());
            ctx.actions().interactEntity(striderEntityId);
            waitTicks = MOUNT_WAIT;
        }
    }

    /** Klik pro nasednutí – s houbou na prutu v ruce (řízení). */
    private void mountClick(BotContext ctx) {
        var snapshot = ctx.serverView().latest();
        if (snapshot != null) {
            ctx.inventory().equipMatching(snapshot,
                    dev.botalive.core.vehicle.Striders::isSteeringRod);
        }
        ctx.actions().interactEntity(striderEntityId);
        waitTicks = MOUNT_WAIT;
    }

    /** Počkat na potvrzení nasednutí serverem a rozjet se k cíli. */
    private void mount(BotContext ctx) {
        if (ctx.vehicle().mounted()) {
            Optional<TrackedEntity> strider = trackedStrider(ctx);
            Vec3 striderPos = strider.map(TrackedEntity::position).orElse(ctx.position());
            // Rozjet se rovnou čelem k cíli – ne ve spawn-natočení.
            Vec3 toDest = destination.center().sub(striderPos).horizontal();
            float yaw = toDest.horizontalLength() < 1.0E-4
                    ? strider.map(TrackedEntity::yaw).orElse(ctx.humanizer().yaw())
                    : (float) Math.toDegrees(Math.atan2(-toDest.x(), toDest.z()));
            ctx.vehicle().startLavaRide(ctx.worldView(), striderPos, yaw,
                    destination.center());
            if (ctx.rng().chance(0.6)) {
                ctx.chat().sayFrom(dev.botalive.core.chat.PhraseCategory.STRIDER_RIDE, null);
            }
            phase = Phase.CRUISE;
            return;
        }
        if (--waitTicks <= 0) {
            if (++attempts >= 3) {
                phase = Phase.DONE; // nedaří se nasednout – vzdát
                return;
            }
            mountClick(ctx);
        }
    }

    /**
     * Jízda k cíli; u břehu nebo u cíle vysednout. Falešné uváznutí (klientský
     * WorldView na hranici chunku ještě nemá lávu) se řeší restartem kurzu –
     * skutečná zeď se pozná tím, že se strider nepohnul z místa.
     */
    private void cruise(BotContext ctx) {
        if (!ctx.vehicle().mounted()) {
            phase = Phase.DONE; // shozen (strider zabit, server vysadil)
            return;
        }
        if (ctx.vehicle().arrived() || ctx.vehicle().ashore()) {
            endCruise(ctx);
            return;
        }
        if (!ctx.vehicle().striderStuck()) {
            return;
        }
        Vec3 pos = ctx.vehicle().vehiclePosition();
        boolean sameSpot = pos != null && lastStuckPos != null
                && pos.distanceSquared(lastStuckPos) < 1.0;
        lastStuckPos = pos;
        if (pos == null || sameSpot || ++cruiseRestarts > MAX_CRUISE_RESTARTS) {
            endCruise(ctx);
            return;
        }
        // Nejspíš jen výpadek WorldView – rozjet jízdu znovu z aktuální pozice.
        Vec3 toDest = destination.center().sub(pos).horizontal();
        float yaw = toDest.horizontalLength() < 1.0E-4 ? 0
                : (float) Math.toDegrees(Math.atan2(-toDest.x(), toDest.z()));
        ctx.vehicle().startLavaRide(ctx.worldView(), pos, yaw, destination.center());
    }

    /** Ukončí jízdu a požádá o vysednutí. */
    private void endCruise(BotContext ctx) {
        ctx.vehicle().stopCruise();
        ctx.vehicle().requestDismount();
        waitTicks = DISMOUNT_WAIT;
        phase = Phase.DISMOUNT;
    }

    /** Držet sneak, dokud server nevysadí; pak odejít od lávy na břeh. */
    private void dismount(BotContext ctx) {
        if (!ctx.vehicle().mounted()) {
            waitTicks = STEP_OFF_TICKS;
            phase = Phase.STEP_OFF;
            return;
        }
        ctx.vehicle().requestDismount(); // drž sneak každý tick, dokud nevysadí
    }

    /**
     * Odejít od stridera na pevninu ve směru cíle. Strider se nechává žít –
     * je znovupoužitelný pro zpáteční cestu (a zabíjet vlastního oře se
     * nedělá). Krok od lávy hlídá {@link EdgeGuard}.
     */
    private void stepOff(BotContext ctx) {
        BlockPos feet = ctx.position().toBlockPos();
        boolean onLand = ctx.onGround()
                && ctx.worldView().traitsAt(feet.down()).solid()
                && !ctx.worldView().traitsAt(feet).liquid();
        if (onLand || --waitTicks <= 0) {
            succeeded = onLand;
            phase = Phase.DONE; // na břehu – navigace odsud přepočítá cestu
            return;
        }
        Vec3 dir = destination.center().sub(ctx.position()).horizontal().normalized();
        MoveInput raw = new MoveInput(dir, false, true, false); // skok přes hranu
        move = EdgeGuard.apply(ctx.worldView(), ctx.position(), raw);
    }

    /** Ukončí task neúspěchem a uklidí případnou rozjetou jízdu. */
    private boolean abort(BotContext ctx) {
        cancel(ctx);
        return true;
    }

    private Optional<TrackedEntity> findStrider(BotContext ctx) {
        return ctx.entities().nearest(ctx.position(), 16,
                e -> e.type() == EntityType.STRIDER);
    }

    private Optional<TrackedEntity> trackedStrider(BotContext ctx) {
        return striderEntityId == null ? Optional.empty()
                : ctx.entities().byId(striderEntityId);
    }

    @Override
    public void cancel(BotContext ctx) {
        if (ctx.vehicle().cruising()) {
            ctx.vehicle().stopCruise();
        }
        if (ctx.vehicle().mounted()) {
            ctx.vehicle().requestDismount();
        }
        phase = Phase.DONE;
    }
}
