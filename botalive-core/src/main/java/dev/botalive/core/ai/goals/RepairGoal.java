package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.bot.ServerSideView;
import dev.botalive.core.inventory.AnvilService;
import dev.botalive.core.inventory.GrindstoneService;
import dev.botalive.core.util.BlockPos;
import org.bukkit.Material;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;

import java.util.concurrent.CompletableFuture;
import dev.botalive.core.pathfinding.PathGoal;

/**
 * Oprava opotřebených nástrojů – nezávisle na roli i na dílně sídla.
 *
 * <p>Dvě cesty podle toho, co má bot po ruce:</p>
 * <ul>
 *   <li><b>Kovadlina</b> ({@link AnvilService}): oprava surovinou + XP, drží
 *       očarování; sem patří i aplikace vyloupené enchantované knihy. Pečliví
 *       boti opravují dřív.</li>
 *   <li><b>Brusný kámen</b> ({@link GrindstoneService}): oprava <b>bez
 *       materiálu</b> spojením dvou neočarovaných opotřebených kusů téhož druhu.
 *       Záchrana, když dochází nářadí a není čím opravovat – bot se tak
 *       nezasekne na cíli s rozbíjejícím se krumpáčem. Využije třeba grindstone
 *       v dílně zbrojíře, i když sám zbrojíř není.</li>
 * </ul>
 *
 * <p>Stanici si bot najde v okolí, jinak si ji z inventáře postaví
 * ({@link StationPlacement}); grindstone typicky obývá dílnu, kde ho najde
 * kdokoli v nouzi.</p>
 */
public final class RepairGoal extends AbstractGoal {

    private enum Phase { FIND, GO, REPAIR, DONE }

    private final AnvilService anvils;
    private final GrindstoneService grindstones;

    private Phase phase = Phase.FIND;
    /** {@code true} = oprava spojením u grindstonu; jinak kovadlina (surovina/kniha). */
    private boolean grindstoning;
    private BlockPos station;
    private StationPlacement placement;
    private CompletableFuture<Material> pending;
    private int waitTicks;
    private int cooldownTicks;

    /**
     * @param anvils      sdílená služba kovadlin
     * @param grindstones sdílená služba brusných kamenů
     */
    public RepairGoal(AnvilService anvils, GrindstoneService grindstones) {
        super("repair");
        this.anvils = anvils;
        this.grindstones = grindstones;
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        ServerSideView.Snapshot snapshot = ctx.serverView().latest();
        if (snapshot == null) {
            return 0;
        }
        boolean hasBook = snapshot.hasItem(m -> m == Material.ENCHANTED_BOOK)
                && ctx.clientState().expLevel() >= 4;
        Material tool = snapshot.damagedTool();
        double caution = bot.personality().trait(Trait.CAUTION);
        boolean worn = tool != null && snapshot.damagedToolPercent() >= 60 - caution * 20;
        boolean anvilMat = tool != null && ctx.clientState().expLevel() >= 2
                && anvilMaterialAvailable(snapshot, tool);

        if (anvilMat && worn) {
            return 8 + snapshot.damagedToolPercent() * 0.15 + caution * 6; // kovadlina + surovina
        }
        if (worn && !anvilMat && countSlots(snapshot, tool) >= 2) {
            return 6 + snapshot.damagedToolPercent() * 0.12 + caution * 4; // grindstone bez suroviny
        }
        if (hasBook) {
            return 8 + bot.personality().trait(Trait.INTELLIGENCE) * 6; // jen aplikace knihy
        }
        return 0;
    }

    @Override
    public void start(Bot bot) {
        BotContext ctx = ctx(bot);
        grindstoning = wantsGrindstone(ctx, bot, ctx.serverView().latest());
        phase = Phase.FIND;
        station = null;
        placement = null;
        pending = null;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        switch (phase) {
            case FIND -> tickFind(ctx);
            case GO -> tickGo(ctx);
            case REPAIR -> tickRepair(ctx);
            case DONE -> {
            }
        }
    }

    private void tickFind(BotContext ctx) {
        Material stationType = grindstoning ? Material.GRINDSTONE : Material.ANVIL;
        station = findStation(ctx, stationType);
        if (station != null) {
            placement = null;
            phase = Phase.GO;
            return;
        }
        if (placement == null) {
            placement = new StationPlacement(stationType);
        }
        if (!placement.tick(ctx)) {
            placement = null;
            cooldownTicks = 2400; // stanici nemá – craft/loot/dílna ji dodá později
            phase = Phase.DONE;
        }
    }

