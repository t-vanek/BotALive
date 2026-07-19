package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.inventory.InventoryHelper;
import dev.botalive.core.physics.EdgeGuard;
import dev.botalive.core.tasks.MineBlockTask;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.WorldDimension;
import dev.botalive.core.world.WorldView;
import org.bukkit.Material;

import java.util.Map;
import java.util.Optional;
import dev.botalive.core.pathfinding.PathGoal;

/**
 * Kořist z Endu – aby výprava nebyla jen o drakovi.
 *
 * <p>Tři činnosti podle situace a povahy: <b>lov endermanů</b> kvůli perlám
 * (jen odvážní, {@code end.hunt-endermen}; cíl se vybírá osamocený, aby se
 * nesemlela půlka ostrova), <b>těžba end stone</b> na mosty a stavění
 * (došly-li bloky) a <b>sklizeň chorus rostlin</b>, když na ně bot narazí
 * (vnější ostrovy, custom mapy). Upuštěné itemy sbírá standardní cíl
 * {@code collect}. Pohyb v boji jistí {@link EdgeGuard} – enderman u hrany
 * ostrova není důvod umřít.</p>
 */
public final class EndHarvestGoal extends AbstractGoal {

    /** Kolik perel je „dost" – víc se na jedné výpravě nelovi. */
    private static final int PEARLS_ENOUGH = 16;

    /** Kolik stavebních bloků chce mít bot v Endu v zásobě (mosty!). */
    private static final int BLOCKS_TARGET = 48;

    private MineBlockTask task;
    private int cooldownTicks;
    private int huntedId = -1;
    private String activity;
    /** Cache skenů chorus rostlin – blokový sken nepatří do každé utility. */
    private int chorusCheckTicks;
    private boolean chorusCached;
    /** Rozpočet chůze k bloku (nedosažitelné cíle se vzdávají). */
    private int approachTicks;

    /** Vytvoří cíl. */
    public EndHarvestGoal() {
        super("end-harvest");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (!ctx.config().end().enabled() || ctx.clientState().dead()
                || ctx.dimension() != WorldDimension.END) {
            return 0;
        }
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        var snapshot = ctx.serverView().latest();
        // Levná existenční otázka – drahý výběr osamocené kořisti (kontrola
        // hloučku na endermana) patří až do ticku, ne do každé utility.
        boolean wantsPearls = canHuntPearls(ctx, bot)
                && ctx.entities().nearest(ctx.position(), 24,
                        TrackedEntity::isEnderman).isPresent();
        boolean wantsStone = wantsEndStone(ctx, snapshot);
        if (!wantsPearls && !wantsStone && !chorusSeen(ctx)) {
            return 0;
        }
        double greed = bot.personality().trait(Trait.GREED);
        return 12 + greed * 8;
    }

    /** Občasný sken chorus rostlin s cache – blokový sken nepatří do utility. */
    private boolean chorusSeen(BotContext ctx) {
        chorusCheckTicks -= ctx.config().ai().decisionIntervalTicks();
        if (chorusCheckTicks <= 0) {
            chorusCheckTicks = 200;
            chorusCached = findChorus(ctx) != null;
        }
        return chorusCached;
    }

    @Override
    public void start(Bot bot) {
        task = null;
        huntedId = -1;
        activity = null;
        approachTicks = 0;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);

        // Probíhající kopání (end stone / chorus).
        if (task != null) {
            if (task.tick(ctx)) {
                task = null;
            }
            return;
        }

        // Rozdělaný lov endermana.
        if (huntedId >= 0) {
            if (tickHunt(ctx)) {
                return;
            }
            huntedId = -1;
        }

        var snapshot = ctx.serverView().latest();

