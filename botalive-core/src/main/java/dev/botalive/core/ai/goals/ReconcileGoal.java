package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.chat.PhraseCategory;
import dev.botalive.core.inventory.InventoryHelper;
import dev.botalive.core.social.CrimeLog;
import dev.botalive.core.social.SocialGraph;
import dev.botalive.core.util.BlockPos;
import org.bukkit.Material;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Usmíření – slušný zloděj nese oběti dar a prosí o odpuštění.
 *
 * <p>Roztržka byla dosud jednosměrka: krádež → zášť → feud → odchod
 * z vesnice, a zpět nevedla žádná cesta. Ochotný bot s čerstvým vlastním
 * proviněním (kniha zločinů ví, kdo ho odhalil) teď oběti donese pár věcí
 * jako splátku. Jestli oběť dar přijme, rozhodne její povaha (trpělivost,
 * ochota): přijetí srazí zášť hluboko pod práh roztržky – feud i vesnický
 * blok zmizí, jizva zůstává. Odmítnutý dar druhý pokus nedostane; co bylo
 * předáno, je předáno. Drama tak dostává druhé dějství.</p>
 */
public final class ReconcileGoal extends AbstractGoal {

    /** Kolik kusů daru zloděj upustí. */
    private static final int GIFT_COUNT = 3;
    /** Zbytková zášť po přijatém usmíření (jizva, ne feud). */
    private static final double APPEASED_IMPORTANCE = 0.25;
    /** Jak daleko je bot ochoten jít se omluvit (bloky). */
    private static final double MAX_TRAVEL = 128;

    private enum Phase { APPROACH, GIVE, DONE }

    private final SocialGraph graph;

    private Phase phase = Phase.APPROACH;
    private CrimeLog.Amends amends;
    private UUID victimId;
    private boolean apologized;
    private int given;
    private int giveTicks;
    private int travelTicks;
    private int cooldownTicks;

    /**
     * @param graph sociální adresář (oběť musí být bot)
     */
    public ReconcileGoal(SocialGraph graph) {
        super("reconcile");
        this.graph = graph;
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        double helpfulness = bot.personality().trait(Trait.HELPFULNESS);
        if (helpfulness < 0.4) {
            return 0; // nekajícní svědomí neřeší
        }
        if (findAmends(ctx, bot).isEmpty() || !hasGift(ctx)) {
            return 0;
        }
        return 7 + helpfulness * 8;
    }

    @Override
    public void start(Bot bot) {
        BotContext ctx = ctx(bot);
        phase = Phase.APPROACH;
        apologized = false;
        given = 0;
        giveTicks = 0;
        travelTicks = 0;
        amends = findAmends(ctx, bot).orElse(null);
        victimId = amends == null ? null : amends.victim();
        if (amends == null) {
            cooldownTicks = 1200;
            phase = Phase.DONE;
        }
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        if (phase == Phase.DONE || amends == null) {
            phase = Phase.DONE;
            return;
        }
        Optional<Bot> victim = graph.bot(victimId);
        if (victim.isEmpty()) {
            cooldownTicks = 2400; // oběť zmizela ze světa – zkusit jindy
            phase = Phase.DONE;
            return;
        }
        BlockPos victimPos = positionOf(ctx, victim.get());
        if (victimPos == null) {
            cooldownTicks = 2400;
            phase = Phase.DONE;
            return;
        }
        switch (phase) {
            case APPROACH -> approach(ctx, bot, victim.get(), victimPos);
            case GIVE -> give(ctx, bot, victim.get(), victimPos);
            case DONE -> {
            }
        }
    }

    @Override
    public boolean finished(Bot bot) {
        return phase == Phase.DONE;
    }

    @Override
    public String explain(Bot bot) {
        return switch (phase) {
            case APPROACH -> "jdu se omluvit za tu krádež";
            case GIVE -> "nesu dar na usmířenou";
            case DONE -> null;
        };
    }

    // ==================================================================

    private void approach(BotContext ctx, Bot bot, Bot victim, BlockPos victimPos) {
        if (++travelTicks > 1600) {
            cooldownTicks = 2400; // dnes to nevyšlo, křivda zůstává otevřená
            phase = Phase.DONE;
            return;
        }
        double distSq = ctx.position().toBlockPos().distanceSquared(victimPos);
        if (distSq <= 3 * 3) {
            ctx.navigator().stop();
            phase = Phase.GIVE;
            return;
        }
        ctx.navigator().navigateTo(ctx.position(), victimPos);
        if (!ctx.navigator().navigating()) {
            cooldownTicks = 2400;
            phase = Phase.DONE;
        }
    }

