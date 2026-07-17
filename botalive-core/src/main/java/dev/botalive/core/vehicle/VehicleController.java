package dev.botalive.core.vehicle;

import dev.botalive.core.network.BotClientState;
import dev.botalive.core.network.BotConnection;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.WorldView;
import org.cloudburstmc.math.vector.Vector3d;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientTickEndPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundMoveVehiclePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundPaddleBoatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundPlayerInputPacket;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Řízení vozidel (lodě; mount/dismount primitivy i pro minecarty).
 *
 * <p>Nasednutí/vysednutí jde přes reálné pakety (interact / sneak) a server je
 * potvrzuje paketem SetPassengers – stav drží {@link BotClientState}. Při
 * plavbě bot – stejně jako vanilla klient – simuluje loď ({@link BoatPhysics})
 * a každý tick posílá PlayerInput (klávesy), PaddleBoat (animace pádel)
 * a MoveVehicle (autoritativní pozice vozidla). Korekce od serveru se
 * přijímají ze síťového vlákna přes atomickou schránku.</p>
 */
public final class VehicleController {

    private final BotConnection connection;
    private final BotClientState state;

    private BoatPhysics boat;
    private MinecartPhysics cart;
    private Vec3 cruiseTarget;
    private boolean lastForward;

    /** Korekce od serveru (network vlákno → tick vlákno). */
    private final AtomicReference<VehicleSync> pendingCorrection = new AtomicReference<>();

    private record VehicleSync(Vec3 position, float yaw) {
    }

    /**
     * @param connection spojení bota
     * @param state      protokolový stav (id vozidla)
     */
    public VehicleController(BotConnection connection, BotClientState state) {
        this.connection = connection;
        this.state = state;
    }

    /** @return {@code true} pokud bot sedí ve vozidle (potvrzeno serverem) */
    public boolean mounted() {
        return state.vehicleId() >= 0;
    }

    /**
     * Požádá o vysednutí – „drží" sneak (stejně jako hráč, který podrží shift).
     *
     * <p>Posílá jen stisk (bez okamžitého uvolnění – to by záměr zrušilo dřív,
     * než ho server v ticku zpracuje). Volá se opakovaně, dokud server jezdce
     * nevysadí; klid nastane sám, jakmile bot po vysednutí zase chodí.</p>
     */
    public void requestDismount() {
        connection.send(new ServerboundPlayerInputPacket(false, false, false, false, false, true, false));
    }

    /**
     * Zahájí plavbu k cíli.
     *
     * @param world    pohled na svět
     * @param boatPos  aktuální pozice lodi (z trackeru entit)
     * @param yaw      aktuální natočení lodi
     * @param target   cílový bod na hladině
     */
    public void startCruise(WorldView world, Vec3 boatPos, float yaw, Vec3 target) {
        stopCruise();
        this.boat = new BoatPhysics(world, boatPos, yaw);
        this.cruiseTarget = target;
        this.lastForward = false;
    }

    /**
     * Zahájí jízdu minecartem po kolejích.
     *
     * @param world   pohled na svět (čtení kolejí)
     * @param cartPos aktuální pozice vozíku (z trackeru entit)
     * @return {@code true} pokud pod vozíkem je kolej a jízda začala
     */
    public boolean startRailRide(WorldView world, Vec3 cartPos) {
        stopCruise();
        try {
            this.cart = new MinecartPhysics(new WorldRailReader(world), cartPos);
            return true;
        } catch (IllegalStateException e) {
            this.cart = null;
            return false;
        }
    }

    /** Ukončí plavbu/jízdu (simulace se zahodí; vozidlo zůstává). */
    public void stopCruise() {
        boat = null;
        cart = null;
        cruiseTarget = null;
    }

    /** @return {@code true} pokud probíhá plavba/jízda */
    public boolean cruising() {
        return boat != null || cart != null;
    }

    /** @return pozice vozidla ze simulace, nebo {@code null} mimo plavbu/jízdu */
    public Vec3 vehiclePosition() {
        BoatPhysics currentBoat = boat;
        if (currentBoat != null) {
            return currentBoat.position();
        }
        MinecartPhysics currentCart = cart;
        return currentCart == null ? null : currentCart.position();
    }

    /** @return {@code true} pokud jízda minecartem skončila (konec trati / stání) */
    public boolean railRideFinished() {
        MinecartPhysics current = cart;
        return current != null && current.finishedRiding();
    }

    /** @return {@code true} pokud loď najela na břeh */
    public boolean aground() {
        BoatPhysics current = boat;
        return current != null && current.aground();
    }

    /** @return {@code true} pokud je před přídí volná voda */
    public boolean waterAhead(double distance) {
        BoatPhysics current = boat;
        return current != null && current.waterAhead(distance);
    }

    /** @return {@code true} pokud loď doplula k cíli plavby */
    public boolean arrived() {
        BoatPhysics current = boat;
        return current != null && cruiseTarget != null
                && current.position().horizontal()
                        .distanceSquared(cruiseTarget.horizontal()) < 3 * 3;
    }

    /**
     * Korekce vozidla od serveru (volá se ze síťového vlákna).
     *
     * @param position pozice od serveru
     * @param yaw      natočení od serveru
     */
    public void applyServerVehicleMove(Vec3 position, float yaw) {
        pendingCorrection.set(new VehicleSync(position, yaw));
    }

    /**
     * Jeden tick ve vozidle – volá se místo běžné pohybové smyčky.
     * Vždy odešle ClientTickEnd; při plavbě/jízdě navíc vehicle pakety.
     */
    public void tick() {
        if (mounted()) {
            tickBoat();
            tickCart();
        }
        connection.send(ServerboundClientTickEndPacket.INSTANCE);
    }

    /** Tick plavby lodí. */
    private void tickBoat() {
        BoatPhysics current = boat;
        if (current == null || cruiseTarget == null) {
            return;
        }
        VehicleSync correction = pendingCorrection.getAndSet(null);
        if (correction != null) {
            current.correct(correction.position(), correction.yaw());
        }
        current.stepToward(cruiseTarget);

        // Klávesy: W drženo po dobu plavby (posílat při změně, jako vanilla).
        boolean forward = !current.aground();
        if (forward != lastForward) {
            connection.send(new ServerboundPlayerInputPacket(
                    forward, false, current.turningLeft(), current.turningRight(),
                    false, false, false));
            lastForward = forward;
        }
        // Animace pádel + autoritativní pozice vozidla.
        connection.send(new ServerboundPaddleBoatPacket(
                forward || current.turningRight(), forward || current.turningLeft()));
        sendVehiclePosition(current.position(), current.yaw(), false);
    }

    /** Tick jízdy minecartem. */
    private void tickCart() {
        MinecartPhysics current = cart;
        if (current == null) {
            return;
        }
        VehicleSync correction = pendingCorrection.getAndSet(null);
        if (correction != null) {
            current.correct(correction.position(), correction.yaw());
        }
        current.step();
        sendVehiclePosition(current.position(), current.yaw(), true);
    }

    private void sendVehiclePosition(Vec3 position, float yaw, boolean onGround) {
        connection.send(new ServerboundMoveVehiclePacket(
                Vector3d.from(position.x(), position.y(), position.z()),
                yaw, 0f, onGround));
    }
}
