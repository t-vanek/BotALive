package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.inventory.ContainerService;
import dev.botalive.core.station.ChestStation;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.WorldView;
import org.bukkit.Material;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Ukládání přebytků do truhly.
 *
 * <p>Když se botovi plní inventář, vyhledá truhlu (nejdřív v paměti
 * {@link MemoryKind#CHEST}, pak skenem okolí), dojde k ní, otevře ji
 * (klik – ostatní hráči vidí animaci víka) a přebytky přesune
 * ({@link ChestStation} – server-side, nebo paketová na cizím serveru).
 * Truhlu si zapamatuje pro příště.</p>
 */
public final class StashGoal extends AbstractGoal {

    private enum Phase { FIND, GO, OPEN, DEPOSIT, CLOSE, DONE }

    private final ChestStation containers;

    private Phase phase = Phase.FIND;
    /** Sken přes studenou chunk cache (po teleportu) chvíli opakovat. */
    private final ScanRetry scanRetry = new ScanRetry(3, 25);
    private BlockPos chest;
    private StationPlacement placement;
    private int waitTicks;
    private CompletableFuture<Integer> deposit;
    private int cooldownTicks;

    /**
     * @param containers sdílená služba kontejnerů
     */
    public StashGoal(ChestStation containers) {
        super("stash");
        this.containers = containers;
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        // Přebytky se ukládají doma – ne do truhel pevností v Netheru.
        if (outsideOverworld(ctx)) {
            return 0;
        }
        var snapshot = ctx.serverView().latest();
        if (snapshot == null) {
            return 0;
        }
        // Zaplněnost hlavního inventáře + přítomnost přebytků.
        int filled = 0;
        boolean junk = false;
        for (Material material : snapshot.mainInventory()) {
            if (material != null) {
                filled++;
                junk |= ContainerService.isJunk(material);
            }
        }
        if (filled < 18 || !junk) {
            return 0;
        }
        double greed = bot.personality().trait(Trait.GREED);
        return 8 + greed * 10 + (filled - 18) * 1.5;
    }

    @Override
    public void start(Bot bot) {
        phase = Phase.FIND;
        chest = null;
        placement = null;
        deposit = null;
        scanRetry.reset();
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
                if (chest != null) {
                    placement = null;
                    phase = Phase.GO;
                    return;
                }
                // Studená chunk cache: neukvapit se s pokládkou vlastní
                // truhly (stála by hned vedle neviděné existující) – nejdřív
                // zahřát okolí a sken zopakovat.
                if (placement == null && scanRetry.shouldRetry()) {
                    if (scanRetry.firstFailure() && ctx.worldView() != null) {
                        ctx.worldView().prefetch(ctx.position().toBlockPos(), 1);
                    }
                    return;
                }
                // Truhla nikde – vlastní vyrobená se položí hned vedle.
                if (placement == null) {
                    placement = new StationPlacement(Material.CHEST);
                }
                if (!placement.tick(ctx)) {
                    placement = null;
                    cooldownTicks = 1800; // žádná truhla v okolí
                    phase = Phase.DONE;
                }
            }
            case GO -> {
                double distSq = chest.center().distanceSquared(ctx.position());
                if (distSq > 3.0 * 3.0) {
                    ctx.navigator().navigateTo(ctx.position(), chest);
                    if (!ctx.navigator().navigating()) {
                        phase = Phase.FIND;
                    }
                    return;
                }
                ctx.navigator().stop();
                ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0), chest.center().add(0, 0.5, 0));
                waitTicks = ctx.rng().rangeInt(4, 10);
                phase = Phase.OPEN;
            }
            case OPEN -> {
                if (--waitTicks > 0) {
                    return;
                }
                // U své truhly nejdřív odhalit případnou krádež (kniha zločinů).
                discoverTheft(ctx, bot);
                // Klik na truhlu – server otevře okno (animace víka pro okolí).
                ctx.actions().useItemOn(chest, Direction.UP);
                waitTicks = ctx.rng().rangeInt(15, 35); // "probírá se obsahem"
                phase = Phase.DEPOSIT;
            }
            case DEPOSIT -> {
                if (--waitTicks > 0) {
                    return;
                }
                if (deposit == null) {
                    deposit = containers.depositJunk(ctx,
                            ctx.worldView().worldName(), chest);
                    return;
                }
                if (!deposit.isDone()) {
                    return;
                }
                int moved = deposit.getNow(0);
                deposit = null;
                rememberChest(ctx, bot, moved);
                waitTicks = ctx.rng().rangeInt(5, 15);
                phase = Phase.CLOSE;
            }
            case CLOSE -> {
                if (--waitTicks > 0) {
                    return;
                }
                ctx.actions().closeContainer();
                cooldownTicks = ctx.rng().rangeInt(600, 1800);
                phase = Phase.DONE;
            }
            case DONE -> {
                // finished() ukončí
            }
        }
    }

    @Override
    public void stop(Bot bot) {
        ctx(bot).actions().closeContainer();
        super.stop(bot);
    }

    @Override
    public boolean finished(Bot bot) {
        return phase == Phase.DONE;
    }

    /** Zapamatuje si truhlu jako VLASTNÍ (důležitost roste s užitečností). */
    private void rememberChest(BotContext ctx, Bot bot, int movedItems) {
        if (ctx.worldView() != null && chest != null) {
            bot.memory().remember(MemoryKind.CHEST, ctx.worldView().worldName(),
                    chest.x(), chest.y(), chest.z(), null,
                    Map.of("deposited", String.valueOf(movedItems), "owner", "self"),
                    movedItems > 0 ? 0.7 : 0.4);
        }
    }

    /**
     * Odhalení krádeže z vlastní truhly: vztek, nepřátelství (živí existující
     * PvP pomstu) a poučení do povahy.
     */
    private void discoverTheft(BotContext ctx, Bot bot) {
        if (ctx.worldView() == null || chest == null) {
            return;
        }
        ctx.crimeLog().discoverTheft(ctx.worldView().worldName(), chest, bot.id())
                .ifPresent(theft -> {
                    ctx.chat().say("hej! nekdo mi vybral truhlu... "
                            + theft.thiefName() + ", to mi zaplatis!");
                    bot.memory().remember(MemoryKind.ENEMY, ctx.worldView().worldName(),
                            chest.x(), chest.y(), chest.z(), theft.thief(),
                            Map.of("reason", "krádež z truhly"), 0.8);
                    ctx.gainExperience(dev.botalive.core.personality.PersonalityEvolution
                            .BotExperience.WAS_ROBBED);
                });
    }

    /** Najde truhlu: paměť → sken okolí. */
    private BlockPos findChest(BotContext ctx, Bot bot) {
        WorldView world = ctx.worldView();
        if (world == null) {
            return null;
        }
        BlockPos center = ctx.position().toBlockPos();
        var remembered = bot.memory().recallNearest(MemoryKind.CHEST, world.worldName(),
                center.x(), center.y(), center.z());
        if (remembered.isPresent()
                && remembered.get().distanceSquared(center.x(), center.y(), center.z()) < 64 * 64) {
            var r = remembered.get();
            // Ověřit, že truhla pořád existuje (pokud je chunk v cache).
            Material material = world.materialAt(new BlockPos(r.x(), r.y(), r.z()));
            if (material == null || material == Material.CHEST || material == Material.BARREL
                    || material == Material.TRAPPED_CHEST) {
                return new BlockPos(r.x(), r.y(), r.z());
            }
        }
        int radius = 12;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -3; dy <= 3; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    Material material = world.materialAt(pos);
                    if (material == Material.CHEST || material == Material.BARREL) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }

    @Override
    public String explain(dev.botalive.api.bot.Bot bot) {
        return "ukládám přebytky do truhly";
    }
}