        // 1) Perly: osamocený enderman + odvaha + meč.
        if (canHuntPearls(ctx, bot)) {
            Optional<TrackedEntity> prey = findLoneEnderman(ctx);
            if (prey.isPresent()) {
                huntedId = prey.get().entityId();
                activity = "lovím endermana kvůli perle";
                // Vyprovokovaný enderman je nepřítel od prvního máchnutí –
                // ne až od první inkasované rány. Bez záznamu by po teleportu
                // kořisti byl pro SurviveGoal/CombatGoal neviditelný.
                if (prey.get().uuid() != null && ctx.worldView() != null) {
                    Vec3 at = prey.get().position();
                    bot.memory().remember(MemoryKind.ENEMY, ctx.worldView().worldName(),
                            (int) at.x(), (int) at.y(), (int) at.z(), prey.get().uuid(),
                            Map.of("type", "ENDERMAN", "via", "provoked"), 0.6);
                }
                ctx.combat().engage(prey.get());
                return;
            }
        }

        // 2) End stone do zásoby (mosty přes void, stavění).
        if (wantsEndStone(ctx, snapshot)) {
            java.util.List<BlockPos> stone = scanAllFor(ctx, m -> m == Material.END_STONE, 8);
            if (!stone.isEmpty() && approachAndMine(ctx, stone, "těžím end stone na mosty")) {
                return;
            }
        }

        // 3) Chorus: jídlo z Endu (vnější ostrovy, custom mapy).
        java.util.List<BlockPos> chorus = findChorus(ctx);
        if (!chorus.isEmpty() && approachAndMine(ctx, chorus, "sklízím chorus ovoce")) {
            return;
        }

