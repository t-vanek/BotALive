package dev.botalive.core.tasks;

import dev.botalive.core.ai.BotContext;
import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.physics.MoveInput;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.vehicle.Boats;

import java.util.Optional;

/**
 * Překonání široké vody lodí směrem k cíli navigace.
 *
 * <p>Když navigace míří přes souvislou vodní plochu širší než pár bloků a bot
 * má loď (v inventáři nebo poblíž na hladině), vyplatí se místo pomalého
 * plavání nasednout a přeplout. Task je vodní analogií {@link BridgeTask}:
 * spustí ho {@code BotImpl}, když před botem leží splavná voda ve směru cíle.</p>
 *
 * <p>Postup jako u hráče: (volitelně) položit loď na hladinu, dojít k ní,
 * nasednout (interact – server potvrdí přes SetPassengers), plout k cíli
 * ({@link dev.botalive.core.vehicle.VehicleController} – klientská simulace
 * s MoveVehicle pakety) a u břehu vysednout (sneak). Řízení vozidla vyžaduje
 * tickování i po nasednutí – proto {@link VehicleTask}.</p>
 *
 * <p>Task končí úspěchem po vysednutí na druhém břehu (navigace si odsud cestu
 * přepočítá), neúspěchem když loď chybí, nejde nasednout nebo vyprší rozpočet.</p>
 */
public final class WaterCrossTask implements VehicleTask {

    /** Tvrdý časový strop celé plavby (ticky) – pojistka proti zacyklení. */
    private static final int BUDGET_TICKS = 1600;
    /** Na jak blízko k lodi dojít, než na ni bot klikne (bloky). */
    private static final double BOARD_DISTANCE = 2.5;
    /** Kolik ticků čekat na potvrzení nasednutí, než klik zopakovat. */
    private static final int MOUNT_WAIT = 25;
    /** Kolik ticků čekat mezi pokusy o vysednutí. */
    private static final int DISMOUNT_WAIT = 40;
    /** Kolik ticků čekat, než se položená loď objeví v trackeru entit. */
    private static final int SPAWN_WAIT = 30;
    /** Kolik ticků tlačit bota od lodi na břeh, než se přejezd uzavře. */
    private static final int STEP_OFF_TICKS = 40;
    /** Jak daleko před bota (ve směru cíle) mířit při pokládce lodi. */
    private static final double AIM_AHEAD = 3.5;
    /** Nejvýš tolik „nadhozů" plavby přes falešný aground (výpadky WorldView). */
    private static final int MAX_CRUISE_RESTARTS = 10;

    private enum Phase { PLAN, PLACE, WAIT_BOAT, APPROACH, MOUNT, CRUISE, DISMOUNT, STEP_OFF, DONE }

    private final BlockPos destination;

    private Phase phase = Phase.PLAN;
    private MoveInput move = MoveInput.IDLE;
    private Integer boatEntityId;
    private int waitTicks;
    private int attempts;
    private int ticks;
    private int cruiseRestarts;
    private Vec3 lastAgroundPos;
    private boolean succeeded;

    /**
     * @param destination cíl navigace (kurz plavby míří k němu; Y se ignoruje)
     */
    public WaterCrossTask(BlockPos destination) {
        this.destination = destination;
    }

    /** @return {@code true} pokud bot vodu lodí přeplul a vysedl na břehu */
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
            case PLACE -> placeBoat(ctx);
            case WAIT_BOAT -> waitBoat(ctx);
            case APPROACH -> approach(ctx);
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

    /** Najít loď poblíž, nebo ji položit z inventáře; bez lodi task končí. */
    private void plan(BotContext ctx) {
        Optional<TrackedEntity> boat = findBoat(ctx);
        if (boat.isPresent()) {
            boatEntityId = boat.get().entityId();
            phase = Phase.APPROACH;
            return;
        }
        if (hasBoatItem(ctx)) {
            waitTicks = 0;
            phase = Phase.PLACE;
            return;
        }
        phase = Phase.DONE; // loď není – navigace vodu přeplave sama
    }

