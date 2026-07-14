package dev.botalive.core.human;

import dev.botalive.api.personality.Personality;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.util.BotRandom;
import dev.botalive.core.util.Vec3;

/**
 * Humanizace projevu bota – nic, co bot dělá, nesmí být strojově dokonalé.
 *
 * <p>Konkrétně:</p>
 * <ul>
 *   <li><b>Pohled</b>: hlava se otáčí s omezenou úhlovou rychlostí a easingem,
 *       s drobným šumem a malou trvalou chybou míření (nikdy přesný střed).</li>
 *   <li><b>Reakce</b>: každá reakce má latenci vzorkovanou z log-normálního
 *       rozdělení – rychlé reflexy jsou časté, občas bot „zaspí".</li>
 *   <li><b>Mikro-chování</b>: náhodné rozhlížení, pohledy na blízké entity,
 *       krátká zastavení – řízené povahou (zvědavost, lenost).</li>
 * </ul>
 *
 * <p>Všechna náhodnost čerpá z per-bot {@link BotRandom}, takže se boti
 * nechovají synchronně.</p>
 */
public final class Humanizer {

    /** Maximální úhlová rychlost otáčení hlavy (stupně/tick). */
    private static final float MAX_TURN_SPEED = 40f;

    /** Easing faktor – jak agresivně se hlava stáčí k cíli. */
    private static final float TURN_EASING = 0.35f;

    private final BotRandom rng;
    private final Personality personality;

    private float yaw;
    private float pitch;
    private Float targetYaw;
    private Float targetPitch;

    /** Trvalá chyba míření tohoto bota (stupně) – každý bot míří jinak. */
    private final float aimErrorYaw;
    private final float aimErrorPitch;

    private int idleLookCooldown;

    /**
     * @param rng         per-bot náhoda
     * @param personality osobnost (ovlivňuje četnost mikro-chování)
     */
    public Humanizer(BotRandom rng, Personality personality) {
        this.rng = rng;
        this.personality = personality;
        this.aimErrorYaw = (float) rng.gaussian(0, 1.2);
        this.aimErrorPitch = (float) rng.gaussian(0, 0.8);
    }

    /** @return aktuální (vyhlazený) yaw */
    public float yaw() {
        return yaw;
    }

    /** @return aktuální (vyhlazený) pitch */
    public float pitch() {
        return pitch;
    }

    /**
     * Tvrdé nastavení rotace (teleport).
     */
    public void snapTo(float yaw, float pitch) {
        this.yaw = yaw;
        this.pitch = pitch;
        this.targetYaw = null;
        this.targetPitch = null;
    }

    /**
     * Požádá o pohled daným směrem (cíl, ke kterému se hlava plynule stočí).
     *
     * @param from oči bota
     * @param at   sledovaný bod
     */
    public void lookAt(Vec3 from, Vec3 at) {
        double dx = at.x() - from.x();
        double dy = at.y() - from.y();
        double dz = at.z() - from.z();
        double horizontal = Math.sqrt(dx * dx + dz * dz);
        targetYaw = (float) Math.toDegrees(Math.atan2(-dx, dz)) + aimErrorYaw;
        targetPitch = (float) -Math.toDegrees(Math.atan2(dy, horizontal)) + aimErrorPitch;
    }

    /**
     * Požádá o pohled ve směru pohybu (přirozené držení hlavy při chůzi).
     *
     * @param direction směr pohybu
     */
    public void lookAlong(Vec3 direction) {
        if (direction.horizontalLength() < 1.0E-4) {
            return;
        }
        targetYaw = (float) Math.toDegrees(Math.atan2(-direction.x(), direction.z()))
                + (float) rng.gaussian(0, 2.0);
        targetPitch = (float) rng.gaussian(8, 4); // při chůzi kouká mírně dolů
    }

    /**
     * Jeden tick vyhlazování pohledu + mikro-chování.
     *
     * @param eyes     pozice očí bota
     * @param idleness {@code true} pokud bot nemá co dělat (více se rozhlíží)
     */
    public void tick(Vec3 eyes, boolean idleness) {
        microLook(idleness);

        if (targetYaw != null) {
            float delta = wrapDegrees(targetYaw - yaw);
            float step = clamp(delta * TURN_EASING, -MAX_TURN_SPEED, MAX_TURN_SPEED);
            // drobný třes ruky
            step += (float) rng.gaussian(0, 0.15);
            yaw = wrapDegrees(yaw + step);
            if (Math.abs(delta) < 0.5f) {
                targetYaw = null;
            }
        }
        if (targetPitch != null) {
            float delta = targetPitch - pitch;
            float step = clamp(delta * TURN_EASING, -MAX_TURN_SPEED, MAX_TURN_SPEED);
            step += (float) rng.gaussian(0, 0.1);
            pitch = clamp(pitch + step, -90f, 90f);
            if (Math.abs(delta) < 0.5f) {
                targetPitch = null;
            }
        }
    }

    /** Náhodné rozhlížení, když se nic neděje. */
    private void microLook(boolean idleness) {
        if (idleLookCooldown-- > 0 || !idleness) {
            return;
        }
        double curiosity = personality.trait(Trait.CURIOSITY);
        if (rng.chance(0.03 + curiosity * 0.05)) {
            targetYaw = wrapDegrees(yaw + (float) rng.gaussian(0, 45));
            targetPitch = clamp((float) rng.gaussian(5, 15), -60f, 60f);
            idleLookCooldown = rng.rangeInt(20, 100);
        }
    }

    /**
     * Vzorkuje lidskou reakční dobu.
     *
     * @param baseMs   základní reakce (např. 200 ms)
     * @param spreadMs rozptyl
     * @return latence v ms (nikdy méně než 60 ms)
     */
    public long reactionDelayMs(long baseMs, long spreadMs) {
        double intelligence = personality.trait(Trait.INTELLIGENCE);
        double laziness = personality.trait(Trait.LAZINESS);
        double factor = 1.0 - intelligence * 0.3 + laziness * 0.4;
        long sample = (long) Math.abs(rng.gaussian(baseMs * factor, spreadMs));
        return Math.max(60, sample);
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
