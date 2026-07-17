package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.memory.MemoryRecord;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.ai.BotNeeds;
import dev.botalive.core.build.HouseBlueprint;
import dev.botalive.core.tasks.BotTask;
import dev.botalive.core.tasks.MineBlockTask;
import dev.botalive.core.tasks.PlaceBlockTask;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.WorldView;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;

/**
 * Stavba skutečného domku – bot si plánuje, upravuje terén a staví.
 *
 * <p>Postup: najít rovné místo poblíž ({@link HouseBlueprint}), připravit
 * staveniště (vykopat překážky, zaplnit díry v podlaze – jen s
 * {@code ai.terraforming}), postavit zdi s dveřním otvorem a střechu z
 * jednoho místa uvnitř, vyjít dveřmi. Hotový dům se ukládá jako
 * {@link MemoryKind#HOME} typu {@code house} – bot ví, že dům má, a další
 * už nestaví; v noci se do něj vrací ({@code ReturnHomeGoal}).</p>
 */
public final class BuildHouseGoal extends AbstractGoal {

    /** Kolik sloupců půdorysu smí chybět/přebývat, aby se to ještě srovnalo. */
    private static final int MAX_FILLS = 4;
    private static final int MAX_DIGS = 8;

    private enum Phase { FIND_SITE, GOTO_SITE, TERRAFORM, BUILD, FURNISH, DONE }

    /** Krok vybavování domu: co vzít do ruky a kam to položit. */
    private record FurnishStep(java.util.function.Predicate<org.bukkit.Material> item,
                               dev.botalive.core.util.BlockPos target) {
    }

    private Phase phase = Phase.FIND_SITE;
    private BlockPos origin;
    private final Deque<BotTask> terraform = new ArrayDeque<>();
    private final Deque<BlockPos> placements = new ArrayDeque<>();
    private final Deque<FurnishStep> furnish = new ArrayDeque<>();
    private BotTask current;
    private int cooldownTicks;
    private int placedCount;
    /** Pojistka proti opakované stavbě, kdyby paměť HOME zmergovala metadata. */
    private boolean houseBuilt;
    /** „Došly bloky" hlásit jen jednou za seanci. */
    private boolean announcedOutOfBlocks;

    /** Vytvoří cíl. */
    public BuildHouseGoal() {
        super("house");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        // Dům se staví za světla.
        long time = ctx.worldTime();
        if (time >= 11500 && time <= 23000) {
            return 0;
        }
        // Už dům má → nestavět další.
        if (houseBuilt || hasHouse(bot, ctx)) {
            return 0;
        }
        // Bez dostatku bloků nemá cenu začínat.
        BotNeeds needs = BotNeeds.assess(ctx.serverView().latest());
        if (needs.buildingBlocks() < HouseBlueprint.blocksNeeded()) {
            return 0;
        }
        double intelligence = bot.personality().trait(Trait.INTELLIGENCE);
        double caution = bot.personality().trait(Trait.CAUTION);
        return 8 + intelligence * 8 + caution * 6;
    }

    @Override
    public void start(Bot bot) {
        phase = Phase.FIND_SITE;
        origin = null;
        terraform.clear();
        placements.clear();
        furnish.clear();
        current = null;
        placedCount = 0;
        announcedOutOfBlocks = false;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        WorldView world = ctx.worldView();
        if (world == null) {
            cooldownTicks = 600;
            phase = Phase.DONE;
            return;
        }
        switch (phase) {
            case FIND_SITE -> findSite(ctx, bot);
            case GOTO_SITE -> gotoSite(ctx);
            case TERRAFORM -> tickTasks(ctx, terraform, Phase.BUILD);
            case BUILD -> tickBuild(ctx, bot);
            case FURNISH -> tickFurnish(ctx, bot);
            case DONE -> {
            }
        }
    }

    @Override
    public void stop(Bot bot) {
        if (current != null) {
            current.cancel(ctx(bot));
            current = null;
        }
        terraform.clear();
        placements.clear();
        super.stop(bot);
    }

    @Override
    public boolean finished(Bot bot) {
        return phase == Phase.DONE;
    }

