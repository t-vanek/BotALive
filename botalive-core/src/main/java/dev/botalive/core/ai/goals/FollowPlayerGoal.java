package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.entity.TrackedEntity;

import java.util.Optional;

/**
 * Následování přátel – bot se drží poblíž hráče, kterého má rád.
 *
 * <p>Kandidáti jsou hráči vedení v paměti jako {@link MemoryKind#FRIEND}
 * s dostatečnou důležitostí (přátelství roste opakovaným kontaktem přes
 * {@link SocializeGoal}). Utility roste s ochotou pomáhat. Cíl se také
 * používá jako vynucený přes {@code /botalive goal <bot> follow}.</p>
 */
public final class FollowPlayerGoal extends AbstractGoal {

    /** Práh důležitosti přátelství pro spontánní následování. */
    private static final double FRIENDSHIP_THRESHOLD = 0.5;

    private int lostTicks;

    /** Vytvoří cíl. */
    public FollowPlayerGoal() {
        super("follow");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        Optional<TrackedEntity> friend = findFriend(ctx, bot, FRIENDSHIP_THRESHOLD);
        if (friend.isEmpty()) {
            return 0;
        }
        double helpfulness = bot.personality().trait(Trait.HELPFULNESS);
        return 4 + helpfulness * 14;
    }

    @Override
    public void start(Bot bot) {
        lostTicks = 0;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        // Při vynuceném cíli následuje kohokoli poblíž, jinak jen přátele.
        Optional<TrackedEntity> friend = findFriend(ctx, bot, FRIENDSHIP_THRESHOLD);
        if (friend.isEmpty()) {
            friend = ctx.entities().nearest(ctx.position(), 32, TrackedEntity::isPlayer);
        }
        if (friend.isEmpty()) {
            lostTicks++;
            return;
        }
        lostTicks = 0;
        TrackedEntity target = friend.get();
        double distance = target.position().distance(ctx.position());
        ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0), target.position().add(0, 1.62, 0));
        if (distance > 4) {
            // Cíl „do okruhu 3" místo přesného bloku: cesta legitimně končí
            // kousek od hráče (jako by šel člověk) a drift throttle navigace
            // ji při pohybu hráče dojíždí místo replánu při každém kroku.
            ctx.navigator().navigateTo(ctx.position(), dev.botalive.core.pathfinding.PathGoal
                    .near(target.position().toBlockPos(), 3));
        } else {
            ctx.navigator().stop();
        }
    }

    @Override
    public boolean finished(Bot bot) {
        return lostTicks > 100;
    }

    /** Najde poblíž hráče, který je dostatečně velký přítel. */
    private Optional<TrackedEntity> findFriend(BotContext ctx, Bot bot, double threshold) {
        return ctx.entities().nearby(ctx.position(), 32, TrackedEntity::isPlayer).stream()
                .filter(player -> player.uuid() != null
                        && bot.memory().recallAbout(player.uuid()).stream()
                                .anyMatch(r -> r.kind() == MemoryKind.FRIEND
                                        && r.importance() >= threshold))
                .findFirst();
    }
}
