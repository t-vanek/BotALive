package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.bot.ServerSideView;
import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.inventory.InventoryHelper;
import dev.botalive.core.util.Vec3;

import java.util.Optional;
import dev.botalive.core.pathfinding.PathGoal;

/**
 * Sdílení přebytků – ochotný bot rozdává jídlo lidem kolem sebe.
 *
 * <p>Má-li bot jídla víc než dost a poblíž je hráč či jiný bot (kamarádům
 * z paměti dává přednost), dojde k němu, podívá se na něj a upustí pár kusů
 * jídla – přesně jako hráč, který kamarádovi hodí steak. Interakci si uloží
 * jako {@link MemoryKind#FRIEND}, takže sdílení přátelství prohlubuje.
 * Ochota roste s rysem {@code HELPFULNESS}; večer boti sdílejí nejvíc
 * (denní rytmus).</p>
 */
public final class ShareGoal extends AbstractGoal {

    /** Kolik kusů jídla je „přebytek" (pod tím si bot nechává vše). */
    private static final int SURPLUS_THRESHOLD = 8;
    /** Kolik kusů bot upustí. */
    private static final int GIFT_COUNT = 3;

    private enum Phase { APPROACH, GIVE, DONE }

    private Phase phase = Phase.APPROACH;
    private TrackedEntity target;
    private int cooldownTicks;
    private int giveTicks;
    private int given;
    private int approachTicks;
    /** Adresná prosba z chatu: konkrétní materiály (null = režim jídla). */
    private java.util.List<org.bukkit.Material> requestedItems;

    /** Vytvoří cíl. */
    public ShareGoal() {
        super("share");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        double helpfulness = bot.personality().trait(Trait.HELPFULNESS);
        if (helpfulness < 0.35) {
            return 0; // sobci nerozdávají
        }
        if (countFood(ctx.serverView().latest()) < SURPLUS_THRESHOLD) {
            return 0;
        }
        Optional<TrackedEntity> nearest = ctx.entities()
                .nearest(ctx.position(), 12, TrackedEntity::isPlayer);
        if (nearest.isEmpty()) {
            return 0;
        }
        boolean friend = isFriend(bot, nearest.get());
        // Cizím dávají jen hodně ochotní; kamarádům skoro každý ochotný.
        if (!friend && helpfulness < 0.65) {
            return 0;
        }
        return 4 + helpfulness * 9 + (friend ? 4 : 0);
    }

    @Override
    public void start(Bot bot) {
        BotContext ctx = ctx(bot);
        phase = Phase.APPROACH;
        given = 0;
        giveTicks = 0;
        approachTicks = 0;
        requestedItems = null;
        // Adresná prosba z chatu má přednost: jde se za prosícím a nese se,
        // o co si řekl (prázdné materiály = jídlo).
        var request = ctx.takeShareRequest();
        if (request != null) {
            target = ctx.entities().byUuid(request.requester()).orElse(null);
            if (!request.foodOnly()) {
                requestedItems = request.materials();
            }
            if (target != null) {
                return;
            }
        }
        target = ctx.entities().nearest(ctx.position(), 12, TrackedEntity::isPlayer)
                .orElse(null);
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        if (target == null || phase == Phase.DONE) {
            finish(ctx);
            return;
        }
        Vec3 targetPos = target.position();
        if (targetPos == null) {
            finish(ctx);
            return;
        }
        switch (phase) {
            case APPROACH -> {
                double distSq = ctx.position().distanceSquared(targetPos);
                if (distSq <= 3 * 3) {
                    ctx.navigator().stop();
                    phase = Phase.GIVE;
                    return;
                }
                if (++approachTicks > 200) {
                    finish(ctx); // utekl – nehonit přes půl mapy
                    return;
                }
                ctx.navigator().navigateTo(ctx.position(), PathGoal.near(targetPos.toBlockPos(), 2));
                if (!ctx.navigator().navigating()) {
                    finish(ctx);
                }
            }
            case GIVE -> {
                // Dívat se na obdarovaného a v lidském tempu upouštět dary.
                ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0),
                        targetPos.add(0, 1.5, 0));
                if (--giveTicks > 0) {
                    return;
                }
                if (given >= GIFT_COUNT || !equipGift(ctx)) {
                    onShared(ctx, bot);
                    return;
                }
                ctx.actions().dropItem();
                given++;
                giveTicks = ctx.rng().rangeInt(8, 16);
            }
            case DONE -> finish(ctx);
        }
    }

    @Override
    public void stop(Bot bot) {
        target = null;
        super.stop(bot);
    }

    @Override
    public boolean finished(Bot bot) {
        return phase == Phase.DONE;
    }

    @Override
    public String explain(Bot bot) {
        return phase == Phase.APPROACH
                ? "nesu kamarádovi něco k jídlu"
                : "rozdávám jídlo, mám ho dost";
    }

    // ==================================================================

    /** Vezme do ruky další dar: vyžádaný materiál, jinak jídlo. */
    private boolean equipGift(BotContext ctx) {
        ServerSideView.Snapshot snapshot = ctx.serverView().latest();
        if (snapshot == null) {
            return false;
        }
        if (requestedItems != null) {
            for (org.bukkit.Material material : requestedItems) {
                if (ctx.inventory().equipItem(snapshot, material)) {
                    return true;
                }
            }
            return false;
        }
        return ctx.inventory().equipFood(snapshot);
    }

    /** Po rozdání: přátelství, hláška, dlouhý cooldown. */
    private void onShared(BotContext ctx, Bot bot) {
        if (given > 0 && target != null) {
            // Dobrý skutek formuje povahu: pomáhání se stává návykem.
            ctx.gainExperience(dev.botalive.core.personality.PersonalityEvolution
                    .BotExperience.SHARE_GIVEN);
            if (ctx.worldView() != null) {
                Vec3 pos = target.position();
                bot.memory().remember(MemoryKind.FRIEND, ctx.worldView().worldName(),
                        (int) pos.x(), (int) pos.y(), (int) pos.z(), target.uuid(),
                        java.util.Map.of("via", "gift"), 0.6);
            }
            if (ctx.rng().chance(0.6)) {
                ctx.chat().sayFrom(dev.botalive.core.chat.PhraseCategory.GIVE_ACCEPT, null);
            }
        }
        finish(ctx);
    }

    private void finish(BotContext ctx) {
        phase = Phase.DONE;
        cooldownTicks = 6000; // rozdávat tak jednou za ~5 minut
        ctx.navigator().stop();
    }

    /** Je protistrana kamarád z paměti? */
    private boolean isFriend(Bot bot, TrackedEntity entity) {
        return bot.memory().recallAbout(entity.uuid()).stream()
                .anyMatch(record -> record.kind() == MemoryKind.FRIEND);
    }

    /** Počet kusů jídla v inventáři (hotbar přesně, hlavní konzervativně). */
    private static int countFood(ServerSideView.Snapshot snapshot) {
        if (snapshot == null) {
            return 0;
        }
        int count = 0;
        var hotbar = snapshot.hotbar();
        int[] counts = snapshot.hotbarCounts();
        for (int i = 0; i < hotbar.length; i++) {
            if (hotbar[i] != null && InventoryHelper.isFood(hotbar[i])) {
                count += counts != null && i < counts.length ? Math.max(counts[i], 1) : 1;
            }
        }
        for (var material : snapshot.mainInventory()) {
            if (material != null && InventoryHelper.isFood(material)) {
                count += 4;
            }
        }
        return count;
    }
}
