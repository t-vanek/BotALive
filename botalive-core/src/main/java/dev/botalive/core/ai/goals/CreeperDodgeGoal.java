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
        // Zapínací práh: creeper na dosah výbuchu (DANGER_DIST). Výš než survive
        // (ten mívá stovky jen při kritickém zdraví).
        if (nearestCreeper(ctx, DANGER_DIST).isPresent()) {
            return 400;
        }
        // Vypínací práh je dál (SAFE_DIST): jednou spuštěný úskok (panicTicks
        // běží) drží utilitu kladnou, dokud se bot nedostane mimo dosah exploze
        // (~7 bloků). Bez toho by ho mozek zahodil hned ve 4 blocích – utilitu 0
        // ruší PŘED finished(), takže zamýšlená hystereze 4→8 nikdy nezabrala
        // a bot přestal utíkat ještě v dosahu výbuchu (a kmital na hranici 4 bl.).
        if (panicTicks > 0 && panicTicks <= 100
                && nearestCreeper(ctx, SAFE_DIST).isPresent()) {
            return 400;
        }
        return 0;
    }

    @Override
    public void start(Bot bot) {
        panicTicks = 0;
        BotContext ctx = ctx(bot);
        ctx.navigator().stop();
        if (ctx.rng().chance(0.3)) {
            ctx.chat().sayUrgent(dev.botalive.core.chat.PhraseCategory.MOB_WARNING, null);
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
