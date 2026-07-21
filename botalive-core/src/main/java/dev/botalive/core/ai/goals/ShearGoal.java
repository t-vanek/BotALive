package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.bot.ServerSideView;
import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.pathfinding.PathGoal;
import dev.botalive.core.util.Vec3;

import org.bukkit.Material;

import java.util.ArrayDeque;
import java.util.Optional;
import java.util.UUID;

/**
 * Stříhání ovcí – vlna na postel bez zabíjení.
 *
 * <p>Bot s nůžkami, který ještě nemá postel ani vlnu, dojde k ovci a ostříhá
 * ji (drží nůžky, klikne na ni – stejný paket jako hráč). Ovce přežije a vlna
 * doroste, takže je to opakovatelný zdroj – na rozdíl od dřívějška, kdy vlna
 * padala jen ze <b>zabíjení</b> ovcí při lovu, a v biomu, kde bot ovce nelovil,
 * postel (a ambice „útulný domov") nikdy nevznikla. Vlnu posbírá
 * {@code CollectItemsGoal}, postel dorobí {@code CraftGoal}.</p>
 */
public final class ShearGoal extends AbstractGoal {

    private enum Phase { FIND, APPROACH, SHEAR, DONE }

    /** Poloměr hledání ovcí (bloky). */
    private static final int SCAN_RADIUS = 16;
    /** Kolik vlny stačí (postel chce 3). */
    private static final int WOOL_TARGET = 3;
    /** Strop pokusů v jedné seanci. */
    private static final int MAX_ATTEMPTS = 8;

    private Phase phase = Phase.DONE;
    private final ArrayDeque<UUID> targets = new ArrayDeque<>();
    private UUID currentUuid;
    private int currentEntityId;
    private int attempts;
    private int waitTicks;
    private int cooldownTicks;

    /** Vytvoří cíl. */
    public ShearGoal() {
        super("shear");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        if (outsideOverworld(ctx) || ctx.worldView() == null) {
            return 0;
        }
        ServerSideView.Snapshot snapshot = ctx.serverView().latest();
        if (snapshot == null || !snapshot.hasItem(m -> m == Material.SHEARS)) {
            return 0; // bez nůžek se nestříhá
        }
        // Stříhá se, jen dokud bot nemá dost vlny ani postel (jinak netřeba).
        if (hasBed(snapshot) || woolCount(snapshot) >= WOOL_TARGET) {
            return 0;
        }
        if (ctx.entities().nearest(ctx.position(), SCAN_RADIUS, TrackedEntity::isSheep).isEmpty()) {
            return 0;
        }
        double patience = bot.personality().trait(Trait.PATIENCE);
        double helpfulness = bot.personality().trait(Trait.HELPFULNESS);
        return 5 + patience * 4 + helpfulness * 2; // klidná pochůzka za vlnou
    }

    @Override
    public void start(Bot bot) {
        phase = Phase.FIND;
        targets.clear();
        currentUuid = null;
        attempts = 0;
        waitTicks = 0;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        switch (phase) {
            case FIND -> tickFind(ctx);
            case APPROACH -> tickApproach(ctx);
            case SHEAR -> tickShear(ctx);
            case DONE -> {
            }
        }
    }

    private void tickFind(BotContext ctx) {
        targets.clear();
        for (TrackedEntity sheep : ctx.entities().nearby(ctx.position(), SCAN_RADIUS,
                e -> e.isSheep() && e.uuid() != null)) {
            targets.add(sheep.uuid());
        }
        if (!nextTarget(ctx)) {
            finish(2400);
        }
    }

    private void tickApproach(BotContext ctx) {
        Optional<TrackedEntity> sheep = tracked(ctx);
        if (sheep.isEmpty()) {
            skipTarget(ctx);
            return;
        }
        Vec3 pos = sheep.get().position();
        ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0), pos.add(0, 0.5, 0));
        if (pos.distanceSquared(ctx.position()) > 2.4 * 2.4) {
            ctx.navigator().navigateTo(ctx.position(), PathGoal.near(pos.toBlockPos(), 1));
            if (!ctx.navigator().navigating()) {
                skipTarget(ctx);
            }
            return;
        }
        ctx.navigator().stop();
        waitTicks = ctx.rng().rangeInt(4, 10);
        phase = Phase.SHEAR;
    }

    private void tickShear(BotContext ctx) {
        if (--waitTicks > 0) {
            return;
        }
        Optional<TrackedEntity> sheep = tracked(ctx);
        ServerSideView.Snapshot snapshot = ctx.serverView().latest();
        if (sheep.isEmpty() || snapshot == null
                || !ctx.inventory().equipMatching(snapshot, m -> m == Material.SHEARS)) {
            if (snapshot != null && !snapshot.hasItem(m -> m == Material.SHEARS)) {
                finish(1800); // nůžky se rozbily/došly
                return;
            }
            skipTarget(ctx);
            return;
        }
        if (sheep.get().position().distanceSquared(ctx.position()) > 3.0 * 3.0) {
            phase = Phase.APPROACH; // ovce popošla
            return;
        }
        ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0),
                sheep.get().position().add(0, 0.5, 0));
        ctx.actions().interactEntity(currentEntityId);
        attempts++;
        // Dost vlny, nebo strop pokusů → hotovo (vlnu sesbírá CollectItemsGoal).
        if (woolCount(snapshot) >= WOOL_TARGET || attempts >= MAX_ATTEMPTS
                || !nextTarget(ctx)) {
            if (ctx.rng().chance(0.4)) {
                ctx.chat().say("stříhám ovce, bude vlna na postel");
            }
            finish(ctx.rng().rangeInt(2400, 4800));
        }
    }

    private boolean nextTarget(BotContext ctx) {
        UUID next = targets.poll();
        if (next == null) {
            return false;
        }
        currentUuid = next;
        Optional<TrackedEntity> sheep = ctx.entities().byUuid(next);
        if (sheep.isEmpty()) {
            return nextTarget(ctx);
        }
        currentEntityId = sheep.get().entityId();
        phase = Phase.APPROACH;
        return true;
    }

    private void skipTarget(BotContext ctx) {
        if (!nextTarget(ctx)) {
            finish(2400);
        }
    }

    private Optional<TrackedEntity> tracked(BotContext ctx) {
        return currentUuid == null ? Optional.empty() : ctx.entities().byUuid(currentUuid);
    }

    private void finish(int cooldown) {
        cooldownTicks = cooldown;
        phase = Phase.DONE;
    }

    @Override
    public void stop(Bot bot) {
        ctx(bot).navigator().stop();
    }

    @Override
    public boolean finished(Bot bot) {
        return phase == Phase.DONE;
    }

    private static boolean hasBed(ServerSideView.Snapshot snapshot) {
        return snapshot.hasItem(m -> m.name().endsWith("_BED"));
    }

    /** Součet kusů vlny v batohu (hotbar + hlavní inventář). */
    private static int woolCount(ServerSideView.Snapshot snapshot) {
        int total = 0;
        for (int i = 0; i < snapshot.hotbar().length; i++) {
            if (snapshot.hotbar()[i] != null && snapshot.hotbar()[i].name().endsWith("_WOOL")) {
                total += snapshot.hotbarCounts()[i];
            }
        }
        for (int i = 0; i < snapshot.mainInventory().length; i++) {
            if (snapshot.mainInventory()[i] != null
                    && snapshot.mainInventory()[i].name().endsWith("_WOOL")) {
                total += snapshot.mainCounts()[i];
            }
        }
        return total;
    }

    @Override
    public String explain(Bot bot) {
        return "stříhám ovce kvůli vlně na postel";
    }
}
