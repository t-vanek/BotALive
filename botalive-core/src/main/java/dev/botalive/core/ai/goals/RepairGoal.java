package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.inventory.AnvilService;
import dev.botalive.core.util.BlockPos;
import org.bukkit.Material;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;

import java.util.concurrent.CompletableFuture;

/**
 * Oprava opotřebených nástrojů u kovadliny.
 *
 * <p>Když má bot výrazně opotřebený kus (&gt;60 %), surovinu na opravu a XP,
 * dojde ke kovadlině (nebo si vlastní vyrobenou postaví), klikne na ni a
 * opraví – materiál + XP jako vanilla ({@link AnvilService}). Pečliví boti
 * opravují dřív a ochotněji.</p>
 */
public final class RepairGoal extends AbstractGoal {

    private enum Phase { FIND, GO, REPAIR, DONE }

    private final AnvilService anvils;

    private Phase phase = Phase.FIND;
    private BlockPos anvil;
    private StationPlacement placement;
    private CompletableFuture<AnvilService.RepairReport> pending;
    private int waitTicks;
    private int cooldownTicks;

    /**
     * @param anvils sdílená služba kovadlin
     */
    public RepairGoal(AnvilService anvils) {
        super("repair");
        this.anvils = anvils;
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        var snapshot = ctx.serverView().latest();
        if (snapshot == null || snapshot.damagedTool() == null
                || ctx.clientState().expLevel() < 2) {
            return 0;
        }
        Material repairMat = AnvilService.repairMaterial(snapshot.damagedTool());
        if (repairMat == null || !snapshot.hasItem(m -> m == repairMat)) {
            return 0;
        }
        double caution = bot.personality().trait(Trait.CAUTION);
        int percent = snapshot.damagedToolPercent();
        if (percent < 60 - caution * 20) {
            return 0; // pečliví opravují od ~40 %, ostatní od ~60 %
        }
        return 8 + percent * 0.15 + caution * 6;
    }

    @Override
    public void start(Bot bot) {
        phase = Phase.FIND;
        anvil = null;
        placement = null;
        pending = null;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        switch (phase) {
            case FIND -> {
                anvil = findAnvil(ctx);
                if (anvil != null) {
                    placement = null;
                    phase = Phase.GO;
                    return;
                }
                if (placement == null) {
                    placement = new StationPlacement(Material.ANVIL);
                }
                if (!placement.tick(ctx)) {
                    placement = null;
                    cooldownTicks = 2400; // bez kovadliny (craft ji dodá později)
                    phase = Phase.DONE;
                }
            }
            case GO -> {
                if (anvil.center().distanceSquared(ctx.position()) > 3.0 * 3.0) {
                    ctx.navigator().navigateTo(ctx.position(), anvil);
                    if (!ctx.navigator().navigating()) {
                        cooldownTicks = 1200;
                        phase = Phase.DONE;
                    }
                    return;
                }
                ctx.navigator().stop();
                ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0),
                        anvil.center().add(0, 0.5, 0));
                ctx.actions().useItemOn(anvil, Direction.UP);
                pending = anvils.repair(ctx.bot().id(),
                        ctx.worldView() != null ? ctx.worldView().worldName() : "world", anvil);
                waitTicks = ctx.rng().rangeInt(25, 45); // „buší kladivem"
                phase = Phase.REPAIR;
            }
            case REPAIR -> {
                if (--waitTicks > 0 || pending == null || !pending.isDone()) {
                    if (waitTicks % 8 == 0) {
                        ctx.actions().swing();
                    }
                    return;
                }
                var report = pending.getNow(AnvilService.RepairReport.NONE);
                if (report.repaired() != null && ctx.rng().chance(0.5)) {
                    ctx.chat().say("opraveno, jak novy");
                }
                cooldownTicks = report.repaired() != null ? 1200 : 2400;
                phase = Phase.DONE;
            }
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
            case FIND, GO -> "nesu opotřebené nástroje ke kovadlině";
            case REPAIR -> "opravuju si výbavu u kovadliny";
            case DONE -> null;
        };
    }

    /** Kovadlina v okolí (sken 8 bloků). */
    private BlockPos findAnvil(BotContext ctx) {
        if (ctx.worldView() == null) {
            return null;
        }
        BlockPos center = ctx.position().toBlockPos();
        for (int dx = -8; dx <= 8; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -8; dz <= 8; dz++) {
                    Material material = ctx.worldView().materialAt(center.offset(dx, dy, dz));
                    if (material != null && material.name().endsWith("ANVIL")) {
                        return center.offset(dx, dy, dz);
                    }
                }
            }
        }
        return null;
    }
}