    @Override
    public String explain(Bot bot) {
        return switch (phase) {
            case FIND_SITE -> "hledám rovné místo na dům";
            case GOTO_SITE -> "jdu na staveniště";
            case TERRAFORM -> "srovnávám staveniště pro dům";
            case BUILD -> "stavím si dům (zbývá " + (placements.size()
                    + (current != null ? 1 : 0)) + " bloků)";
            case FURNISH -> "zabydluju se – dveře, světlo, postel";
            case DONE -> null;
        };
    }

    // ==================================================================

    /** Hledá rovné místo v okolí; hodnotí podle nutných úprav terénu. */
    private void findSite(BotContext ctx, Bot bot) {
        WorldView world = ctx.worldView();
        BlockPos feet = ctx.position().toBlockPos();
        boolean terraformingAllowed = ctx.config().ai().terraforming();

        BlockPos best = null;
        int bestCost = Integer.MAX_VALUE;
        for (int dx = -8; dx <= 8; dx += 2) {
            for (int dz = -8; dz <= 8; dz += 2) {
                for (int dy = -1; dy <= 1; dy++) {
                    BlockPos candidate = feet.offset(dx, dy, dz);
                    int cost = siteCost(world, candidate, terraformingAllowed);
                    if (cost >= 0 && cost < bestCost) {
                        bestCost = cost;
                        best = candidate;
                    }
                }
            }
        }
        if (best == null) {
            cooldownTicks = 1200; // tady se stavět nedá – zkusit jinde/jindy
            phase = Phase.DONE;
            return;
        }
        origin = best;
        planTerraform(ctx, bot);
        phase = Phase.GOTO_SITE;
    }

    /**
     * Cena úprav staveniště: počet výkopů + zásypů; -1 = nepoužitelné
     * (moc úprav, tekutina, nebo úpravy zakázané a místo není rovné).
     */
    private int siteCost(WorldView world, BlockPos candidate, boolean terraformingAllowed) {
        int fills = 0;
        for (BlockPos ground : HouseBlueprint.groundColumns(candidate)) {
            var traits = world.traitsAt(ground);
            if (traits.liquid()) {
                return -1;
            }
            if (!traits.solid()) {
                fills++;
            }
        }
        int digs = 0;
        for (BlockPos space : HouseBlueprint.clearVolume(candidate)) {
            var traits = world.traitsAt(space);
            if (traits.liquid()) {
                return -1;
            }
            if (traits.solid()) {
                digs++;
            }
        }
        if (fills > MAX_FILLS || digs > MAX_DIGS) {
            return -1;
        }
        if (!terraformingAllowed && (fills > 0 || digs > 0)) {
            return -1;
        }
        return fills * 2 + digs; // zásyp stojí i materiál
    }

    /** Naplánuje výkopy překážek a zásypy děr v podlaze. */
    private void planTerraform(BotContext ctx, Bot bot) {
        WorldView world = ctx.worldView();
        for (BlockPos space : HouseBlueprint.clearVolume(origin)) {
            if (world.traitsAt(space).solid()) {
                terraform.add(new MineBlockTask(space));
            }
        }
        for (BlockPos ground : HouseBlueprint.groundColumns(origin)) {
            if (!world.traitsAt(ground).solid()) {
                terraform.add(new PlaceBlockTask(ground));
            }
        }
        placements.addAll(HouseBlueprint.placements(origin));
    }

    /** Dojít doprostřed staveniště. */
    private void gotoSite(BotContext ctx) {
        BlockPos stand = HouseBlueprint.standPoint(origin);
        Vec3 pos = ctx.position();
        if (pos.toBlockPos().distanceSquared(stand) <= 2) {
            ctx.navigator().stop();
            phase = terraform.isEmpty() ? Phase.BUILD : Phase.TERRAFORM;
            return;
        }
        ctx.navigator().navigateTo(pos, stand);
        if (!ctx.navigator().navigating()) {
            cooldownTicks = 1200; // staveniště nedostupné
            phase = Phase.DONE;
        }
    }

