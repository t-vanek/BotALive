package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.ai.EndKnowledge;
import dev.botalive.core.ai.EndReadiness;
import dev.botalive.core.bot.ServerSideView;
import dev.botalive.core.chat.PhraseCategory;
import dev.botalive.core.inventory.InventoryHelper;
import dev.botalive.core.pathfinding.PathGoal;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.WorldView;

import org.bukkit.Material;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Hledání strongholdu a jeho End portálu – zavírá tvrdou slepou uličku ambice
 * DRAKOBIJCE.
 *
 * <p>Dřív bot nikdy sám nezjistil, kde je portál do Endu: nezapálený rám ve
 * strongholdu se nepamatoval a žádný lokátor neexistoval, takže {@code
 * EndTravelGoal} (který rám umí zaplnit oky i projít) neměl kam jít. Tenhle cíl
 * ten předpoklad dodá:</p>
 * <ol>
 *   <li>server-side najde nejbližší <b>stronghold</b> ({@code
 *       locateNearestStructure} – stejný precedent jako end city);</li>
 *   <li>dojde k němu (chunky se cestou načtou);</li>
 *   <li>v načteném okolí najde bloky <b>END_PORTAL_FRAME</b> a zapamatuje rám
 *       jako portál do Endu čekající na oči ({@code type=end, eyes=missing}).</li>
 * </ol>
 *
 * <p>Od té chvíle převezme {@link EndTravelGoal}: dojde k rámu, doplní ho oky
 * (to už umí) a projde. Zapamatování rámu zároveň splní milník ambice
 * „zná portál do Endu".</p>
 */
public final class StrongholdSeekGoal extends AbstractGoal {

    private enum Phase { LOCATE, TRAVEL, SEARCH, DONE }

    /** Poloměr hledání strongholdu (chunky). */
    private static final int LOCATE_RADIUS_CHUNKS = 100;
    /** Minimum očí Enderu – bez nich nemá cenu rám hledat (nezaplní ho). */
    private static final int EYES_MIN = 8;
    /** Do jaké vzdálenosti od strongholdu se přejde do skenu (bloky, XZ). */
    private static final int SEARCH_START_DISTANCE = 40;
    /** Poloměr skenu rámu kolem strongholdu (bloky, XZ). */
    private static final int SCAN_RADIUS = 64;
    private static final int SCAN_Y_MIN = 4;
    private static final int SCAN_Y_MAX = 68;
    /** Rozpočet cesty ke strongholdu (ticky). */
    private static final int TRAVEL_BUDGET_TICKS = 8000;

    private Phase phase = Phase.DONE;
    private CompletableFuture<BlockPos> locateFuture;
    private BlockPos strongholdPos;
    private int travelTicks;
    private int scanY;
    private int cooldownTicks;

    /** Vytvoří cíl. */
    public StrongholdSeekGoal() {
        super("stronghold");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        // Stronghold je v overworldu; do Endu se chystá jen připravený a odvážný.
        if (!ctx.config().end().enabled() || ctx.clientState().dead()
                || outsideOverworld(ctx) || ctx.worldView() == null) {
            return 0;
        }
        if (!expeditionFit(ctx)
                || bot.personality().trait(Trait.COURAGE) < ctx.config().end().minCourage()) {
            return 0;
        }
        ServerSideView.Snapshot snapshot = ctx.serverView().latest();
        if (snapshot == null
                || InventoryHelper.countEstimate(snapshot, m -> m == Material.ENDER_EYE) < EYES_MIN) {
            return 0; // bez zásoby očí nemá cenu rám hledat
        }
        if (EndKnowledge.knowsEndPortal(bot.memory().recall(MemoryKind.PORTAL))) {
            return 0; // portál už zná – převezme EndTravelGoal
        }
        double courage = bot.personality().trait(Trait.COURAGE);
        double curiosity = bot.personality().trait(Trait.CURIOSITY);
        return 7 + courage * 8 + curiosity * 4;
    }

    @Override
    public void start(Bot bot) {
        phase = Phase.LOCATE;
        locateFuture = null;
        strongholdPos = null;
        travelTicks = 0;
        scanY = SCAN_Y_MIN;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        switch (phase) {
            case LOCATE -> tickLocate(ctx);
            case TRAVEL -> tickTravel(ctx);
            case SEARCH -> tickSearch(ctx, bot);
            case DONE -> {
            }
        }
    }

