package dev.botalive.core.vehicle;

import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.BlockTraits;
import dev.botalive.core.world.WorldView;

/**
 * Klientská simulace osedlaného stridera na lávě.
 *
 * <p>Jízda ve vozidle je klientsky autoritativní – řidič posílá
 * {@code ServerboundMoveVehiclePacket} a server validuje, stejně jako
 * u lodi ({@link BoatPhysics}). Strider chodí po hladině lávy jako po
 * pevné zemi: jízdní rychlost ~0.096 bloku/tick (vanilla s jezdcem
 * a houbou na prutu), mimo lávu „prochladlý" zpomalí. Výška sleduje
 * hladinu lávy se schodem ±1 blok; vystoupení na pevný břeh jízdu končí
 * ({@link #ashore()}).</p>
 *
 * <p>Třída je čistá (žádné pakety, žádný Bukkit) – testovatelná nad
 * syntetickým {@link WorldView}.</p>
 */
public final class StriderPhysics {

    /** Jízdní rychlost na lávě (bloky/tick) – vanilla osedlaný strider. */
    private static final double LAVA_SPEED = 0.096;

    /** Rychlost mimo lávu – strider se třese zimou a zpomalí. */
    private static final double COLD_SPEED = 0.06;

    /** Náběh rychlosti (bloky/tick za tick). */
    private static final double ACCELERATION = 0.02;

    /** Max. úhlová rychlost zatáčení (stupně/tick). */
    private static final float MAX_TURN_PER_TICK = 5f;

    /** Po kolika korekcích serveru v řadě je jasné, že jízdu nepouští. */
    private static final int HARD_STUCK_CORRECTIONS = 3;

    private final WorldView world;

    private Vec3 position;
    private float yaw;
    private double speed;
    private boolean ashore;
    private boolean stuck;
    /** Série korekcí od serveru bez čistého ticku jízdy (nesouhlas o terénu). */
    private int correctionStreak;

    /**
     * @param world    pohled na svět (kontrola lávy/břehu)
     * @param position výchozí pozice stridera (od serveru)
     * @param yaw      výchozí natočení stridera
     */
    public StriderPhysics(WorldView world, Vec3 position, float yaw) {
        this.world = world;
        this.position = position;
        this.yaw = yaw;
    }

    /** @return aktuální pozice stridera */
    public Vec3 position() {
        return position;
    }

    /** @return aktuální natočení stridera */
    public float yaw() {
        return yaw;
    }

    /** @return aktuální rychlost (bloky/tick) */
    public double speed() {
        return speed;
    }

    /** @return {@code true} pokud strider vystoupal na pevný břeh */
    public boolean ashore() {
        return ashore;
    }

    /** @return {@code true} pokud jízda uvázla (zeď/sráz, přetlačování se serverem) */
    public boolean stuck() {
        return stuck;
    }

    /**
     * Tvrdá korekce od serveru (clientbound MoveVehicle).
     *
     * <p>Stejná pojistka jako u lodi: série korekcí bez čistého ticku jízdy
     * znamená, že server pohyb nepouští (klient nevidí skutečný terén) –
     * přestat se přetlačovat a nechat jízdu ukončit vysednutím.</p>
     *
     * @param position pozice od serveru
     * @param yaw      natočení od serveru
     */
    public void correct(Vec3 position, float yaw) {
        this.position = position;
        this.yaw = yaw;
        if (++correctionStreak >= HARD_STUCK_CORRECTIONS) {
            stuck = true;
            speed = 0;
        }
    }

    /**
     * Jeden tick jízdy směrem k cíli.
     *
     * @param target cílový bod (Y se ignoruje – jede se po hladině/terénu)
     */
    public void stepToward(Vec3 target) {
        if (ashore || stuck) {
            return;
        }
        // Zatáčení k cíli s omezenou úhlovou rychlostí.
        Vec3 toTarget = target.sub(position);
        float desiredYaw = (float) Math.toDegrees(Math.atan2(-toTarget.x(), toTarget.z()));
        float error = wrapDegrees(desiredYaw - yaw);
        yaw = wrapDegrees(yaw + clamp(error, -MAX_TURN_PER_TICK, MAX_TURN_PER_TICK));

        // Rychlost s náběhem; mimo lávu strider „mrzne" a jde pomaleji.
        double cruise = isLavaSupport(position) ? LAVA_SPEED : COLD_SPEED;
        speed = Math.min(speed + ACCELERATION, cruise);
        double yawRad = Math.toRadians(yaw);
        Vec3 heading = new Vec3(-Math.sin(yawRad), 0, Math.cos(yawRad));
        Vec3 ahead = position.add(heading.mul(speed));

        Support support = findSupport(ahead);
        if (support == null) {
            // Zeď nebo sráz – stát; jízdu dořeší vysednutí/restart kurzu.
            speed = 0;
            stuck = true;
            return;
        }
        position = new Vec3(ahead.x(), support.feetY(), ahead.z());
        if (support.land()) {
            // Pevný břeh – přejezd lávy je u konce, jezdec vysedá.
            ashore = true;
            speed = 0;
        }
        // Čistý tick jízdy rozpouští sérii korekcí (ojedinělá korekce za
        // jízdy je normální a stuck z ní být nemá).
        if (correctionStreak > 0) {
            correctionStreak--;
        }
    }

    /** Stojí strider (± půl bloku pod nohama) na lávě? */
    private boolean isLavaSupport(Vec3 at) {
        BlockPos below = new BlockPos((int) Math.floor(at.x()),
                (int) Math.floor(at.y() - 0.5), (int) Math.floor(at.z()));
        BlockTraits traits = world.traitsAt(below);
        return traits.liquid() && traits.hazard();
    }

    /** Nosný povrch pod nohama: výška chodidel + zda jde o pevninu. */
    private record Support(double feetY, boolean land) {
    }

    /**
     * Najde nosný povrch (láva/pevnina) v okně ±1 bloku od aktuální výšky –
     * strider zvládá schod jako chodec. Pořadí: stejná výška → schod vzhůru
     * → schod dolů; nad povrchem musí být volno.
     */
    private Support findSupport(Vec3 at) {
        int bx = (int) Math.floor(at.x());
        int bz = (int) Math.floor(at.z());
        int baseY = (int) Math.floor(at.y()) - 1; // blok pod nohama
        int[] steps = {0, 1, -1};
        for (int dy : steps) {
            BlockPos support = new BlockPos(bx, baseY + dy, bz);
            BlockTraits s = world.traitsAt(support);
            if (!world.traitsAt(support.up()).passable()) {
                continue;
            }
            if (s.liquid() && s.hazard()) {
                return new Support(support.y() + 1.0, false);
            }
            if (s.solid()) {
                return new Support(support.y() + 1.0, true);
            }
        }
        return null;
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
