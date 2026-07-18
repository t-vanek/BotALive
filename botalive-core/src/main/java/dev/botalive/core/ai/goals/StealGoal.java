package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.ai.BotNeeds;
import dev.botalive.core.station.ChestStation;
import dev.botalive.core.util.BlockPos;
import org.bukkit.Material;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Krádež z beznadějе – hladový bot bez prostředků si „půjčí" z cizí truhly.
 *
 * <p>Spouští se jen v nouzi ({@link BotNeeds#starving} nebo
 * {@link BotNeeds#destitute}): bot si vzpomene na truhlu (nebo ji najde
 * v okolí), dojde k ní, otevře ji jako hráč a vezme si jen to, co teď
 * potřebuje – pár jídel, případně jeden nástroj a trochu materiálu.
 * Slušní boti se pak omluví, chamtiví ne. Vypínatelné přes
 * {@code ai.desperation}.</p>
 */
public final class StealGoal extends AbstractGoal {

    private enum Phase { FIND, GO, OPEN, LOOT, DONE }

    private final ChestStation containers;

    private Phase phase = Phase.FIND;
    private BlockPos chest;
    private CompletableFuture<Integer> loot;
    private int waitTicks;
    private int cooldownTicks;
    private int taken;
    /** Sken přes studenou chunk cache (po teleportu) chvíli opakovat. */
    private final ScanRetry scanRetry = new ScanRetry(3, 25);

    /**
     * @param containers sdílená stanice truhel
     */
    public StealGoal(ChestStation containers) {
        super("steal");
        this.containers = containers;
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        if (!ctx.config().ai().desperation()) {
            return 0;
        }
        BotNeeds needs = BotNeeds.assess(ctx.serverView().latest());
        boolean starving = needs.starving(ctx.clientState().food());
        boolean destitute = needs.destitute();
        if (!starving && !destitute) {
            return 0;
        }
        // Hlad je silnější motiv než chudoba; chamtivost snižuje zábrany.
        double greed = bot.personality().trait(Trait.GREED);
        double base = starving ? 30 : 12;
        return base + greed * 8;
    }

    @Override
    public void start(Bot bot) {
        phase = Phase.FIND;
        chest = null;
        loot = null;
        taken = 0;
        scanRetry.reset();
        // Spolupráce před zločinem: nejdřív o jídlo slušně poprosit – ochotný
        // bot poblíž zareaguje na „dej mi jídlo" intent a rozdělí se.
        BotContext ctx = ctx(bot);
        var neighbor = ctx.entities().nearest(ctx.position(), 16,
                dev.botalive.core.entity.TrackedEntity::isPlayer);
        if (neighbor.isPresent() && ctx.rng().chance(0.7)) {
            String name = resolveName(ctx, neighbor.get());
            ctx.chat().say((name != null ? name + " " : "")
                    + "dej mi neco k jidlu prosim, umiram hlady");
        }
    }

    /** Jméno protistrany podle UUID (online hráč/bot), nebo {@code null}. */
    private String resolveName(BotContext ctx, dev.botalive.core.entity.TrackedEntity entity) {
        var player = org.bukkit.Bukkit.getPlayer(entity.uuid());
        return player != null ? player.getName() : null;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        switch (phase) {
            case FIND -> {
                if (scanRetry.waiting()) {
                    return; // čeká se na async chunk snapshoty
                }
                chest = findChest(ctx, bot);
                if (chest == null) {
                    if (scanRetry.shouldRetry()) {
                        // Studená chunk cache (po teleportu): zahřát okolí
                        // a sken za chvíli zopakovat – bot mezitím stojí.
                        if (scanRetry.firstFailure() && ctx.worldView() != null) {
                            ctx.worldView().prefetch(ctx.position().toBlockPos(), 1);
                        }
                        return;
                    }
                    cooldownTicks = 1200; // žádná truhla – nouze trvá, zkusit jinak
                    phase = Phase.DONE;
                    return;
                }
                phase = Phase.GO;
            }
            case GO -> {
                double distSq = chest.center().distanceSquared(ctx.position());
                if (distSq > 3.0 * 3.0) {
                    ctx.navigator().navigateTo(ctx.position(), chest);
                    if (!ctx.navigator().navigating()) {
                        cooldownTicks = 1200;
                        phase = Phase.DONE;
                    }
                    return;
                }
                ctx.navigator().stop();
                ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0),
                        chest.center().add(0, 0.5, 0));
                waitTicks = ctx.rng().rangeInt(4, 10);
                phase = Phase.OPEN;
            }
            case OPEN -> {
                if (--waitTicks > 0) {
                    return;
                }
                // Klik na truhlu – server otevře okno (animace víka pro okolí).
                ctx.actions().useItemOn(chest, Direction.UP);
                BotNeeds needs = BotNeeds.assess(ctx.serverView().latest());
                loot = containers.withdrawSupplies(ctx,
                        ctx.worldView() != null ? ctx.worldView().worldName() : "world",
                        chest, needs.destitute());
                waitTicks = ctx.rng().rangeInt(20, 40); // „probírá se obsahem"
                phase = Phase.LOOT;
            }
            case LOOT -> {
                if (--waitTicks > 0 || loot == null || !loot.isDone()) {
                    return;
                }
                taken = loot.getNow(0) == null ? 0 : loot.getNow(0);
                ctx.actions().closeContainer();
                onLooted(ctx, bot);
            }
            case DONE -> {
            }
        }
    }

    @Override
    public void stop(Bot bot) {
        BotContext ctx = ctx(bot);
        if (phase == Phase.LOOT) {
            ctx.actions().closeContainer();
        }
        super.stop(bot);
    }

    @Override
    public boolean finished(Bot bot) {
        return phase == Phase.DONE;
    }

    @Override
    public String explain(Bot bot) {
        return switch (phase) {
            case FIND, GO -> "mám hlad a nic nemám – jdu si vypůjčit z truhly";
            case OPEN, LOOT -> "beru si z truhly, co nutně potřebuju";
            case DONE -> null;
        };
    }

    // ==================================================================

    /** Po krádeži: paměť truhly, svědomí podle povahy, dlouhý cooldown. */
    private void onLooted(BotContext ctx, Bot bot) {
        if (taken > 0) {
            // Prožitek formuje povahu: komu krádež projde, tomu se zalíbí.
            ctx.gainExperience(dev.botalive.core.personality.PersonalityEvolution
                    .BotExperience.STEAL_SUCCESS);
            // Stopy zůstávají – majitel může krádež odhalit (kniha zločinů).
            if (ctx.worldView() != null) {
                ctx.crimeLog().reportTheft(ctx.worldView().worldName(), chest,
                        bot.id(), bot.name());
            }
            if (ctx.worldView() != null) {
                bot.memory().remember(MemoryKind.CHEST, ctx.worldView().worldName(),
                        chest.x(), chest.y(), chest.z(), null,
                        Map.of("looted", "true"), 0.5);
            }
            double helpfulness = bot.personality().trait(Trait.HELPFULNESS);
            if (ctx.rng().chance(0.5)) {
                ctx.chat().say(helpfulness > 0.5
                        ? "promin, mel jsem hlad, vratim ti to"
                        : "co lezi v truhle, to se pocita");
            }
        }
        cooldownTicks = taken > 0 ? 6000 : 2400;
        phase = Phase.DONE;
    }

    /** Truhla z paměti, jinak sken okolí (i cizí truhly – v nouzi se nevybírá). */
    private BlockPos findChest(BotContext ctx, Bot bot) {
        if (ctx.worldView() == null) {
            return null;
        }
        var remembered = bot.memory().recallNearest(MemoryKind.CHEST,
                ctx.worldView().worldName(), (int) ctx.position().x(),
                (int) ctx.position().y(), (int) ctx.position().z());
        if (remembered.isPresent()
                && !"self".equals(remembered.get().data().get("owner"))) {
            // Vlastní truhlu bot nevykrádá – z té si bere StashGoal legálně.
            BlockPos pos = new BlockPos(remembered.get().x(), remembered.get().y(),
                    remembered.get().z());
            if (pos.distanceSquared(ctx.position().toBlockPos()) < 48 * 48
                    && isChest(ctx, pos)) {
                return pos;
            }
        }
        BlockPos center = ctx.position().toBlockPos();
        for (int radius = 2; radius <= 12; radius += 2) {
            for (int dx = -radius; dx <= radius; dx++) {
                for (int dy = -3; dy <= 3; dy++) {
                    for (int dz = -radius; dz <= radius; dz++) {
                        BlockPos pos = center.offset(dx, dy, dz);
                        if (isChest(ctx, pos)) {
                            return pos;
                        }
                    }
                }
            }
        }
        return null;
    }

    private boolean isChest(BotContext ctx, BlockPos pos) {
        Material material = ctx.worldView().materialAt(pos);
        return material == Material.CHEST || material == Material.BARREL
                || material == Material.TRAPPED_CHEST;
    }
}
