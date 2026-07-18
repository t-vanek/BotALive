package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.memory.MemoryRecord;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.chat.PhraseCategory;
import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.util.BlockPos;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Běh pro věci po smrti – „corpse run".
 *
 * <p>Smrt zapisuje {@link MemoryKind#LOST_ITEMS} s místem úmrtí; tenhle cíl
 * ji konzumuje: po respawnu má vysokou prioritu doběhnout zpátky (dropy
 * mizí ~5 minut po smrti, takže priorita s časem klesá), na místě posbírat
 * všechno, co tam leží, a vzpomínku smazat. Beznadějné smrti (láva, void –
 * věci shořely/spadly ze světa) se neběhají vůbec.</p>
 *
 * <p>Cesta k místu smrti je záměrně dražší (DEATH paměť v pathfinderu),
 * ale ne zakázaná – bot se vrací opatrně, jako hráč.</p>
 */
public final class RecoverItemsGoal extends AbstractGoal {

    /** Do kdy má smysl běžet (despawn 5 min + rezerva na cestu). */
    private static final long DESPAWN_BUDGET_MS = 6 * 60_000L;
    /** Starší záznamy jsou mrtvé (restart, dávná smrt) – jen uklidit. */
    private static final long STALE_MS = 12 * 60_000L;
    /** Jak blízko místa smrti začíná sběr. */
    private static final int SWEEP_DISTANCE = 20;

    private enum Phase { TRAVEL, SWEEP, DONE }

    private Phase phase = Phase.TRAVEL;
    private MemoryRecord lost;
    private int sweepEmptyTicks;
    private boolean sawAnyItem;

    /** Vytvoří cíl. */
    public RecoverItemsGoal() {
        super("recover");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (ctx.worldView() == null) {
            return 0;
        }
        MemoryRecord freshest = freshestLoss(bot);
        if (freshest == null) {
            return 0;
        }
        long age = System.currentTimeMillis() - freshest.createdAt();
        if (age > DESPAWN_BUDGET_MS || hopeless(freshest)) {
            // Věci jsou pryč (čas/láva/void) – vzpomínku uklidit, ať se
            // sem mozek nevrací. Stejný vzor „úklid v utility" jako cooldowny.
            // Úklid běží PŘED world-gatem: smrt v Endu/Netheru by jinak
            // nechala záznam viset navěky (bot se do světa v despawn okně
            // nevrátí a jiný svět dřív cíl vypínal bez úklidu).
            if (age > STALE_MS || hopeless(freshest)) {
                bot.memory().forget(MemoryKind.LOST_ITEMS);
            }
            return 0;
        }
        // Jiný svět: po respawnu jinde nemá cesta smysl (do cizí dimenze se
        // v despawn okně stihnout nedá) – záznam doběhne do stale úklidu výš.
        if (!ctx.worldView().worldName().equals(freshest.world())) {
            return 0;
        }
        // Vysoká priorita hned po smrti, klesá k nule s despawn oknem.
        double urgency = 1.0 - (double) age / DESPAWN_BUDGET_MS;
        return 26 + urgency * 14;
    }

    @Override
    public void start(Bot bot) {
        BotContext ctx = ctx(bot);
        phase = Phase.TRAVEL;
        sweepEmptyTicks = 0;
        sawAnyItem = false;
        lost = freshestLoss(bot);
        if (lost != null && ctx.rng().chance(0.5)) {
            ctx.chat().sayFrom(PhraseCategory.RECOVER_RUN, null);
        }
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        if (lost == null) {
            phase = Phase.DONE;
            return;
        }
        BlockPos spot = new BlockPos(lost.x(), lost.y(), lost.z());
        switch (phase) {
            case TRAVEL -> travel(ctx, bot, spot);
            case SWEEP -> sweep(ctx, bot, spot);
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
            case TRAVEL -> "běžím si pro věci na místo, kde jsem umřel";
            case SWEEP -> "sbírám, co po mně zbylo";
            case DONE -> null;
        };
    }

    // ==================================================================

    private void travel(BotContext ctx, Bot bot, BlockPos spot) {
        if (ctx.position().toBlockPos().distanceSquared(spot)
                <= SWEEP_DISTANCE * SWEEP_DISTANCE) {
            ctx.navigator().stop();
            phase = Phase.SWEEP;
            return;
        }
        ctx.navigator().navigateTo(ctx.position(), spot);
        if (!ctx.navigator().navigating()) {
            giveUp(ctx, bot); // cesta nevede – věci oželet
        }
    }

    private void sweep(BotContext ctx, Bot bot, BlockPos spot) {
        // Sbírá se všechno v okolí místa smrti (výbava se rozletí do okolí).
        Optional<TrackedEntity> item = ctx.entities()
                .nearest(ctx.position(), 24, TrackedEntity::isItem)
                .filter(e -> e.position() != null
                        && e.position().toBlockPos().distanceSquared(spot) <= 28 * 28);
        if (item.isEmpty()) {
            if (++sweepEmptyTicks > 60) {
                finishSweep(ctx, bot);
            }
            return;
        }
        sweepEmptyTicks = 0;
        sawAnyItem = true;
        TrackedEntity target = item.get();
        ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0), target.position());
        ctx.navigator().navigateTo(ctx.position(), target.position().toBlockPos());
    }

    /** Na místě už nic neleží – hotovo (úspěch, nebo aspoň jistota). */
    private void finishSweep(BotContext ctx, Bot bot) {
        bot.memory().forget(MemoryKind.LOST_ITEMS);
        if (ctx.rng().chance(0.6)) {
            ctx.chat().sayFrom(sawAnyItem
                    ? PhraseCategory.RECOVER_OK
                    : PhraseCategory.RECOVER_FAIL, null);
        }
        phase = Phase.DONE;
    }

    /** Cesta nevede / pozdě – vzpomínku smazat, ať se bot netrápí donekonečna. */
    private void giveUp(BotContext ctx, Bot bot) {
        bot.memory().forget(MemoryKind.LOST_ITEMS);
        if (ctx.rng().chance(0.5)) {
            ctx.chat().sayFrom(PhraseCategory.RECOVER_FAIL, null);
        }
        phase = Phase.DONE;
    }

    /** Nejčerstvější záznam o ztracených věcech. */
    private static MemoryRecord freshestLoss(Bot bot) {
        List<MemoryRecord> records = bot.memory().recall(MemoryKind.LOST_ITEMS);
        MemoryRecord freshest = null;
        for (MemoryRecord record : records) {
            if (freshest == null || record.createdAt() > freshest.createdAt()) {
                freshest = record;
            }
        }
        return freshest;
    }

    /** Smrt, po které není co sbírat (věci shořely v lávě / spadly do voidu). */
    private static boolean hopeless(MemoryRecord loss) {
        String cause = loss.data().get("cause");
        if (cause == null) {
            return false;
        }
        String lower = cause.toLowerCase(Locale.ROOT);
        return lower.contains("lava") || lower.contains("láv")
                || lower.contains("out of the world") || lower.contains("void");
    }
}
