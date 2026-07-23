package dev.botalive.core.combat;

import dev.botalive.api.personality.Personality;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.bot.ServerSideView;
import dev.botalive.core.config.BotAliveConfig;
import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.human.Humanizer;
import dev.botalive.core.inventory.InventoryHelper;
import dev.botalive.core.network.BotActions;
import dev.botalive.core.pathfinding.Navigator;
import dev.botalive.core.pathfinding.PathGoal;
import dev.botalive.core.physics.MoveInput;
import dev.botalive.core.util.BotRandom;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.BlockTraits;
import dev.botalive.core.world.WorldView;
import org.bukkit.Material;

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
 *
 * <p><b>Hybrid s navigací</b>: mikropohyb (strafing, rozestupy, timing úderů)
 * zůstává přímému řízení, ale když přímá cesta nefunguje – chybí volná
 * spojnice (zeď, roh), nebo se vzdálenost přes snahu nezmenšuje (příkop,
 * plot) – převezme přiblížení pathfinding ({@code near(cíl, 2)} s drift
 * throttlem pohyblivých cílů). Ústup při nízkém zdraví je dvoustupňový jako
 * v SurviveGoalu: okamžité přímé couvání drží bota v pohybu a jakmile se
 * dopočítá plánovaný útěk {@code awayFrom} po pochozím terénu (obchází lávu,
 * hrany i slepé kouty), převezme řízení navigace. {@link #tick} pak vrací
 * {@code null} – volající nechá pohyb navigátoru.</p>
 */
public final class CombatController {

    /** Dosah melee útoku (bloky). */
    private static final double ATTACK_RANGE = 3.0;

    /** Základní cooldown zbraně (ticky) – meč ~0.6 s. */
    private static final int BASE_ATTACK_COOLDOWN_TICKS = 12;

    /** Vzdálenost, od které bot preferuje střelbu (má-li luk/kuši). */
    private static final double RANGED_MIN_DISTANCE = 7.0;

    /** Maximální efektivní dostřel. */
    private static final double RANGED_MAX_DISTANCE = 32.0;

    /** Ticky bez postupu k cíli, po kterých přiblížení převezme navigace. */
    private static final int NO_PROGRESS_TICKS = 30;
    /**
     * Strop obcházení k jednomu cíli (ticky).
     *
     * <p>Bez něj se bot zamkl na nedosažitelném mobovi (přes vodu, za zdí, na
     * římse): {@code navigatingApproach} se držel „až na dosah úderu“, cíl byl
     * pořád v dosahu trackeru, takže ani {@code lostTargetTicks} nerostlo a
     * {@code CombatGoal.finished()} nikdy nenastalo. V měření to byl zdaleka
     * nejčastější zdroj nehybných botů (208 z 211 hlášení watchdogu).</p>
     */
    private static final int APPROACH_TIMEOUT_TICKS = 200;

    /** Cílová vzdálenost plánovaného ústupu (bloky, vodorovně). */
    private static final int RETREAT_DISTANCE = 12;

    private final BotActions actions;
    private final Humanizer humanizer;
    private final BotRandom rng;
    private final Personality personality;
    private final BotAliveConfig.Combat config;
    private final CombatDifficulty difficulty;
    private final InventoryHelper inventory;
    private final RangedAttack ranged;

    private Navigator navigator;
    private WorldView world;
    /** Přiblížení řídí navigace (bez spojnice / bez postupu). */
    private boolean navigatingApproach;
    /** Ústup řídí navigace (plánovaný útěk awayFrom). */
    private boolean navigatingRetreat;
    /** Nejlepší dosažená vzdálenost k cíli (detekce marného přibližování). */
    private double bestApproachDistance = Double.MAX_VALUE;
    private int noProgressTicks;
    /** Jak dlouho už bot obchází k aktuálnímu cíli (ticky). */
    private int approachTicks;
    /** Cíl je nedosažitelný – {@code CombatGoal} má boj ukončit. */
    private boolean targetUnreachable;

    private TrackedEntity target;
    private int attackCooldown;
    private int reactionTicks;
    private int strafeDirection = 1;
    private int strafeTicks;
    private int sprintResetTicks;
    private int shieldBlockTicks;

    /**
     * @param actions     akční primitivy
     * @param humanizer   humanizace pohledu
     * @param rng         per-bot náhoda
     * @param personality osobnost
     * @param config      konfigurace boje
     * @param difficulty  bojová obtížnost (z konfigurace AI)
     * @param inventory   výběr zbraní v hotbaru
     */
    public CombatController(BotActions actions, Humanizer humanizer, BotRandom rng,
                            Personality personality, BotAliveConfig.Combat config,
                            CombatDifficulty difficulty, InventoryHelper inventory) {
        this.actions = actions;
        this.humanizer = humanizer;
        this.rng = rng;
        this.personality = personality;
        this.config = config;
        this.difficulty = difficulty;
        this.inventory = inventory;
        this.ranged = new RangedAttack(actions, humanizer, rng, inventory);
    }

    /**
     * Napojí navigaci – boj s ní obchází překážky (kiting) a plánuje ústup.
     * Bez napojení zůstává čistě přímé řízení (testy, degradovaný režim).
     *
     * @param navigator navigátor bota
     */
    public void navigation(Navigator navigator) {
        this.navigator = navigator;
    }

    /**
     * Napojí pohled na svět (kontrola volné spojnice na cíl).
     *
     * @param world svět bota
     */
    public void world(WorldView world) {
        this.world = world;
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
            this.bestApproachDistance = Double.MAX_VALUE;
            this.noProgressTicks = 0;
            this.approachTicks = 0;
            this.targetUnreachable = false;
        }
    }

    /** Ukončí boj. */
    public void disengage() {
        target = null;
        ranged.reset();
        shieldBlockTicks = 0;
        approachTicks = 0;
        targetUnreachable = false;
        stopNavigation();
    }

    /**
     * @return {@code true} když se k cíli nedá dojít a boj nemá smysl držet
     */
    public boolean targetUnreachable() {
        return targetUnreachable;
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
     * @param snapshot   server-side snapshot (výběr zbraně, štít); může být null
     * @return pohybový vstup pro fyziku, nebo {@code null} když řízení
     *         převzala navigace (přiblížení bez spojnice, plánovaný ústup)
     *         – volající nechá pohyb navigátoru
     */
    public MoveInput tick(Vec3 position, float health, boolean onGround,
                          ServerSideView.Snapshot snapshot) {
        if (target == null || !config.enabled()) {
            stopNavigation();
            return MoveInput.IDLE;
        }
        Vec3 targetPos = target.position();
        Vec3 eyes = position.add(0, 1.62, 0);
        Vec3 aimPoint = targetPos.add(0, 1.2, 0);
        humanizer.lookAt(eyes, aimPoint);

        double distance = position.distance(targetPos);
        Vec3 toTarget = targetPos.sub(position).horizontal().normalized();

        // Ústup při nízkém zdraví – práh závisí na odvaze. Dvoustupňově:
        // okamžité přímé couvání drží bota v pohybu, a jakmile se dopočítá
        // plánovaný útěk po pochozím terénu (obchází lávu, hrany, slepé
        // kouty), převezme řízení navigace.
        double retreatThreshold = 6 + (1.0 - personality.trait(Trait.COURAGE)) * 8;
        if (health <= retreatThreshold) {
            ranged.reset();
            if (navigator != null) {
                navigatingRetreat = true;
                navigatingApproach = false;
                navigator.navigateTo(position,
                        PathGoal.awayFrom(targetPos.toBlockPos(), RETREAT_DISTANCE));
                if (navigator.pathReady()) {
                    return null;
                }
            }
            // Panika couvá s ochranou hran a lávy – i pár slepých ticků
            // s rozběhem umí skončit v hazardu hned za zády.
            MoveInput panic = MoveInput.of(toTarget.mul(-1), true,
                    onGround && rng.chance(0.15));
            return world != null
                    ? dev.botalive.core.physics.EdgeGuard.apply(world, position, panic)
                    : panic;
        }
        if (navigatingRetreat) {
            stopNavigation(); // zdraví se zvedlo (jídlo, lektvar) – ústup končí
        }

        // Přiblížení navigací, když přímý pohyb nefunguje: bez volné spojnice
        // (zeď, roh), nebo se vzdálenost přes snahu nezmenšuje (příkop, plot
        // se spojnicí). V dosahu úderu se řízení vrací přímému mikropohybu.
        if (updateApproach(position, targetPos, distance)) {
            return null;
        }

        // Zavřený shulker: pancíř krunýře odráží šípy a údery do zavřené
        // ulity jsou zelenáčská ztráta času. Bot si nachystá zbraň, drží se
        // na dosah a čeká – střílet musí shulker s otevřeným krunýřem,
        // a v tu chvíli je zranitelný.
        if (target.shulkerClosed()) {
            if (attackCooldown > 0) {
                attackCooldown--;
            }
            if (reactionTicks > 0) {
                reactionTicks--;
            }
            inventory.equipWeapon(snapshot);
            if (distance > ATTACK_RANGE) {
                // Dojít na dosah (bez sprintu – nikam se nespěchá).
                return MoveInput.of(toTarget, false, false);
            }
            if (--strafeTicks <= 0) {
                strafeDirection = -strafeDirection;
                strafeTicks = rng.rangeInt(12, 30);
            }
            // Vyčkávací kroužení: směr udává znaménko, rychlost řeší fyzika
            // (MoveInput směr normalizuje – násobky velikosti nemají smysl).
            Vec3 wait = new Vec3(-toTarget.z(), 0, toTarget.x())
                    .mul(strafeDirection);
            return MoveInput.of(wait, false, false);
        }

        // Střelba na dálku, pokud je čím střílet.
        if (distance >= RANGED_MIN_DISTANCE && distance <= RANGED_MAX_DISTANCE
                && ranged.canUse(snapshot)) {
            return ranged.tick(position, target, snapshot);
        }
        if (ranged.busy()) {
            ranged.reset(); // cíl se přiblížil → melee
        }

        // Melee: meč/sekera do ruky.
        inventory.equipWeapon(snapshot);

        if (attackCooldown > 0) {
            attackCooldown--;
        }
        if (reactionTicks > 0) {
            reactionTicks--;
        }

        // Blokování štítem: krátké zvednutí mezi vlastními útoky.
        if (shieldBlockTicks > 0) {
            shieldBlockTicks--;
            if (shieldBlockTicks == 0) {
                actions.releaseUseItem();
            }
            return MoveInput.of(toTarget.mul(-0.4), false, false); // pomalé couvání s krytem
        }
        if (config.shieldUse() && snapshot != null
                && dev.botalive.core.inventory.Items.isShield(snapshot.offhand())
                && distance < 4.0 && attackCooldown > 4
                && rng.chance(0.04 + personality.trait(Trait.CAUTION) * 0.06)) {
            actions.useOffhand(humanizer.yaw(), humanizer.pitch());
            shieldBlockTicks = rng.rangeInt(8, 22);
            return MoveInput.IDLE;
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

    /**
     * Rozhodne, zda přiblížení řídí navigace, a případně ji krmí cílem.
     *
     * @return {@code true} když pohyb tento tick řídí navigátor
     */
    private boolean updateApproach(Vec3 position, Vec3 targetPos, double distance) {
        if (navigator == null || world == null) {
            return false; // bez napojení zůstává čistě přímé řízení
        }
        if (distance <= ATTACK_RANGE) {
            // Dorazil na dosah – melee mikropohyb je přímý.
            if (navigatingApproach) {
                stopNavigation();
            }
            bestApproachDistance = distance;
            noProgressTicks = 0;
            approachTicks = 0;
            return false;
        }
        if (navigatingApproach) {
            // Hystereze: jednou zahájené obcházení se drží až na dosah úderu
            // (drift throttle pohyblivých cílů tlumí replány sám) – ale jen do
            // časového stropu, jinak bot u nedosažitelného cíle stojí navždy.
            if (++approachTicks > APPROACH_TIMEOUT_TICKS) {
                targetUnreachable = true;
                stopNavigation();
                return false;
            }
            navigator.navigateTo(position, PathGoal.near(targetPos.toBlockPos(), 2));
            if (navigator.navigating() || navigator.hasPath()) {
                return true;
            }
            navigatingApproach = false; // navigace to vzdala – nouzově přímo
            return false;
        }
        // Detektor marného přibližování: mimo strafovací pásmo se musí
        // nejlepší dosažená vzdálenost zlepšovat, jinak bot buší do překážky
        // (příkop, plot – spojnice volná, cesta žádná).
        if (distance < bestApproachDistance - 0.3) {
            bestApproachDistance = distance;
            noProgressTicks = 0;
        } else if (distance > ATTACK_RANGE + 1.5) {
            noProgressTicks++;
        }
        boolean blocked = !lineOfSight(position.add(0, 1.62, 0), targetPos.add(0, 1.2, 0));
        if (blocked || noProgressTicks > NO_PROGRESS_TICKS) {
            navigatingApproach = true;
            noProgressTicks = 0;
            bestApproachDistance = distance;
            navigator.navigateTo(position, PathGoal.near(targetPos.toBlockPos(), 2));
            return true;
        }
        return false;
    }

    /** Volná spojnice očí bota a trupu cíle (vzorky po půl bloku). */
    private boolean lineOfSight(Vec3 from, Vec3 to) {
        double length = from.distance(to);
        if (length < 1.0E-6) {
            return true;
        }
        Vec3 direction = to.sub(from).mul(1.0 / length);
        for (double d = 0.5; d < length; d += 0.5) {
            Vec3 point = from.add(direction.mul(d));
            BlockTraits t = world.traitsAt(point.toBlockPos());
            if (!t.noCollision() && !t.lowProfile()) {
                return false;
            }
        }
        return true;
    }

    /** Ukončí navigační režimy a vrátí řízení přímému pohybu. */
    private void stopNavigation() {
        if ((navigatingApproach || navigatingRetreat) && navigator != null) {
            navigator.stop();
        }
        navigatingApproach = false;
        navigatingRetreat = false;
    }
}
