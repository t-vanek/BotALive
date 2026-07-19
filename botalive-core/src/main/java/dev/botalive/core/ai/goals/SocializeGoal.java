package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.chat.PhraseCategory;
import dev.botalive.core.entity.TrackedEntity;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import dev.botalive.core.pathfinding.PathGoal;

/**
 * Sociální chování – bot vyhledá hráče (nebo jiného bota), přijde k němu,
 * dívá se na něj a pozdraví.
 *
 * <p>Po interakci si protistranu zapamatuje jako {@link MemoryKind#FRIEND}
 * (s nízkou důležitostí – přátelství roste opakovaným kontaktem) a má
 * cooldown, aby hráče neobtěžoval.</p>
 */
public final class SocializeGoal extends AbstractGoal {

    private UUID targetUuid;
    private int lingerTicks;
    private boolean greeted;
    private int cooldownTicks;

    /** Vytvoří cíl. */
    public SocializeGoal() {
        super("socialize");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            // Cooldown se odpočítává rytmem rozhodování mozku.
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        double sociability = bot.personality().trait(Trait.SOCIABILITY);
        if (sociability < 0.25) {
            return 0; // samotáři se nedruží
        }
        Optional<TrackedEntity> player = ctx.entities()
                .nearest(ctx.position(), 24, TrackedEntity::isPlayer);
        return player.isEmpty() ? 0 : 6 + sociability * 16;
    }

    @Override
    public void start(Bot bot) {
        targetUuid = null;
        lingerTicks = 0;
        greeted = false;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        Optional<TrackedEntity> target = targetUuid != null
                ? ctx.entities().byUuid(targetUuid)
                : ctx.entities().nearest(ctx.position(), 24, TrackedEntity::isPlayer);
        if (target.isEmpty()) {
            lingerTicks = 200; // cíl zmizel → ukončit
            return;
        }
        TrackedEntity player = target.get();
        targetUuid = player.uuid();

        double distance = player.position().distance(ctx.position());
        ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0), player.position().add(0, 1.62, 0));

        if (distance > 3.5) {
            ctx.navigator().navigateTo(ctx.position(), PathGoal.near(player.position().toBlockPos(), 2));
            return;
        }
        ctx.navigator().stop();

        if (!greeted) {
            greeted = true;
            ctx.chat().sayFrom(PhraseCategory.MEET_PLAYER, resolveName(ctx, player));
            // Kontakt posiluje přátelství.
            if (player.uuid() != null && ctx.worldView() != null) {
                bot.memory().remember(MemoryKind.FRIEND, ctx.worldView().worldName(),
                        (int) player.position().x(), (int) player.position().y(),
                        (int) player.position().z(), player.uuid(), Map.of(), 0.3);
            }
            // Drby: boti si při pokecu vymění, co kdo zažil (vesnice, doly,
            // nebezpečí, pomluvy) – vědění přestává být ostrovní. Jen bot↔bot;
            // hráči paměť nemají.
            var graph = ctx.socialGraph();
            String gossipName = resolveName(ctx, player);
            if (graph != null && player.uuid() != null && gossipName != null
                    && graph.exchangeGossip(bot, player.uuid(), ctx.rng())
                    && ctx.rng().chance(0.35)) {
                ctx.chat().sayFrom(PhraseCategory.GOSSIP, gossipName);
            }
        }
        lingerTicks++;
    }

    @Override
    public boolean finished(Bot bot) {
        BotContext ctx = ctx(bot);
        // Poklábosit ~5–15 s, pak jít po svém + nasadit cooldown.
        int lingerLimit = 100 + (int) (bot.personality().trait(Trait.SOCIABILITY) * 200);
        if (lingerTicks > lingerLimit) {
            cooldownTicks = ctx.rng().rangeInt(1200, 3600);
            return true;
        }
        return false;
    }

    /** Zjistí jméno hráče přes Bukkit (bez blokování – jen cache serveru). */
    private String resolveName(BotContext ctx, TrackedEntity player) {
        if (player.uuid() == null) {
            return null;
        }
        org.bukkit.entity.Player bukkit = org.bukkit.Bukkit.getPlayer(player.uuid());
        return bukkit != null ? bukkit.getName() : null;
    }
}
