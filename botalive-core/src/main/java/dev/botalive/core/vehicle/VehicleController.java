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
     * Požádá o vysednutí (sneak – stejně jako hráč).
     */
    public void requestDismount() {
        connection.send(new ServerboundPlayerInputPacket(false, false, false, false, false, true, false));
        connection.send(new ServerboundPlayerInputPacket(false, false, false, false, false, false, false));
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
        this.boat = new BoatPhysics(world, boatPos, yaw);
        this.cruiseTarget = target;
        this.lastForward = false;
    }

    /** Ukončí plavbu (simulace se zahodí; vozidlo zůstává). */
    public void stopCruise() {
        boat = null;
        cruiseTarget = null;
    }

    /** @return {@code true} pokud probíhá plavba */
    public boolean cruising() {
        return boat != null;
    }

    /** @return pozice lodi ze simulace, nebo {@code null} mimo plavbu */
    public Vec3 boatPosition() {
        BoatPhysics current = boat;
        return current == null ? null : current.position();
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
     * Vždy odešle ClientTickEnd; při plavbě navíc vehicle pakety.
     */
    public void tick() {
        BoatPhysics current = boat;
        if (mounted() && current != null && cruiseTarget != null) {
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
            connection.send(new ServerboundMoveVehiclePacket(
                    Vector3d.from(current.position().x(), current.position().y(),
                            current.position().z()),
                    current.yaw(), 0f, false));
        }
        connection.send(ServerboundClientTickEndPacket.INSTANCE);
    }
}
