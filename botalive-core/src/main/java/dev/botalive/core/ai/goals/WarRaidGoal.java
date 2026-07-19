package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.chat.PhraseCategory;
import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.pathfinding.PathGoal;
import dev.botalive.core.physics.EdgeGuard;
import dev.botalive.core.physics.MoveInput;
import dev.botalive.core.pvp.PvpCoordinator;
import dev.botalive.core.settlement.DiplomacyService;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.WorldDimension;

import java.util.Optional;
import java.util.UUID;

/**
 * Válečný nájezd – bot vyslaný starostou táhne na náves nepřátelské vesnice
 * a bojuje s jejími členy.
 *
 * <p>Rozkaz k nájezdu vydává {@code DiplomacyService} (starosta vybírá
 * nejbojovnější členy). Nájezdník pochoduje ke shromaždišti (návsi cíle),
 * tam si vybírá výhradně <b>válečné nepřátele</b> – boty vesnice, se kterou
 * se válčí – a boj vede standardní {@code CombatController}. Obrana
 * napadených funguje zadarmo: damage event zapíše hrozbu a svolá spojence
 * přes {@code PvpCoordinator}, přesně jako u běžného PvP.</p>
 *
 * <p>Mantinely: cíle prochází {@code PvpCoordinator.mayEngage} (hlavní
 * vypínač, útoky jen na boty, spojenci nikdy) a férovostní strop útočníků.
 * Na hráče nájezd nikdy neútočí – hráč není válečný nepřítel. Vyprchá-li
 * rozkaz, dojdou-li cíle nebo je bot zraněný, nájezd končí a bota odvede
 * domů běžná mašinerie ({@code ReturnHomeGoal}, {@code SurviveGoal}).</p>
 */
public final class WarRaidGoal extends AbstractGoal {

    /** Vzdálenost od shromaždiště, od které se hledají cíle. */
    private static final double FIGHT_RADIUS = 24;

    /** Po kolika ticích bez cíle u návsi se nájezd vzdává. */
    private static final int NO_TARGET_GIVE_UP_TICKS = 200;

    /** Zdraví, pod kterým nájezdník odpadá (obranu řeší SurviveGoal). */
    private static final float RETREAT_HEALTH = 8f;

    private final DiplomacyService diplomacy;
    private final PvpCoordinator pvp;

    private enum Phase { MARCH, FIGHT, DONE }

    private Phase phase = Phase.MARCH;
    private DiplomacyService.RaidCall call;
    private UUID targetUuid;
    private boolean registered;
    private int noTargetTicks;
    private int lostTicks;
    private int cooldownTicks;

    /**
     * @param diplomacy diplomacie sídel (rozkazy k nájezdům)
     * @param pvp       PvP koordinátor (mantinely a férovost)
     */
    public WarRaidGoal(DiplomacyService diplomacy, PvpCoordinator pvp) {
        super("war-raid");
        this.diplomacy = diplomacy;
        this.pvp = pvp;
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        if (ctx.clientState().dead() || !ctx.config().combat().enabled()
                || !ctx.config().pvp().enabled()) {
            return 0;
        }
        Optional<DiplomacyService.RaidCall> pending = diplomacy.raidCall(bot.id());
        if (pending.isEmpty()
                || !pending.get().world().equals(ctx.worldView().worldName())
                || ctx.clientState().health() < RETREAT_HEALTH) {
            return 0;
        }
        double courage = bot.personality().trait(Trait.COURAGE);
        double aggression = bot.personality().trait(Trait.AGGRESSION);
        // Pod obranou vlastní kůže (PvpGoal ~28+), nad běžnou prací.
        return 24 + courage * 10 + aggression * 6;
    }

    @Override
    public void start(Bot bot) {
        BotContext ctx = ctx(bot);
        phase = Phase.MARCH;
        targetUuid = null;
        registered = false;
        noTargetTicks = 0;
        lostTicks = 0;
        call = diplomacy.raidCall(bot.id()).orElse(null);
        if (call == null) {
            phase = Phase.DONE;
            return;
        }
        if (ctx.rng().chance(0.6)) {
            ctx.chat().sayFrom(PhraseCategory.WAR_RAID_DEPART, call.targetName());
        }
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        if (phase == Phase.DONE) {
            return;
        }
        if (call == null || diplomacy.raidCall(bot.id()).isEmpty()) {
            abandon(ctx, bot, 400); // rozkaz vypršel nebo bylo uzavřeno příměří
            return;
        }
        if (ctx.clientState().health() < RETREAT_HEALTH) {
            diplomacy.clearRaidCall(bot.id());
            abandon(ctx, bot, 1200); // zraněný odpadá; útěk řeší SurviveGoal
            return;
        }
        switch (phase) {
            case MARCH -> tickMarch(ctx);
            case FIGHT -> tickFight(ctx, bot);
            default -> { }
        }
    }

