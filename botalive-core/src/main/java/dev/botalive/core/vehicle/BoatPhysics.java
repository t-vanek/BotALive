package dev.botalive.core.vehicle;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.WorldView;

/**
 * Klientská simulace lodi na vodě.
 *
 * <p>V Minecraftu je jízda ve vozidle klientsky autoritativní – řidič posílá
 * {@code ServerboundMoveVehiclePacket} a server ji validuje. Tahle simulace
 * replikuje vanilla kinematiku lodi: dopředné zrychlení ~0.04/tick s útlumem
 * 0.9 (terminální rychlost ~8 bloků/s), zatáčení omezenou úhlovou rychlostí.
 * Výška se drží na hladině z okamžiku nalodění – loď se pohybuje jen po vodě;
 * najetí na mělčinu simulace detekuje ({@link #aground()}).</p>
 *
 * <p>Třída je čistá (žádné pakety, žádný Bukkit) – testovatelná nad
 * syntetickým {@link WorldView}.</p>
 */
public final class BoatPhysics {

    private static final double ACCELERATION = 0.04;
    private static final double DRAG = 0.9;
    private static final float MAX_TURN_PER_TICK = 2.5f;

    private final WorldView world;

    private Vec3 position;
    private float yaw;
    private double speed;
    private boolean aground;
    private boolean turningLeft;
    private boolean turningRight;
    /** Série korekcí od serveru bez čistého ticku plavby (nesouhlas o vodě). */
    private int correctionStreak;

    /** Po kolika korekcích v řadě je jasné, že server loď nepouští (břeh). */
    private static final int HARD_AGROUND_CORRECTIONS = 3;

    /**
     * @param world    pohled na svět (kontrola vody)
     * @param position výchozí pozice lodi (od serveru)
     * @param yaw      výchozí natočení lodi
     */
    public BoatPhysics(WorldView world, Vec3 position, float yaw) {
        this.world = world;
        this.position = position;
        this.yaw = yaw;
    }

    /** @return aktuální pozice lodi */
    public Vec3 position() {
        return position;
    }

    /** @return aktuální natočení lodi */
    public float yaw() {
        return yaw;
    }

    /** @return aktuální rychlost (bloky/tick) */
    public double speed() {
        return speed;
    }

    /** @return {@code true} pokud loď najela na mělčinu/břeh */
    public boolean aground() {
        return aground;
    }

    /** @return {@code true} pokud loď právě zatáčí doleva (pro animaci pádel) */
    public boolean turningLeft() {
        return turningLeft;
    }

    /** @return {@code true} pokud loď právě zatáčí doprava (pro animaci pádel) */
    public boolean turningRight() {
        return turningRight;
    }

    /**
     * Tvrdá korekce od serveru (clientbound MoveVehicle).
     *
     * <p>Korekce dřív mazala {@code aground} – když ale klient mělčinu nevidí
     * (chunk cache, geometrie břehu) a server ano, vznikla smyčka: korekce →
     * záběr vpřed → „moved wrongly" → korekce, klidně 20× za sekundu. Série
     * korekcí bez čistého ticku plavby proto znamená tvrdý aground: přestat
     * se se serverem přetlačovat a nechat plavbu vyřešit vysednutí/restart.</p>
     *
     * @param position pozice od serveru
     * @param yaw      natočení od serveru
     */
    public void correct(Vec3 position, float yaw) {
        this.position = position;
        this.yaw = yaw;
        if (++correctionStreak >= HARD_AGROUND_CORRECTIONS) {
            aground = true;
            speed = 0;
        }
    }

    /**
     * Jeden tick plavby směrem k cíli.
     *
     * @param target cílový bod (Y se ignoruje – pluje se po hladině)
     */
    public void stepToward(Vec3 target) {
        if (aground) {
            return;
        }
        // Zatáčení k cíli s omezenou úhlovou rychlostí.
        Vec3 toTarget = target.sub(position);
        float desiredYaw = (float) Math.toDegrees(Math.atan2(-toTarget.x(), toTarget.z()));
        float error = wrapDegrees(desiredYaw - yaw);
        turningLeft = error < -3;
        turningRight = error > 3;
        yaw = wrapDegrees(yaw + clamp(error, -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK));

        // Dopředný pohon s útlumem (vanilla boat kinematika).
        speed = (speed + ACCELERATION) * DRAG;
        double yawRad = Math.toRadians(yaw);
        Vec3 heading = new Vec3(-Math.sin(yawRad), 0, Math.cos(yawRad));
        Vec3 next = position.add(heading.mul(speed));

        if (!isWaterColumn(next)) {
            // Mělčina/břeh – zastavit, ať se loď „nevklíní" do terénu.
            aground = true;
            speed = 0;
            return;
        }
        position = next;
        // Čistý tick plavby: série korekcí se rozpouští (ojedinělá korekce
        // za jízdy je normální a aground z ní být nemá).
        if (correctionStreak > 0) {
            correctionStreak--;
        }
    }

    /**
     * @param distance vzdálenost před přídí
     * @return {@code true} pokud je v daném směru stále voda
     */
    public boolean waterAhead(double distance) {
        double yawRad = Math.toRadians(yaw);
        Vec3 probe = position.add(-Math.sin(yawRad) * distance, 0, Math.cos(yawRad) * distance);
        return isWaterColumn(probe);
    }

    /** Voda v úrovni ponoru lodi (hladina, případně blok pod ní). */
    private boolean isWaterColumn(Vec3 at) {
        BlockPos waterline = new BlockPos(
                (int) Math.floor(at.x()), (int) Math.floor(at.y()), (int) Math.floor(at.z()));
        var traits = world.traitsAt(waterline);
        if (traits.liquid() && !traits.hazard()) {
            return true;
        }
        var below = world.traitsAt(waterline.down());
        return below.liquid() && !below.hazard();
    }

    private static float wrapDegrees(float degrees) {
        float wrapped = degrees % 360f;
        if (wrapped >= 180f) {
            wrapped -= 360f;
        }
        if (wrapped < -180f) {
            wrapped += 360f;
        }
        return wrapped;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }
}
