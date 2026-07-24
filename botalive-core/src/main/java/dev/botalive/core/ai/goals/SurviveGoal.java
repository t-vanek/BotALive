package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.physics.MoveInput;
import dev.botalive.core.util.Vec3;

import java.util.Map;
import java.util.Optional;

/**
 * Přežití – nejvyšší priorita, když jde do tuhého.
 *
 * <p>Aktivuje se při nízkém zdraví nebo hoření. Útěk je dvoustupňový:
 * okamžitá přímočará panika drží bota v pohybu hned (v Endu s ochranou
 * hran), a jakmile se dopočítá <b>plánovaný ústup</b>
 * ({@code PathGoal.awayFrom} – po pochozím terénu, žádné slepé kouty,
 * hrany ani láva), převezme řízení navigace. Nebezpečné místo si bot
 * zapamatuje. Utility roste s chybějícím zdravím a klesá s odvahou –
 * stateční boti bojují déle.</p>
 */
public final class SurviveGoal extends AbstractGoal {

    /** Minimální vzdálenost plánovaného ústupu od hrozby (bloky). */
    private static final int FLEE_DISTANCE = 16;
    /**
     * Kriticky nízké zdraví: panika i bez viditelné hrozby (poškození může
     * přicházet z ohně, jedu, dušení). Nad ním a bez hrozby survive ustupuje,
     * ať se bot může najíst / zotavit místo stání na místě.
     */
    private static final double CRITICAL_HEALTH = 6.0;

    private int safeTicks;
    /** Cache zuřícího neutrála (id) + odpočet drahé paměťové kontroly. */
    private int aggressorId = -1;
    private int aggressorScanCooldown;

    /** Vytvoří cíl. */
    public SurviveGoal() {
        super("survive");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (ctx.clientState().dead()) {
            return 0;
        }
        float health = ctx.clientState().health();
        var snapshot = ctx.serverView().latest();
        boolean burning = snapshot != null && (snapshot.onFire() || snapshot.inLava());

        double courage = bot.personality().trait(Trait.COURAGE);
        double threshold = 6 + (1.0 - courage) * 6; // 6–12 HP podle odvahy
        if (health > threshold && !burning) {
            return 0;
        }
        double missing = 20 - health;
        double panic = 80 + missing * 8 + (burning ? 60 : 0);
        // Hoření nebo kriticky nízké zdraví = panika vždy.
        if (burning || health <= CRITICAL_HEALTH) {
            return panic;
        }
        // Jinak (podprahové, ale ne kritické zdraví): panika jen když je poblíž
        // hrozba. „Jen" nízké zdraví BEZ hrozby nesmí držet bota v útěku na
        // místě – survive (80+) přebíjí eat, takže by se bot bez jídla nikdy
        // nezotavil: finished (health≥8) doběhne → mozek hned znovu vybere
        // survive → start() vynuluje safeTicks → stání dokola (motor smyčky
        // smrti). Bez hrozby proto uvolni řízení: bot se najde/nají/vrátí
        // k práci a zotaví se; jakmile hrozba přijde, survive se spustí znovu.
        return threatNear(ctx, bot) ? panic : 0;
    }