    private void tickLocate(BotContext ctx) {
        if (locateFuture == null) {
            locateFuture = locateStronghold(ctx);
            return;
        }
        if (!locateFuture.isDone()) {
            return;
        }
        strongholdPos = locateFuture.getNow(null);
        locateFuture = null;
        if (strongholdPos == null) {
            giveUp(6000); // žádný stronghold v dosahu / packet režim bez asistence
            return;
        }
        travelTicks = 0;
        phase = Phase.TRAVEL;
    }

    private void tickTravel(BotContext ctx) {
        Vec3 pos = ctx.position();
        double dx = strongholdPos.x() - pos.x();
        double dz = strongholdPos.z() - pos.z();
        if (dx * dx + dz * dz <= SEARCH_START_DISTANCE * SEARCH_START_DISTANCE) {
            ctx.navigator().stop();
            scanY = SCAN_Y_MIN;
            phase = Phase.SEARCH;
            return;
        }
        if (++travelTicks > TRAVEL_BUDGET_TICKS) {
            giveUp(2400);
            return;
        }
        // Jde se po povrchu k XZ strongholdu (zakopání k rámu obstará EndTravelGoal).
        BlockPos surface = new BlockPos(strongholdPos.x(), pos.toBlockPos().y(), strongholdPos.z());
        ctx.navigator().navigateTo(pos, PathGoal.near(surface, 6));
        if (!ctx.navigator().navigating()) {
            giveUp(2400); // nedosažitelné
        }
    }

    private void tickSearch(BotContext ctx, Bot bot) {
        WorldView world = ctx.worldView();
        if (world == null) {
            giveUp(2400);
            return;
        }
        BlockPos frame = scanLayer(world, scanY);
        if (frame != null) {
            rememberFrame(ctx, bot, world, frame);
            phase = Phase.DONE;
            return;
        }
        if (++scanY > SCAN_Y_MAX) {
            // Rám v načteném okolí není (moc daleko / studená cache) – zkusí se
            // znovu později, cooldown drží interval, ať to netočí naprázdno.
            giveUp(6000);
        }
    }

    /** Projede jednu vodorovnou vrstvu kolem strongholdu; vrátí první rám. */
    private BlockPos scanLayer(WorldView world, int y) {
        int cx = strongholdPos.x();
        int cz = strongholdPos.z();
        for (int dx = -SCAN_RADIUS; dx <= SCAN_RADIUS; dx++) {
            for (int dz = -SCAN_RADIUS; dz <= SCAN_RADIUS; dz++) {
                BlockPos pos = new BlockPos(cx + dx, y, cz + dz);
                if (world.materialAt(pos) == Material.END_PORTAL_FRAME) {
                    return pos;
                }
            }
        }
        return null;
    }

    private void rememberFrame(BotContext ctx, Bot bot, WorldView world, BlockPos frame) {
        bot.memory().remember(MemoryKind.PORTAL, world.worldName(),
                frame.x(), frame.y(), frame.z(), null,
                Map.of("type", EndKnowledge.TYPE_END,
                        EndKnowledge.DATA_EYES, EndKnowledge.EYES_MISSING),
                0.85);
        if (ctx.rng().chance(0.7)) {
            ctx.chat().sayFrom(PhraseCategory.PORTAL_FOUND, null);
        }
        cooldownTicks = 1200; // EndTravelGoal teď převezme (rám doplní oky a projde)
    }

    /** Server-side locate nejbližšího strongholdu přes vlákno regionu. */
    private CompletableFuture<BlockPos> locateStronghold(BotContext ctx) {
        String worldName = ctx.worldView().worldName();
        Vec3 pos = ctx.position();
        org.bukkit.World world = org.bukkit.Bukkit.getWorld(worldName);
        if (world == null) {
            return CompletableFuture.completedFuture(null);
        }
        org.bukkit.Location origin = new org.bukkit.Location(world, pos.x(), pos.y(), pos.z());
        return ctx.bridge().callAt(origin, () -> {
            var result = world.locateNearestStructure(origin,
                    org.bukkit.generator.structure.Structure.STRONGHOLD,
                    LOCATE_RADIUS_CHUNKS, false);
            if (result == null || result.getLocation() == null) {
                return null;
            }
            var location = result.getLocation();
            return new BlockPos(location.getBlockX(), location.getBlockY(), location.getBlockZ());
        }).exceptionally(t -> null);
    }

    private void giveUp(int cooldown) {
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

    @Override
    public String explain(Bot bot) {
        return switch (phase) {
            case LOCATE, TRAVEL -> "hledám stronghold s portálem do Endu";
            case SEARCH -> "prohledávám stronghold, kde je portál do Endu";
            case DONE -> null;
        };
    }
}