        cooldownTicks = 400; // teď není co dělat
    }

    /** Jeden tick lovu; {@code false} = lov skončil (úlovek/útěk kořisti). */
    private boolean tickHunt(BotContext ctx) {
        Optional<TrackedEntity> prey = ctx.entities().byId(huntedId);
        if (prey.isEmpty() || prey.get().position().distance(ctx.position()) > 40) {
            ctx.combat().disengage();
            return false;
        }
        ctx.combat().engage(prey.get());
        dev.botalive.core.physics.MoveInput preyMove = ctx.combat().tick(
                ctx.position(), ctx.clientState().health(),
                ctx.onGround(), ctx.serverView().latest());
        if (preyMove != null) {
            ctx.requestMove(EdgeGuard.apply(ctx.worldView(), ctx.position(), preyMove));
        }
        return true;
    }

    /** Dojde k bloku a pustí se do kopání; {@code false} = nedosažitelný. */
    /** Dojde k NEJBLIŽŠÍMU DOSAŽITELNÉMU z kandidátů a kopne do něj;
     *  {@code false} = žádný není dosažitelný. */
    private boolean approachAndMine(BotContext ctx, java.util.List<BlockPos> candidates,
                                    String why) {
        Vec3 pos = ctx.position();
        Vec3 eye = pos.add(0, 1.62, 0);
        BlockPos inReach = null;
        double bestDist = 4.5 * 4.5;
        for (BlockPos candidate : candidates) {
            double dist = candidate.center().add(0, 0.5, 0).distanceSquared(eye);
            if (dist <= bestDist) {
                bestDist = dist;
                inReach = candidate;
            }
        }
        if (inReach == null) {
            if (++approachTicks > 600) {
                approachTicks = 0;
                return false; // k žádnému kandidátovi se nejde dostat
            }
            ctx.navigator().navigateTo(pos, candidates.size() > 1
                    ? PathGoal.anyNear(candidates, 2)
                    : PathGoal.near(candidates.getFirst(), 2));
            activity = why;
            return true;
        }
        approachTicks = 0;
        ctx.navigator().stop();
        Material material = ctx.worldView().materialAt(inReach);
        ctx.inventory().equipBestTool(ctx.serverView().latest(),
                material != null ? material : Material.STONE);
        task = new MineBlockTask(inReach);
        activity = why;
        return true;
    }

    private boolean canHuntPearls(BotContext ctx, Bot bot) {
        if (!ctx.config().end().huntEndermen() || !ctx.config().combat().enabled()) {
            return false;
        }
        // Lov je riskantnější než samotná výprava – práh drží aspoň 0.6
        // a respektuje přísnější end.min-courage z konfigurace.
        if (bot.personality().trait(Trait.COURAGE)
                < Math.max(0.6, ctx.config().end().minCourage())) {
            return false;
        }
        var snapshot = ctx.serverView().latest();
        if (snapshot == null || !snapshot.hasItem(m -> m.name().endsWith("_SWORD"))) {
            return false;
        }
        return InventoryHelper.countEstimate(snapshot, m -> m == Material.ENDER_PEARL)
                < PEARLS_ENOUGH;
    }

    private static boolean wantsEndStone(BotContext ctx, dev.botalive.core.bot.ServerSideView.Snapshot snapshot) {
        if (snapshot == null) {
            return false;
        }
        boolean pickaxe = snapshot.hasItem(m -> m.name().endsWith("_PICKAXE"));
        return pickaxe && InventoryHelper.countEstimate(snapshot,
                InventoryHelper::isBuildingBlock) < BLOCKS_TARGET;
    }

    /**
     * Osamocený enderman: žádný další enderman do 8 bloků od něj. Provokovat
     * jednoho uprostřed hloučku znamená lynč – to hráči nedělají a bot taky ne.
     */
    private Optional<TrackedEntity> findLoneEnderman(BotContext ctx) {
        Vec3 pos = ctx.position();
        return ctx.entities().nearest(pos, 24, e -> {
            if (!e.isEnderman()) {
                return false;
            }
            return ctx.entities().nearby(e.position(), 8, TrackedEntity::isEnderman)
                    .stream().noneMatch(other -> other.entityId() != e.entityId());
        });
    }

    private java.util.List<BlockPos> findChorus(BotContext ctx) {
        return scanAllFor(ctx, m -> m == Material.CHORUS_PLANT || m == Material.CHORUS_FLOWER, 10);
    }

    /** Až 4 nejbližší odkryté bloky dle filtru – o pořadí rozhodne
     *  dosažitelnost (anyNear), ne vzdušná čára. */
    private java.util.List<BlockPos> scanAllFor(BotContext ctx,
            java.util.function.Predicate<Material> filter, int radius) {
        WorldView world = ctx.worldView();
        if (world == null) {
            return java.util.List.of();
        }
        BlockPos center = ctx.position().toBlockPos();
        java.util.ArrayList<BlockPos> found = new java.util.ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    BlockPos pos = center.offset(dx, dy, dz);
                    Material material = world.materialAt(pos);
                    if (material == null || !filter.test(material)
                            || !exposed(world, pos)) {
                        continue;
                    }
                    found.add(pos);
                }
            }
        }
        found.sort(java.util.Comparator.comparingDouble(p -> p.distanceSquared(center)));
        return found.size() > 4 ? java.util.List.copyOf(found.subList(0, 4))
                : java.util.List.copyOf(found);
    }

    /** Zasypaný blok nemá smysl – kope se jen to, k čemu se dá došlápnout. */
    private static boolean exposed(WorldView world, BlockPos pos) {
        return world.traitsAt(pos.up()).passable()
                || world.traitsAt(pos.down()).passable()
                || world.traitsAt(pos.offset(1, 0, 0)).passable()
                || world.traitsAt(pos.offset(-1, 0, 0)).passable()
                || world.traitsAt(pos.offset(0, 0, 1)).passable()
                || world.traitsAt(pos.offset(0, 0, -1)).passable();
    }

    @Override
    public void stop(Bot bot) {
        BotContext ctx = ctx(bot);
        if (task != null) {
            task.cancel(ctx);
            task = null;
        }
        if (huntedId >= 0) {
            ctx.combat().disengage();
            huntedId = -1;
        }
        super.stop(bot);
    }

    @Override
    public boolean finished(Bot bot) {
        return cooldownTicks > 0;
    }

    @Override
    public String explain(Bot bot) {
        return activity;
    }
}
