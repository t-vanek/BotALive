package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.chat.PhraseCategory;

/**
 * Zahálení – výchozí cíl, když nic jiného nedává smysl.
 *
 * <p>Bot stojí, rozhlíží se (mikro-chování humanizeru), občas prohodí hlášku.
 * Utility je konstantní baseline 1, takže ho přebije cokoli smysluplného.</p>
 */
public final class IdleGoal extends AbstractGoal {

    private int chatterCooldown = 1200;

    /** Vytvoří cíl. */
    public IdleGoal() {
        super("idle");
    }

    @Override
    public double utility(Bot bot) {
        return 1.0;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        // Občasná spontánní hláška – hlavně u společenských botů.
        if (--chatterCooldown <= 0) {
            chatterCooldown = ctx.rng().rangeInt(1200, 4800);
            double sociability = bot.personality().trait(Trait.SOCIABILITY);
            if (ctx.rng().chance(sociability * 0.35)) {
                ctx.chat().sayFrom(PhraseCategory.IDLE_CHATTER, null);
            }
        }
    }

    @Override
    public String explain(dev.botalive.api.bot.Bot bot) {
        return "ale nic, jen tak odpočívám";
    }
}