    private void tickGo(BotContext ctx) {
        if (station.center().distanceSquared(ctx.position()) > 3.0 * 3.0) {
            ctx.navigator().navigateTo(ctx.position(), PathGoal.near(station, 2));
            if (!ctx.navigator().navigating()) {
                cooldownTicks = 1200;
                phase = Phase.DONE;
            }
            return;
        }
        ctx.navigator().stop();
        ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0), station.center().add(0, 0.5, 0));
        ctx.actions().useItemOn(station, Direction.UP);
        String worldName = ctx.worldView() != null ? ctx.worldView().worldName() : "world";
        pending = grindstoning
                ? grindstones.repair(ctx.bot().id(), worldName, station)
                        .thenApply(GrindstoneService.RepairReport::repaired)
                : anvilOperation(ctx, worldName);
        waitTicks = ctx.rng().rangeInt(25, 45); // „buší kladivem" / „brousí"
        phase = Phase.REPAIR;
    }

    /** Kovadlina: nejdřív oprava surovinou (je-li proveditelná), jinak kniha. */
    private CompletableFuture<Material> anvilOperation(BotContext ctx, String worldName) {
        ServerSideView.Snapshot snapshot = ctx.serverView().latest();
        Material tool = snapshot == null ? null : snapshot.damagedTool();
        boolean repairable = tool != null && anvilMaterialAvailable(snapshot, tool)
                && ctx.clientState().expLevel() >= 2;
        return (repairable
                ? anvils.repair(ctx.bot().id(), worldName, station)
                : anvils.applyBook(ctx.bot().id(), worldName, station))
                .thenApply(AnvilService.RepairReport::repaired);
    }

    private void tickRepair(BotContext ctx) {
        if (--waitTicks > 0 || pending == null || !pending.isDone()) {
            if (waitTicks % 8 == 0) {
                ctx.actions().swing();
            }
            return;
        }
        Material repaired = pending.getNow(null);
        if (repaired != null && ctx.rng().chance(0.5)) {
            ctx.chat().say(grindstoning
                    ? "spojil jsem dva kusy u brusu, zas mám čím dělat"
                    : "opraveno, jak novy");
        }
        cooldownTicks = repaired != null ? 1200 : 2400;
        phase = Phase.DONE;
    }

    @Override
    public boolean finished(Bot bot) {
        return phase == Phase.DONE;
    }

    @Override
    public String explain(Bot bot) {
        return switch (phase) {
            case FIND, GO -> grindstoning
                    ? "nesu opotřebené nářadí k brusnému kameni"
                    : "nesu opotřebené nástroje ke kovadlině";
            case REPAIR -> grindstoning
                    ? "brousím a spojuju opotřebené kusy"
                    : "opravuju si výbavu u kovadliny";
            case DONE -> null;
        };
    }

    /** Chce bot opravit u grindstonu (bez suroviny), místo u kovadliny? */
    private boolean wantsGrindstone(BotContext ctx, Bot bot, ServerSideView.Snapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        Material tool = snapshot.damagedTool();
        if (tool == null) {
            return false;
        }
        double caution = bot.personality().trait(Trait.CAUTION);
        boolean worn = snapshot.damagedToolPercent() >= 60 - caution * 20;
        boolean anvilMat = ctx.clientState().expLevel() >= 2
                && anvilMaterialAvailable(snapshot, tool);
        return worn && !anvilMat && countSlots(snapshot, tool) >= 2;
    }

    private static boolean anvilMaterialAvailable(ServerSideView.Snapshot snapshot, Material tool) {
        Material mat = AnvilService.repairMaterial(tool);
        return mat != null && snapshot.hasItem(m -> m == mat);
    }

    /** Kolik slotů batohu (hotbar + hlavní) drží daný materiál (heuristika páru). */
    private static int countSlots(ServerSideView.Snapshot snapshot, Material material) {
        int count = 0;
        for (Material m : snapshot.hotbar()) {
            if (m == material) {
                count++;
            }
        }
        for (Material m : snapshot.mainInventory()) {
            if (m == material) {
                count++;
            }
        }
        return count;
    }

    /** Stanice daného druhu v okolí (sken 8 bloků). */
    private BlockPos findStation(BotContext ctx, Material type) {
        if (ctx.worldView() == null) {
            return null;
        }
        BlockPos center = ctx.position().toBlockPos();
        for (int dx = -8; dx <= 8; dx++) {
            for (int dy = -2; dy <= 2; dy++) {
                for (int dz = -8; dz <= 8; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    Material material = ctx.worldView().materialAt(pos);
                    boolean match = type == Material.ANVIL
                            ? material != null && material.name().endsWith("ANVIL")
                            : material == type;
                    if (match) {
                        return pos;
                    }
                }
            }
        }
        return null;
    }
}