    /**
     * Je do 24 bloků hrozba, před kterou má smysl utíkat – hostilní mob nebo
     * zuřící neutrál z paměti? Read-only (na rozdíl od {@link #aggressorThreat}
     * necachuje aggressorId), aby ho směla volat {@link #utility}.
     *
     * @param ctx kontext bota
     * @param bot bot
     * @return {@code true} pokud je hrozba poblíž
     */
    private boolean threatNear(BotContext ctx, Bot bot) {
        Vec3 pos = ctx.position();
        if (ctx.entities().nearest(pos, 24, TrackedEntity::isHostile).isPresent()) {
            return true;
        }
        for (TrackedEntity entity : ctx.entities().nearby(pos, 24,
                e -> !e.isPlayer() && e.uuid() != null)) {
            if (recentAggressor(bot, entity)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void start(Bot bot) {
        BotContext ctx = ctx(bot);
        safeTicks = 0;
        aggressorId = -1;
        aggressorScanCooldown = 0;
        ctx.combat().disengage();
        ctx.navigator().stop();
        // Zapamatovat si nebezpečné místo.
        Vec3 pos = ctx.position();
        if (ctx.worldView() != null) {
            bot.memory().remember(MemoryKind.DANGER, ctx.worldView().worldName(),
                    (int) pos.x(), (int) pos.y(), (int) pos.z(), null,
                    Map.of("reason", "low-health"), 0.7);
        }
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        Vec3 pos = ctx.position();

        // Hrozba je i neutrál, který bota nedávno napadl (rozzuřený enderman,
        // vlk…) – ti v isHostile nefigurují, ale utéct se před nimi musí.
        Optional<TrackedEntity> threat = ctx.entities().nearest(pos, 24, TrackedEntity::isHostile);
        if (threat.isEmpty()) {
            threat = aggressorThreat(ctx, bot, pos);
        }
        if (threat.isPresent()) {
            Vec3 threatPos = threat.get().position();
            // Plánovaný ústup: awayFrom vede po pochozím terénu (obchází
            // lávu, hrany i slepé kouty, respektuje DANGER paměť). Drift
            // throttle navigace zvládá pohybující se hrozbu bez replan bouří.
            ctx.navigator().navigateTo(pos, dev.botalive.core.pathfinding.PathGoal
                    .awayFrom(threatPos.toBlockPos(), FLEE_DISTANCE));
            if (!ctx.navigator().pathReady()) {
                // Než se ústup dopočítá, drží bota v pohybu přímočará panika.
                // V Endu nesmí skončit ve voidu; v overworldu zůstává přímá –
                // slepá zatáčka podél hrany umí vrátit bota k hrozbě.
                Vec3 away = pos.sub(threatPos).horizontal().normalized();
                MoveInput flee = MoveInput.of(away, true, ctx.onGround() && ctx.rng().chance(0.2));
                // End chrání každou hranu; overworld jen smrtící pády – slepá
                // zatáčka podél malé hrany by bota vracela k hrozbě, ale útěk
                // do propasti/lávy je horší než hrozba za zády.
                flee = ctx.dimension() == dev.botalive.core.world.WorldDimension.END
                        ? dev.botalive.core.physics.EdgeGuard.apply(ctx.worldView(), pos, flee)
                        : dev.botalive.core.physics.EdgeGuard.applyLethal(ctx.worldView(), pos, flee);
                ctx.requestMove(flee);
                if (flee.direction().horizontalLength() > 1.0E-4) {
                    ctx.humanizer().lookAlong(flee.direction());
                }
            }
            safeTicks = 0;
        } else {
            safeTicks++;
        }
    }

    /**
     * Zuřící neutrál z paměti – kontrola je dražší (recallAbout na entitu),
     * proto běží po pěti ticích a nalezený útočník se drží podle id.
     */
    private Optional<TrackedEntity> aggressorThreat(BotContext ctx, Bot bot, Vec3 pos) {
        if (aggressorId >= 0) {
            Optional<TrackedEntity> cached = ctx.entities().byId(aggressorId);
            if (cached.isPresent() && cached.get().position().distance(pos) < 24) {
                return cached;
            }
            aggressorId = -1;
        }
        if (--aggressorScanCooldown > 0) {
            return Optional.empty();
        }
        aggressorScanCooldown = 5;
        for (TrackedEntity entity : ctx.entities().nearby(pos, 24,
                e -> !e.isPlayer() && e.uuid() != null)) {
            if (recentAggressor(bot, entity)) {
                aggressorId = entity.entityId();
                return Optional.of(entity);
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean finished(Bot bot) {
        BotContext ctx = ctx(bot);
        float health = ctx.clientState().health();
        var snapshot = ctx.serverView().latest();
        boolean burning = snapshot != null && (snapshot.onFire() || snapshot.inLava());
        // Konec, když je bot chvíli v bezpečí a neregeneruje-li, aspoň nehoří.
        return safeTicks > 60 && !burning && health >= 8;
    }

    @Override
    public String explain(dev.botalive.api.bot.Bot bot) {
        return "teď hlavně přežít";
    }
}
