package dev.botalive.core.combat;

import dev.botalive.api.personality.Personality;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.config.BotAliveConfig;
import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.human.Humanizer;
import dev.botalive.core.network.BotActions;
import dev.botalive.core.physics.MoveInput;
import dev.botalive.core.util.BotRandom;
import dev.botalive.core.util.Vec3;

/**
 * Bojový kontrolér bota – melee souboj s lidskými manýry.
 *
 * <p>Implementované techniky:</p>
 * <ul>
 *   <li><b>Attack cooldown s jitterem</b>: útok až po nabití zbraně (~0.6 s)
 *       plus náhodná odchylka – bot nikdy nekliká přesně po 600 ms.</li>
 *   <li><b>Reakční latence</b>: první útok po zpozorování cíle přijde po
 *       lidské reakční době (konfigurovatelný interval).</li>
 *   <li><b>Strafing</b>: boční pohyb kolem cíle se střídáním směru.</li>
 *   <li><b>Sprint reset</b>: krátké puštění sprintu před úderem pro vyšší
 *       knockback (technika zkušených hráčů, řízená obtížností).</li>
 *   <li><b>Ústup</b>: při nízkém zdraví (práh podle odvahy) couvá od cíle.</li>
 * </ul>
 */
public final class CombatController {

    /** Dosah melee útoku (bloky). */
    private static final double ATTACK_RANGE = 3.0;

    /** Základní cooldown zbraně (ticky) – meč ~0.6 s. */
    private static final int BASE_ATTACK_COOLDOWN_TICKS = 12;

    private final BotActions actions;
    private final Humanizer humanizer;
    private final BotRandom rng;
    private final Personality personality;
    private final BotAliveConfig.Combat config;
    private final CombatDifficulty difficulty;

    private TrackedEntity target;
    private int attackCooldown;
    private int reactionTicks;
    private int strafeDirection = 1;
    private int strafeTicks;
    private int sprintResetTicks;

    /**
     * @param actions     akční primitivy
     * @param humanizer   humanizace pohledu
     * @param rng         per-bot náhoda
     * @param personality osobnost
     * @param config      konfigurace boje
     * @param difficulty  bojová obtížnost (z konfigurace AI)
     */
    public CombatController(BotActions actions, Humanizer humanizer, BotRandom rng,
                            Personality personality, BotAliveConfig.Combat config,
                            CombatDifficulty difficulty) {
        this.actions = actions;
        this.humanizer = humanizer;
        this.rng = rng;
        this.personality = personality;
        this.config = config;
        this.difficulty = difficulty;
    }

    /**
     * Zahájí boj s cílem.
     *
     * @param newTarget cílová entita
     */
    public void engage(TrackedEntity newTarget) {
        if (this.target == null || this.target.entityId() != newTarget.entityId()) {
            this.target = newTarget;
            // lidská reakce na nový cíl
            long reactionMs = humanizer.reactionDelayMs(
                    (config.reactionMinMs() + config.reactionMaxMs()) / 2L,
                    (config.reactionMaxMs() - config.reactionMinMs()) / 2L);
            this.reactionTicks = (int) (reactionMs / 50) + difficulty.extraReactionTicks();
        }
    }

    /** Ukončí boj. */
    public void disengage() {
        target = null;
    }

    /** @return aktuální cíl, nebo {@code null} */
    public TrackedEntity target() {
        return target;
    }

    /** @return {@code true} pokud bot právě bojuje */
    public boolean engaged() {
        return target != null;
    }

    /**
     * Jeden bojový tick.
     *
     * @param position   pozice bota (nohy)
     * @param health     zdraví bota
     * @param onGround   bot na zemi
     * @return pohybový vstup pro fyziku
     */
    public MoveInput tick(Vec3 position, float health, boolean onGround) {
        if (target == null || !config.enabled()) {
            return MoveInput.IDLE;
        }
        Vec3 targetPos = target.position();
        Vec3 eyes = position.add(0, 1.62, 0);
        Vec3 aimPoint = targetPos.add(0, 1.2, 0);
        humanizer.lookAt(eyes, aimPoint);

        double distance = position.distance(targetPos);
        Vec3 toTarget = targetPos.sub(position).horizontal().normalized();

        // Ústup při nízkém zdraví – práh závisí na odvaze.
        double retreatThreshold = 6 + (1.0 - personality.trait(Trait.COURAGE)) * 8;
        if (health <= retreatThreshold) {
            return MoveInput.of(toTarget.mul(-1), true, onGround && rng.chance(0.15));
        }

        if (attackCooldown > 0) {
            attackCooldown--;
        }
        if (reactionTicks > 0) {
            reactionTicks--;
        }

        // Útok, pokud je cíl v dosahu, zbraň nabitá a reakce doběhla.
        if (distance <= ATTACK_RANGE && attackCooldown == 0 && reactionTicks == 0) {
            if (rng.chance(difficulty.hitChance())) {
                actions.attack(target.entityId());
            } else {
                actions.swing(); // minutí – klik do vzduchu
            }
            attackCooldown = BASE_ATTACK_COOLDOWN_TICKS
                    + rng.rangeInt(0, 6) + difficulty.extraCooldownTicks();
            // Sprint reset: krátké puštění sprintu po úderu.
            if (difficulty.sprintReset() && rng.chance(0.6)) {
                sprintResetTicks = rng.rangeInt(2, 4);
            }
        }

        // Pohyb: přiblížení + strafing kolem cíle.
        Vec3 movement = toTarget;
        if (config.strafing() && distance < ATTACK_RANGE + 1.5) {
            if (--strafeTicks <= 0) {
                strafeDirection = -strafeDirection;
                strafeTicks = rng.rangeInt(8, 25);
            }
            Vec3 side = new Vec3(-toTarget.z(), 0, toTarget.x()).mul(strafeDirection);
            double closeness = distance < 2.0 ? -0.4 : 0.6; // moc blízko → mírně couvat
            movement = toTarget.mul(closeness).add(side.mul(0.8)).normalized();
        }

        boolean sprint = distance > 3.5 && sprintResetTicks == 0;
        if (sprintResetTicks > 0) {
            sprintResetTicks--;
        }
        boolean jump = onGround && rng.chance(0.03); // občasný poskok
        return MoveInput.of(movement, sprint, jump);
    }
}