    private void give(BotContext ctx, Bot bot, Bot victim, BlockPos victimPos) {
        ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0),
                victimPos.center().add(0, 1.5, 0));
        if (!apologized) {
            apologized = true;
            ctx.chat().sayFrom(PhraseCategory.RECONCILE_OFFER, victim.name());
        }
        if (--giveTicks > 0) {
            return;
        }
        if (given < GIFT_COUNT && equipGift(ctx)) {
            ctx.actions().dropItem();
            given++;
            giveTicks = ctx.rng().rangeInt(8, 16);
            return;
        }
        resolve(ctx, bot, victim);
    }

    /** Dar předán – oběť se rozhodne podle své povahy, křivda se uzavírá. */
    private void resolve(BotContext ctx, Bot bot, Bot victim) {
        ctx.crimeLog().settleAmends(amends);
        if (given > 0) {
            double patience = victim.personality().trait(Trait.PATIENCE);
            double helpfulness = victim.personality().trait(Trait.HELPFULNESS);
            boolean accepted = ctx.rng().chance(0.35 + patience * 0.4 + helpfulness * 0.25);
            BotContext victimCtx = BotContext.of(victim);
            if (accepted) {
                // Zášť opadá pod práh roztržky – feud i vesnický blok mizí,
                // jizva (nízká ENEMY vzpomínka) zůstává.
                BlockPos pos = ctx.position().toBlockPos();
                victim.memory().forgetIf(MemoryKind.ENEMY,
                        r -> bot.id().equals(r.subject()));
                victim.memory().remember(MemoryKind.ENEMY,
                        ctx.worldView() == null ? null : ctx.worldView().worldName(),
                        pos.x(), pos.y(), pos.z(), bot.id(),
                        Map.of("via", "reconciled"), APPEASED_IMPORTANCE);
                victimCtx.chat().sayFrom(PhraseCategory.RECONCILE_ACCEPT, bot.name());
            } else {
                victimCtx.chat().sayFrom(PhraseCategory.RECONCILE_REJECT, bot.name());
            }
        }
        cooldownTicks = 6000;
        phase = Phase.DONE;
    }

    /** Křivda s obětí-botem v dosahu (jinak nemá cesta smysl). */
    private Optional<CrimeLog.Amends> findAmends(BotContext ctx, Bot bot) {
        return ctx.crimeLog().pendingAmends(bot.id()).filter(candidate -> {
            Optional<Bot> victim = graph == null
                    ? Optional.empty() : graph.bot(candidate.victim());
            if (victim.isEmpty()) {
                return false;
            }
            BlockPos victimPos = positionOf(ctx, victim.get());
            return victimPos != null
                    && victimPos.distanceSquared(ctx.position().toBlockPos())
                    <= MAX_TRAVEL * MAX_TRAVEL;
        });
    }

    /** Pozice oběti, jen je-li ve stejném světě jako bot (snapshot je thread-safe). */
    private BlockPos positionOf(BotContext ctx, Bot victim) {
        var snapshot = victim.snapshot();
        if (snapshot == null || snapshot.worldName() == null || ctx.worldView() == null
                || !snapshot.worldName().equals(ctx.worldView().worldName())) {
            return null;
        }
        return new BlockPos((int) Math.floor(snapshot.x()),
                (int) Math.floor(snapshot.y()), (int) Math.floor(snapshot.z()));
    }

    /** Má bot čím se udobřit? (jídlo, uhlí, železo) */
    private boolean hasGift(BotContext ctx) {
        var snapshot = ctx.serverView().latest();
        return snapshot != null && snapshot.hasItem(m -> InventoryHelper.isFood(m)
                || m == Material.COAL || m == Material.IRON_INGOT);
    }

    /** Vezme do ruky další kus daru. */
    private boolean equipGift(BotContext ctx) {
        var snapshot = ctx.serverView().latest();
        if (snapshot == null) {
            return false;
        }
        return ctx.inventory().equipFood(snapshot)
                || ctx.inventory().equipItem(snapshot, Material.COAL)
                || ctx.inventory().equipItem(snapshot, Material.IRON_INGOT);
    }
}