    private void tickMarch(BotContext ctx) {
        BlockPos rally = call.rally();
        double distance = ctx.position().distance(
                new Vec3(rally.x() + 0.5, rally.y(), rally.z() + 0.5));
        if (distance <= FIGHT_RADIUS * 0.6) {
            ctx.navigator().stop();
            phase = Phase.FIGHT;
            return;
        }
        if (!ctx.navigator().navigating()) {
            ctx.navigator().navigateTo(ctx.position(), PathGoal.near(rally, 8));
        }
    }

    private void tickFight(BotContext ctx, Bot bot) {
        Optional<TrackedEntity> target = currentTarget(ctx);
        if (target.isEmpty()) {
            if (targetUuid != null) {
                // Cíl padl nebo utekl – odhlásit a hledat dalšího.
                if (++lostTicks > 40) {
                    if (ctx.entities().byUuid(targetUuid).isEmpty()) {
                        ctx.stats().addKill();
                        if (ctx.rng().chance(0.35)) {
                            ctx.chat().sayFrom(PhraseCategory.WAR_RAID_TAUNTS,
                                    call.targetName());
                        }
                    }
                    releaseTarget(ctx, bot);
                }
                return;
            }
            target = pickTarget(ctx, bot);
            if (target.isEmpty()) {
                if (++noTargetTicks > NO_TARGET_GIVE_UP_TICKS) {
                    diplomacy.clearRaidCall(bot.id());
                    abandon(ctx, bot, 1200); // vesnice je prázdná, jde se domů
                }
                return;
            }
            noTargetTicks = 0;
        }
        lostTicks = 0;
        TrackedEntity entity = target.get();
        if (ctx.combat().target() == null
                || ctx.combat().target().entityId() != entity.entityId()) {
            ctx.combat().engage(entity);
        }
        MoveInput move = ctx.combat().tick(ctx.position(), ctx.clientState().health(),
                ctx.onGround(), ctx.serverView().latest());
        if (move == null) {
            return; // přiblížení řídí navigace
        }
        move = ctx.dimension() == WorldDimension.END
                ? EdgeGuard.apply(ctx.worldView(), ctx.position(), move)
                : EdgeGuard.applyLethal(ctx.worldView(), ctx.position(), move);
        ctx.requestMove(move);
    }

    /** Aktuální cíl, pokud je stále v dohledu. */
    private Optional<TrackedEntity> currentTarget(BotContext ctx) {
        if (targetUuid == null) {
            return Optional.empty();
        }
        return ctx.entities().byUuid(targetUuid).filter(TrackedEntity::isPlayer);
    }

    /**
     * Vybere nejbližšího válečného nepřítele. Výhradně boti vesnice, se
     * kterou se válčí – hráči nikdy; respektuje férovostní strop.
     */
    private Optional<TrackedEntity> pickTarget(BotContext ctx, Bot bot) {
        Optional<TrackedEntity> candidate = ctx.entities()
                .nearby(ctx.position(), FIGHT_RADIUS, TrackedEntity::isPlayer)
                .stream()
                .filter(e -> e.uuid() != null)
                .filter(e -> ctx.socialGraph().isBot(e.uuid()))
                .filter(e -> diplomacy.isWarEnemy(bot.id(), e.uuid()))
                .filter(e -> pvp.mayEngage(bot, e.uuid(), false))
                .findFirst();
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        UUID uuid = candidate.get().uuid();
        if (!pvp.registerAttacker(uuid, bot.id(), false)) {
            return Optional.empty(); // na cíl už jde dost botů
        }
        targetUuid = uuid;
        registered = true;
        return candidate;
    }

    private void releaseTarget(BotContext ctx, Bot bot) {
        ctx.combat().disengage();
        if (registered && targetUuid != null) {
            pvp.unregisterAttacker(targetUuid, bot.id());
        }
        targetUuid = null;
        registered = false;
        lostTicks = 0;
    }

    private void abandon(BotContext ctx, Bot bot, int cooldown) {
        releaseTarget(ctx, bot);
        ctx.navigator().stop();
        phase = Phase.DONE;
        cooldownTicks = cooldown;
    }

    @Override
    public void stop(Bot bot) {
        BotContext ctx = ctx(bot);
        releaseTarget(ctx, bot);
        super.stop(bot);
    }

    @Override
    public boolean finished(Bot bot) {
        return phase == Phase.DONE;
    }

    @Override
    public String explain(Bot bot) {
        if (call == null) {
            return null;
        }
        return phase == Phase.MARCH
                ? "táhnu s nájezdem na vesnici " + call.targetName()
                : "bojuju v nájezdu na vesnici " + call.targetName();
    }
}