    /**
     * Položení lodi z inventáře na hladinu (UseItem s pohledem na vodu).
     *
     * <p>Míří se ~{@link #AIM_AHEAD} bloku dopředu ve směru cíle na úroveň
     * hladiny. Mírný úhel přeletí přilehlý břeh s rezervou a loď dopadne
     * dovnitř vodní plochy (ne na jeho hranu, kde by spadla na souš).</p>
     */
    private void placeBoat(BotContext ctx) {
        var snapshot = ctx.serverView().latest();
        if (snapshot == null) {
            abort(ctx);
            return;
        }
        BlockPos water = Boats.nearestWater(ctx.worldView(), ctx.position().toBlockPos(), 5);
        if (water == null || !snapshot.hasItem(Boats::isBoatItem)) {
            phase = Phase.DONE; // poblíž není hladina, nebo loď došla
            return;
        }
        // Vzít loď do ruky – i z batohu (equipMatching ji přitáhne do hotbaru).
        if (waitTicks == 0 && !ctx.inventory().equipMatching(snapshot, Boats::isBoatItem)) {
            phase = Phase.DONE;
            return;
        }
        // Cíl pohledu: kousek dovnitř vodní plochy ve směru cesty, na hladině.
        Vec3 dir = destination.center().sub(ctx.position()).horizontal().normalized();
        Vec3 aim = new Vec3(ctx.position().x() + dir.x() * AIM_AHEAD,
                water.y() + 0.9, ctx.position().z() + dir.z() * AIM_AHEAD);
        ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0), aim);
        if (waitTicks++ >= 8) {
            ctx.actions().useItem(ctx.humanizer().yaw(), ctx.humanizer().pitch());
            waitTicks = 0;
            attempts++;
            phase = Phase.WAIT_BOAT; // dát serveru čas loď spawnout a trackeru ji zaznamenat
        }
    }

    /** Počkat, až se položená loď objeví v trackeru; pak k ní vyrazit. */
    private void waitBoat(BotContext ctx) {
        Optional<TrackedEntity> boat = findBoat(ctx);
        if (boat.isPresent()) {
            boatEntityId = boat.get().entityId();
            phase = Phase.APPROACH;
            return;
        }
        if (++waitTicks > SPAWN_WAIT) {
            waitTicks = 0;
            phase = attempts >= 4 ? Phase.DONE : Phase.PLACE; // loď se nespawnla – zkusit znovu
        }
    }

    /** Dojít (doplavat) k lodi a kliknout na ni – nasednout. */
    private void approach(BotContext ctx) {
        Optional<TrackedEntity> boat = trackedBoat(ctx);
        if (boat.isEmpty()) {
            phase = Phase.PLAN; // loď zmizela (rozbila se, odplula) – zkusit znovu
            return;
        }
        Vec3 boatPos = boat.get().position();
        Vec3 delta = boatPos.sub(ctx.position());
        if (delta.horizontalLength() > BOARD_DISTANCE) {
            move = new MoveInput(delta.horizontal().normalized(), false, false, false);
            return;
        }
        ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0), boatPos);
        ctx.actions().interactEntity(boatEntityId);
        waitTicks = MOUNT_WAIT;
        phase = Phase.MOUNT;
    }

    /** Počkat na potvrzení nasednutí serverem a spustit plavbu k cíli. */
    private void mount(BotContext ctx) {
        if (ctx.vehicle().mounted()) {
            Optional<TrackedEntity> boat = trackedBoat(ctx);
            Vec3 boatPos = boat.map(TrackedEntity::position).orElse(ctx.position());
            // Rozjet loď rovnou přídí k cíli – ne ve spawn-natočení. Pomalé
            // otáčení (2,5°/tick) by ji jinak stihlo zanést do břehu (aground).
            Vec3 toDest = destination.center().sub(boatPos).horizontal();
            float boatYaw = toDest.horizontalLength() < 1.0E-4
                    ? boat.map(TrackedEntity::yaw).orElse(ctx.humanizer().yaw())
                    : (float) Math.toDegrees(Math.atan2(-toDest.x(), toDest.z()));
            ctx.vehicle().startCruise(ctx.worldView(), boatPos, boatYaw, destination.center());
            phase = Phase.CRUISE;
            return;
        }
        if (--waitTicks <= 0) {
            if (++attempts >= 3) {
                phase = Phase.DONE; // nedaří se nasednout – vzdát
                return;
            }
            ctx.actions().interactEntity(boatEntityId);
            waitTicks = MOUNT_WAIT;
        }
    }

    /**
     * Plavba k cíli; u břehu (loď najela na mělčinu) nebo u cíle vysednout.
     *
     * <p>Klientský {@code WorldView} bota občas na hranici chunku ještě nemá
     * vodu před přídí (rychlá loď ji předběhne) a simulace to vyhodnotí jako
     * mělčinu. Než vysednout, ověříme: pokud je před lodí <b>opravdu</b> voda
     * (WorldView už dohnal), plavbu jen znovu rozjedeme; skutečný břeh se pozná
     * tím, že loď nedokáže popojet (aground na stejném místě) nebo dojdou nadhozy.</p>
     */
    private void cruise(BotContext ctx) {
        if (!ctx.vehicle().mounted()) {
            phase = Phase.DONE; // vypadl z lodi (rozbitá, shozený jezdec)
            return;
        }
        if (ctx.vehicle().arrived()) {
            endCruise(ctx);
            return;
        }
        if (!ctx.vehicle().aground()) {
            return;
        }
        Vec3 pos = ctx.vehicle().vehiclePosition();
        boolean sameSpot = pos != null && lastAgroundPos != null
                && pos.distanceSquared(lastAgroundPos) < 1.0;
        lastAgroundPos = pos;
        // Skutečný břeh (nepohnula se) nebo vyčerpané nadhozy → vysednout.
        if (pos == null || sameSpot || ++cruiseRestarts > MAX_CRUISE_RESTARTS
                || !ctx.vehicle().waterAhead(2)) {
            endCruise(ctx);
            return;
        }
        // Nejspíš jen výpadek WorldView – rozjet plavbu znovu z aktuální pozice.
        Vec3 toDest = destination.center().sub(pos).horizontal();
        float yaw = toDest.horizontalLength() < 1.0E-4 ? 0
                : (float) Math.toDegrees(Math.atan2(-toDest.x(), toDest.z()));
        ctx.vehicle().startCruise(ctx.worldView(), pos, yaw, destination.center());
    }

    /** Ukončí plavbu a požádá o vysednutí. */
    private void endCruise(BotContext ctx) {
        ctx.vehicle().stopCruise();
        ctx.vehicle().requestDismount();
        waitTicks = DISMOUNT_WAIT;
        phase = Phase.DISMOUNT;
    }

    /** Držet sneak, dokud server nevysadí; pak odejít od lodi na břeh. */
    private void dismount(BotContext ctx) {
        if (!ctx.vehicle().mounted()) {
            waitTicks = STEP_OFF_TICKS;
            phase = Phase.STEP_OFF; // vysednuto – teď se odlepit od lodi na souš
            return;
        }
        ctx.vehicle().requestDismount(); // drž sneak každý tick, dokud nevysadí
    }

    /**
     * Odejít od zanechané lodi na pevninu ve směru cíle.
     *
     * <p>Po vysednutí bot stojí na hraně vody vedle lodi; její kolizní box by
     * mu bránil vykročit. Chvíli ho proto aktivně tlačíme k cíli, dokud
     * nestojí na pevné zemi (mimo vodu) – pak navigace převezme řízení.</p>
     */
    private void stepOff(BotContext ctx) {
        // Rozbít zanechanou loď – její kolizní box by botovi bránil vykročit;
        // navíc spadne jako item, takže je znovupoužitelná pro další přejezd.
        Optional<TrackedEntity> boat = trackedBoat(ctx);
        if (boat.isPresent() && boat.get().position().distanceSquared(ctx.position()) < 4 * 4) {
            ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0), boat.get().position());
            ctx.actions().attack(boat.get().entityId());
            ctx.actions().swing();
        }
        BlockPos feet = ctx.position().toBlockPos();
        boolean onLand = ctx.onGround()
                && ctx.worldView().traitsAt(feet.down()).solid()
                && !ctx.worldView().traitsAt(feet).liquid();
        if (onLand || --waitTicks <= 0) {
            succeeded = true;
            phase = Phase.DONE; // na břehu – navigace odsud přepočítá cestu
            return;
        }
        Vec3 dir = destination.center().sub(ctx.position()).horizontal().normalized();
        move = new MoveInput(dir, false, true, false); // skok pomůže přelézt hranu
    }

    /** Ukončí task neúspěchem a uklidí případnou rozjetou plavbu. */
    private boolean abort(BotContext ctx) {
        cancel(ctx);
        return true;
    }

    private Optional<TrackedEntity> findBoat(BotContext ctx) {
        return ctx.entities().nearest(ctx.position(), 16,
                e -> Boats.isBoatType(e.type().name()));
    }

    private Optional<TrackedEntity> trackedBoat(BotContext ctx) {
        return boatEntityId == null ? Optional.empty() : ctx.entities().byId(boatEntityId);
    }

    private boolean hasBoatItem(BotContext ctx) {
        var snapshot = ctx.serverView().latest();
        return snapshot != null && snapshot.hasItem(Boats::isBoatItem);
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
