package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.chat.PhraseCategory;
import dev.botalive.core.economy.EmploymentService;
import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.pathfinding.PathGoal;
import dev.botalive.core.physics.EdgeGuard;
import dev.botalive.core.physics.MoveInput;
import dev.botalive.core.pvp.PvpCoordinator;

import java.util.Optional;
import java.util.UUID;

/**
 * Bodyguard – najatý bot chodí se zaměstnavatelem a brání ho.
 *
 * <p>Dokud smlouva platí a zaměstnavatel je v dohledu, drží se bot poblíž
 * (stejná mechanika jako následování kamaráda). Když je zaměstnavatel
 * napaden ({@code EmploymentService.GuardAlert} z damage eventu), bodyguard
 * útočníka napadne: <b>moby vždy</b>; hráče a boty jen v mezích sekce
 * {@code pvp} ({@code PvpCoordinator.mayEngage} + férovostní strop) –
 * najmutí bodyguarda není licence k PvP, které server nepovolil.</p>
 *
 * <p>Zmizí-li zaměstnavatel z dohledu (odhlásil se, odešel daleko), bot se
 * vrací ke svému běžnému životu a znovu se připojí, až ho uvidí.</p>
 */
public final class BodyguardGoal extends AbstractGoal {

    /** Jak daleko bodyguard zaměstnavatele „vidí" (bloky). */
    private static final double SIGHT_RADIUS = 48;

    /** Vzdálenost, nad kterou bodyguard dobíhá. */
    private static final double FOLLOW_DISTANCE = 5;

    private final EmploymentService employment;
    private final PvpCoordinator pvp;

    private UUID targetUuid;
    private boolean registered;
    private int lostTicks;

    /**
     * @param employment služba najímání (smlouvy, poplachy)
     * @param pvp        PvP koordinátor (mantinely útoků na hráče/boty)
     */
    public BodyguardGoal(EmploymentService employment, PvpCoordinator pvp) {
        super("bodyguard");
        this.employment = employment;
        this.pvp = pvp;
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (ctx.clientState().dead() || !ctx.config().combat().enabled()) {
            return 0;
        }
        Optional<EmploymentService.Contract> contract = employment.contractOf(bot.id());
        if (contract.isEmpty()
                || contract.get().kind() != EmploymentService.Kind.GUARD) {
            return 0;
        }
        Optional<TrackedEntity> employer = employerEntity(ctx, contract.get());
        if (employer.isEmpty()) {
            return 0; // zaměstnavatel není v dohledu – bot žije svůj život
        }
        if (employment.guardAlert(bot.id())
                .filter(alert -> resolveAttacker(ctx, bot, alert).isPresent())
                .isPresent()) {
            return 30 + bot.personality().trait(Trait.COURAGE) * 8;
        }
        return 15 + bot.personality().trait(Trait.HELPFULNESS) * 4;
    }

    @Override
    public void start(Bot bot) {
        targetUuid = null;
        registered = false;
        lostTicks = 0;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        Optional<EmploymentService.Contract> contract = employment.contractOf(bot.id());
        if (contract.isEmpty()) {
            return; // finished() ukončí
        }
        // 1) Poplach: zaměstnavatele někdo mlátí.
        if (targetUuid == null) {
            employment.guardAlert(bot.id()).ifPresent(alert -> {
                Optional<TrackedEntity> attacker = resolveAttacker(ctx, bot, alert);
                if (attacker.isEmpty()) {
                    employment.clearGuardAlert(bot.id());
                    return;
                }
                targetUuid = attacker.get().uuid();
                if (attacker.get().isPlayer()) {
                    registered = pvp.registerAttacker(targetUuid, bot.id(), false);
                    if (!registered) {
                        targetUuid = null; // na útočníka už jde dost botů
                        return;
                    }
                }
                if (ctx.rng().chance(0.5)) {
                    ctx.chat().sayFrom(PhraseCategory.GUARD_DEFEND,
                            contract.get().employerName());
                }
            });
        }
        // 2) Boj s útočníkem.
        if (targetUuid != null) {
            Optional<TrackedEntity> target = ctx.entities().byUuid(targetUuid);
            if (target.isEmpty()) {
                if (++lostTicks > 40) {
                    releaseTarget(ctx, bot);
                    employment.clearGuardAlert(bot.id());
                }
                return;
            }
            lostTicks = 0;
            TrackedEntity entity = target.get();
            if (ctx.combat().target() == null
                    || ctx.combat().target().entityId() != entity.entityId()) {
                ctx.combat().engage(entity);
            }
            MoveInput move = ctx.combat().tick(ctx.position(),
                    ctx.clientState().health(), ctx.onGround(),
                    ctx.serverView().latest());
            if (move != null) {
                ctx.requestMove(EdgeGuard.applyLethal(
                        ctx.worldView(), ctx.position(), move));
            }
            return;
        }
        // 3) Jinak držet krok se zaměstnavatelem.
        Optional<TrackedEntity> employer = employerEntity(ctx, contract.get());
        if (employer.isEmpty()) {
            lostTicks++;
            return;
        }
        lostTicks = 0;
        TrackedEntity entity = employer.get();
        double distance = entity.position().distance(ctx.position());
        ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0),
                entity.position().add(0, 1.62, 0));
        if (distance > FOLLOW_DISTANCE) {
            ctx.navigator().navigateTo(ctx.position(),
                    PathGoal.near(entity.position().toBlockPos(), 3));
        } else {
            ctx.navigator().stop();
        }
    }

    @Override
    public void stop(Bot bot) {
        BotContext ctx = ctx(bot);
        releaseTarget(ctx, bot);
        super.stop(bot);
    }

    @Override
    public boolean finished(Bot bot) {
        Optional<EmploymentService.Contract> contract = employment.contractOf(bot.id());
        return contract.isEmpty()
                || contract.get().kind() != EmploymentService.Kind.GUARD
                || lostTicks > 200;
    }

    @Override
    public String explain(Bot bot) {
        return employment.contractOf(bot.id())
                .map(c -> targetUuid != null
                        ? "bráním zaměstnavatele " + c.employerName()
                        : "dělám bodyguarda hráči " + c.employerName())
                .orElse(null);
    }

    /** Zaměstnavatel v dohledu bodyguarda. */
    private Optional<TrackedEntity> employerEntity(BotContext ctx,
                                                   EmploymentService.Contract contract) {
        return ctx.entities().byUuid(contract.employer())
                .filter(e -> e.position().distance(ctx.position()) <= SIGHT_RADIUS);
    }

    /**
     * Útočník, na kterého bodyguard smí: moby vždy, hráče a boty jen
     * v mezích sekce {@code pvp}.
     */
    private Optional<TrackedEntity> resolveAttacker(BotContext ctx, Bot bot,
                                                    EmploymentService.GuardAlert alert) {
        Optional<TrackedEntity> attacker = alert.attacker() != null
                ? ctx.entities().byUuid(alert.attacker())
                : ctx.entities().byId(alert.attackerEntityId());
        return attacker.filter(e -> !e.isPlayer()
                || pvp.mayEngage(bot, e.uuid(), false));
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
}
