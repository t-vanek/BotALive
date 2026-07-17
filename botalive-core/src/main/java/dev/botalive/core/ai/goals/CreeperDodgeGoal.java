package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.physics.MoveInput;
import dev.botalive.core.util.Vec3;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;

import java.util.Optional;

/**
 * Úskok před creeperem – reflex, který odlišuje hráče od NPC.
 *
 * <p>Creeper na dosah výbuchu (≈3 bloky, odpaluje se) znamená okamžitý
 * sprint pryč, ať bot právě dělá cokoli. Utility je nad úrovní přežití,
 * takže úskok přebije i boj; končí, jakmile je creeper bezpečně daleko.
 * Boti se creeperům nevyhýbají úplně (lovec je může střílet z dálky) –
 * tohle řeší jen smrtící blízkost.</p>
 */
public final class CreeperDodgeGoal extends AbstractGoal {

    /** Vzdálenost, od které je creeper akutní hrozba (bloky). */
    private static final double DANGER_DIST = 4.0;
    /** Bezpečná vzdálenost, kde úskok končí. */
    private static final double SAFE_DIST = 8.0;

    private int panicTicks;

    /** Vytvoří cíl. */
    public CreeperDodgeGoal() {
        super("creeper-dodge");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (ctx.clientState().dead()) {
            return 0;
        }
        Optional<TrackedEntity> creeper = nearestCreeper(ctx, DANGER_DIST);
        // Výš než survive (ten mívá stovky jen při kritickém zdraví).
        return creeper.isPresent() ? 400 : 0;
    }

    @Override
    public void start(Bot bot) {
        panicTicks = 0;
        BotContext ctx = ctx(bot);
        ctx.navigator().stop();
        if (ctx.rng().chance(0.3)) {
            ctx.chat().say("creeper!!");
        }
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        Optional<TrackedEntity> creeper = nearestCreeper(ctx, SAFE_DIST);
        if (creeper.isEmpty() || ++panicTicks > 100) {
            panicTicks = Math.max(panicTicks, 101); // hotovo
            return;
        }
        Vec3 away = ctx.position().sub(creeper.get().position());
        ctx.requestMove(MoveInput.of(away.horizontal().normalized(), true,
                ctx.onGround() && ctx.rng().chance(0.2)));
    }

    @Override
    public boolean finished(Bot bot) {
        BotContext ctx = ctx(bot);
        return panicTicks > 100 || nearestCreeper(ctx, SAFE_DIST).isEmpty();
    }

    @Override
    public String explain(Bot bot) {
        return "CREEPER! utíkám, než to bouchne";
    }

    private Optional<TrackedEntity> nearestCreeper(BotContext ctx, double dist) {
        return ctx.entities().nearest(ctx.position(), dist,
                e -> e.type() == EntityType.CREEPER);
    }
}