    /** Odbaví frontu tasků (terraform) a přepne do další fáze. */
    private void tickTasks(BotContext ctx, Deque<BotTask> queue, Phase next) {
        if (current == null) {
            current = queue.poll();
            if (current == null) {
                phase = next;
                return;
            }
            if (current instanceof PlaceBlockTask) {
                ctx.inventory().equipBuildingBlock(ctx.serverView().latest());
            }
        }
        if (current.tick(ctx)) {
            current = null;
        }
    }

    /** Staví domek blok po bloku z místa uvnitř. */
    private void tickBuild(BotContext ctx, Bot bot) {
        if (current == null) {
            BlockPos next = placements.poll();
            if (next == null) {
                planFurnish(ctx);
                phase = Phase.FURNISH;
                return;
            }
            // Průchozí pozici přeskočit jen pokud tam už blok je.
            if (ctx.worldView() != null && ctx.worldView().traitsAt(next).solid()) {
                return;
            }
            if (!ctx.inventory().equipBuildingBlock(ctx.serverView().latest())) {
                // Došly bloky – dům zůstal rozestavěný, zkusit dostavět jindy.
                cooldownTicks = 2400;
                phase = Phase.DONE;
                if (!announcedOutOfBlocks && ctx.rng().chance(0.5)) {
                    announcedOutOfBlocks = true;
                    ctx.chat().say("dosly mi bloky, dum dostavim pozdejc");
                }
                return;
            }
            current = new PlaceBlockTask(next);
        }
        if (current.tick(ctx)) {
            current = null;
            placedCount++;
            ctx.stats().addPlaced();
        }
    }

    /** Naplánuje vybavení: dveře do otvoru, pochodeň a postel dovnitř. */
    private void planFurnish(BotContext ctx) {
        var snapshot = ctx.serverView().latest();
        if (snapshot == null) {
            return;
        }
        if (snapshot.hasItem(m -> m.name().endsWith("_DOOR"))) {
            furnish.add(new FurnishStep(m -> m.name().endsWith("_DOOR"),
                    origin.offset(HouseBlueprint.DOOR_X, 0, 0)));
        }
        if (snapshot.hasItem(m -> m == org.bukkit.Material.TORCH)) {
            furnish.add(new FurnishStep(m -> m == org.bukkit.Material.TORCH,
                    origin.offset(2, 0, 1)));
        }
        if (snapshot.hasItem(m -> m.name().endsWith("_BED"))) {
            furnish.add(new FurnishStep(m -> m.name().endsWith("_BED"),
                    origin.offset(1, 0, 1)));
        }
    }

    /** Osadí dveře/pochodeň/postel; co nejde vybavit, přeskočí. */
    private void tickFurnish(BotContext ctx, Bot bot) {
        if (current == null) {
            FurnishStep step = furnish.poll();
            if (step == null) {
                finishHouse(ctx, bot);
                return;
            }
            if (!ctx.inventory().equipMatching(ctx.serverView().latest(), step.item())) {
                return; // item mezitím zmizel – další krok příští tick
            }
            current = new PlaceBlockTask(step.target());
        }
        if (current.tick(ctx)) {
            current = null;
        }
    }

    /** Hotovo: paměť, oznámení, konec cíle. */
    private void finishHouse(BotContext ctx, Bot bot) {
        BlockPos stand = HouseBlueprint.standPoint(origin);
        if (ctx.worldView() != null) {
            bot.memory().remember(MemoryKind.HOME, ctx.worldView().worldName(),
                    stand.x(), stand.y(), stand.z(), null,
                    Map.of("type", "house"), 0.9);
        }
        if (ctx.rng().chance(0.7)) {
            ctx.chat().say("hotovo! mam vlastni dum :)");
        }
        ctx.gainExperience(dev.botalive.core.personality.PersonalityEvolution
                .BotExperience.HOUSE_BUILT);
        houseBuilt = true;
        cooldownTicks = 6000;
        phase = Phase.DONE;
    }

    /** Bot už někde dům má? */
    private boolean hasHouse(Bot bot, BotContext ctx) {
        List<MemoryRecord> homes = bot.memory().recall(MemoryKind.HOME);
        for (MemoryRecord home : homes) {
            if ("house".equals(home.data().get("type"))) {
                return true;
            }
        }
        return false;
    }
}
