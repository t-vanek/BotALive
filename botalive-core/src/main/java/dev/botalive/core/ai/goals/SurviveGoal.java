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
 * <p>Aktivuje se při nízkém zdraví nebo hoření. Bot sprintuje pryč od
 * nejbližšího nepřítele (bez pathfindingu – panika je přímočará), hořící bot
 * hledá vodu v okolí. Nebezpečné místo si zapamatuje. Utility roste s chybějícím
 * zdravím a klesá s odvahou – stateční boti bojují déle.</p>
 */
public final class SurviveGoal extends AbstractGoal {

    private int safeTicks;

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
        return 80 + missing * 8 + (burning ? 60 : 0);
    }

    @Override
    public void start(Bot bot) {
        BotContext ctx = ctx(bot);
        safeTicks = 0;
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

        // Hrozba: vždy nepřátelský mob, NEBO neutrální útočník (rozzuřený
        // enderman, vlk…) s čerstvou ENEMY vzpomínkou – ten v klasifikaci
        // isHostile záměrně není, ale zraněný bot před ním utíkat musí.
        long now = System.currentTimeMillis();
        Optional<TrackedEntity> threat = ctx.entities().nearest(pos, 24,
                e -> e.isHostile()
                        || (!e.isPlayer() && e.uuid() != null
                                && bot.memory().recallAbout(e.uuid()).stream()
                                        .anyMatch(r -> r.kind() == MemoryKind.ENEMY
                                                && now - r.updatedAt() < 60_000)));
        if (threat.isPresent()) {
            // Panický útěk přímo od hrozby.
            Vec3 away = pos.sub(threat.get().position()).horizontal().normalized();
            ctx.requestMove(MoveInput.of(away, true, ctx.onGround() && ctx.rng().chance(0.2)));
            ctx.humanizer().lookAlong(away);
            safeTicks = 0;
        } else {
            safeTicks++;
        }
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
