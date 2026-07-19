package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.ai.BotNeeds;
import dev.botalive.core.build.HouseBlueprint;
import dev.botalive.core.build.WellBlueprint;
import dev.botalive.core.chat.PhraseCategory;
import dev.botalive.core.settlement.SettlementService;
import dev.botalive.core.settlement.SettlementService.ProjectKind;
import dev.botalive.core.tasks.BotTask;
import dev.botalive.core.tasks.MineBlockTask;
import dev.botalive.core.tasks.PlaceBlockTask;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.WorldDimension;
import dev.botalive.core.world.WorldView;

import org.bukkit.Material;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.function.Predicate;

/**
 * Společná stavba pro sídlo – zatím studna návsi (růstová roadmapa, fáze B).
 *
 * <p>Projekt vlastní {@link SettlementService} (nástěnka po vzoru trhu:
 * první stavitel bere, přerušení uvolňuje dalšímu, restart taky – fyzická
 * stavba je autorita). Cíl je zeštíhlený {@code BuildHouseGoal}: staveniště
 * je dané projektem, takže odpadá hledání parcely; zbývá dojít, srovnat
 * terén, položit věnec a pochodeň. Dokončení hlásí službě – povýšení sídla
 * (osada→vesnice) ohlásí stavitel v chatu.</p>
 */
public final class CommunalBuildGoal extends AbstractGoal {

    private enum Phase { CLAIM, GOTO, TERRAFORM, STEP_IN, BUILD, FINISH, DONE }

    /** Rezerva bloků nad čistou spotřebu věnce (zásypy podlahy). */
    private static final int BLOCK_RESERVE = 8;

    private Phase phase = Phase.DONE;
    private SettlementService.ProjectInfo project;
    private final Deque<BotTask> terraform = new ArrayDeque<>();
    private final Deque<BlockPos> placements = new ArrayDeque<>();
    /** Vybavení sýpky (dveře, dvojtruhla, pochodeň) – co chybí, přeskočí se. */
    private final Deque<FurnishStep> furnish = new ArrayDeque<>();
    private BotTask current;
    private int cooldownTicks;
    private boolean claimed;
    private java.util.UUID selfId;

    /** Krok vybavení: čím (predikát itemu) a kam. */
    private record FurnishStep(Predicate<Material> item, BlockPos target) {
    }

    /** Vytvoří cíl. */
    public CommunalBuildGoal() {
        super("communal-build");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks--;
            return 0;
        }
        if (ctx.worldView() == null || ctx.dimension() != WorldDimension.OVERWORLD) {
            return 0;
        }
        // Staví se za světla, jako domy.
        long time = ctx.worldTime();
        if (time >= 11500 && time <= 23000) {
            return 0;
        }
        if (ctx.settlements() == null) {
            return 0;
        }
        var needed = ctx.settlements().neededProject(bot.id());
        if (needed.isEmpty()) {
            return 0;
        }
        BotNeeds needs = BotNeeds.assess(ctx.serverView().latest());
        if (needs.buildingBlocks() < blocksNeededFor(needed.get().kind()) + BLOCK_RESERVE) {
            return 0;
        }
        if (needed.get().kind() == ProjectKind.GRANARY
                && ctx.inventory().countItem(ctx.serverView().latest(),
                        Material.CHEST) < 2) {
            return 0; // sýpka bez truhel je jen kůlna
        }
        double helpfulness = bot.personality().trait(Trait.HELPFULNESS);
        // Závazek: kdo si stavbu zamluvil, drží se jí – bez bonusu si stavitelé
        // rozdělanou studnu přebírali po pár vteřinách (utility kolotoč).
        return 9 + helpfulness * 9 + (claimed ? 10 : 0);
    }

    @Override
    public void start(Bot bot) {
        phase = Phase.CLAIM;
        project = null;
        claimed = false;
        selfId = bot.id();
        terraform.clear();
        placements.clear();
        furnish.clear();
        furnishPlanned = false;
        current = null;
    }

    private boolean furnishPlanned;
    /** Klíč posledního ohlášeného projektu – hláška jen pro nový, ne re-claim. */
    private long lastAnnouncedProject = -1;

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        switch (phase) {
            case CLAIM -> tickClaim(ctx, bot);
            case GOTO -> tickGoto(ctx);
            case TERRAFORM -> tickTasks(ctx, Phase.STEP_IN);
            case STEP_IN -> tickStepIn(ctx);
            case BUILD -> tickBuild(ctx);
            case FINISH -> tickFinish(ctx, bot);
            case DONE -> {
            }
        }
    }

    /** @return buňka šachty uprostřed věnce – stanoviště stavitele */
    private BlockPos wellCenter() {
        return project.origin().offset(WellBlueprint.SIZE / 2, 0, WellBlueprint.SIZE / 2);
    }

    // ------------------------------------------------ per-druh geometrie

    private static int blocksNeededFor(ProjectKind kind) {
        return kind == ProjectKind.WELL
                ? WellBlueprint.blocksNeeded() : HouseBlueprint.blocksNeeded();
    }

    private List<BlockPos> placementsFor() {
        return project.kind() == ProjectKind.WELL
                ? WellBlueprint.placements(project.origin())
                : HouseBlueprint.placements(project.origin(), project.facing());
    }

    private List<BlockPos> clearVolumeFor() {
        return project.kind() == ProjectKind.WELL
                ? WellBlueprint.clearVolume(project.origin())
                : HouseBlueprint.clearVolume(project.origin());
    }

    private List<BlockPos> groundColumnsFor() {
        return project.kind() == ProjectKind.WELL
                ? WellBlueprint.groundColumns(project.origin())
                : HouseBlueprint.groundColumns(project.origin());
    }

    /** Stanoviště stavitele: šachta studny, resp. vnitřek sýpky. */
    private BlockPos standFor() {
        return project.kind() == ProjectKind.WELL
                ? wellCenter()
                : HouseBlueprint.standPoint(project.origin(), project.facing());
    }

    private void tickClaim(BotContext ctx, Bot bot) {
        var needed = ctx.settlements().neededProject(bot.id());
        if (needed.isEmpty()) {
            phase = Phase.DONE;
            return;
        }
        project = needed.get();
        if (!ctx.settlements().claimProject(project.settlementId(), project.kind(),
                bot.id())) {
            cooldownTicks = 600; // předběhl mě soused – ať staví on
            phase = Phase.DONE;
            return;
        }
        claimed = true;
        String name = settlementName(ctx, bot);
        long announceKey = project.settlementId() * 31 + project.kind().ordinal();
        if (name != null && announceKey != lastAnnouncedProject && ctx.rng().chance(0.6)) {
            // Hlásí se jen NOVÝ projekt – opakované zamluvení téhož (návrat
            // ke stavbě, marné pokusy) by chat zaplavilo.
            lastAnnouncedProject = announceKey;
            ctx.chat().sayFrom(project.kind() == ProjectKind.WELL
                    ? PhraseCategory.SETTLEMENT_WELL_START
                    : PhraseCategory.SETTLEMENT_GRANARY_START, name);
        }
        phase = Phase.GOTO;
    }

    private void tickGoto(BotContext ctx) {
        // Ke staveništi se chodí „do okruhu" – pevné stanoviště umělo být
        // zrovna nedostupné (křoví, soused) a stavba se zbytečně vzdávala.
        BlockPos stand = standFor();
        if (ctx.position().toBlockPos().distanceSquared(stand) <= 9) {
            ctx.navigator().stop();
            planWork(ctx);
            phase = terraform.isEmpty() ? Phase.STEP_IN : Phase.TERRAFORM;
            return;
        }
        ctx.navigator().navigateTo(ctx.position(),
                dev.botalive.core.pathfinding.PathGoal.near(stand, 2));
        if (!ctx.navigator().navigating()) {
            // Staveniště nedostupné (typicky v backoffu nedosažitelnosti po
            // dálkovém selhání) – cooldown musí být delší než backoff, jinak
            // se claim/hláška točí naprázdno každou minutu.
            giveUp(ctx, 2400);
        }
    }

    /**
     * Po srovnání terénu si stavitel stoupne DO šachty (střed věnce zůstává
     * volný) – odtud je celý věnec i pochodeň na dosah ruky; z boku byl
     * protější roh na hraně dosahu.
     */
    private void tickStepIn(BotContext ctx) {
        BlockPos center = standFor();
        BlockPos feet = ctx.position().toBlockPos();
        if (feet.equals(center)) {
            ctx.navigator().stop();
            // Rozpočet: vzdát se dřív, než zůstane věnec poloviční.
            if (BotNeeds.assess(ctx.serverView().latest()).buildingBlocks()
                    < placements.size()) {
                giveUp(ctx, 2400);
                return;
            }
            phase = Phase.BUILD;
            return;
        }
        ctx.navigator().navigateTo(ctx.position(), center);
        if (!ctx.navigator().navigating() && !ctx.navigator().hasPath()) {
            giveUp(ctx, 1200);
        }
    }

    /** Naplánuje výkopy, zásypy a pokládku struktury (vzor BuildHouseGoal). */
    private void planWork(BotContext ctx) {
        WorldView world = ctx.worldView();
        List<BlockPos> structurePlacements = placementsFor();
        var structure = new java.util.HashSet<>(structurePlacements);
        for (BlockPos space : clearVolumeFor()) {
            if (structure.contains(space)) {
                continue;
            }
            if (world.traitsAt(space).solid()) {
                terraform.add(new MineBlockTask(space));
            }
        }
        for (BlockPos ground : groundColumnsFor()) {
            if (!world.traitsAt(ground).solid()) {
                terraform.add(new PlaceBlockTask(ground));
            }
        }
        placements.addAll(structurePlacements);
    }

    private void tickTasks(BotContext ctx, Phase next) {
        if (current == null) {
            current = terraform.poll();
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

    private void tickBuild(BotContext ctx) {
        if (current == null) {
            BlockPos next = placements.poll();
            if (next == null) {
                phase = Phase.FINISH;
                return;
            }
            if (ctx.worldView() != null && ctx.worldView().traitsAt(next).solid()) {
                return; // blok už stojí (návrat ke stavbě)
            }
            if (!ctx.inventory().equipBuildingBlock(ctx.serverView().latest())) {
                giveUp(ctx, 2400); // došly bloky – uvolnit projekt dalšímu
                return;
            }
            current = new PlaceBlockTask(next);
        }
        if (current.tick(ctx)) {
            current = null;
            ctx.stats().addPlaced();
        }
    }

    private void tickFinish(BotContext ctx, Bot bot) {
        if (current != null) {
            if (!current.tick(ctx)) {
                return;
            }
            current = null;
        }
        if (!furnishPlanned) {
            planFurnish();
            furnishPlanned = true;
        }
        // Vybavení je bonus, ne podmínka: co chybí v batohu, přeskočí se.
        FurnishStep step;
        while ((step = furnish.poll()) != null) {
            if (ctx.worldView() != null
                    && ctx.worldView().traitsAt(step.target()).solid()) {
                continue; // už osazeno (návrat k rozdělané stavbě)
            }
            if (!ctx.inventory().equipMatching(ctx.serverView().latest(), step.item())) {
                continue;
            }
            current = new PlaceBlockTask(step.target());
            return;
        }
        finishProject(ctx, bot);
    }

    /** Vybavení podle druhu: studna pochodeň, sýpka dveře + dvojtruhlu + světlo. */
    private void planFurnish() {
        if (project.kind() == ProjectKind.WELL) {
            furnish.add(new FurnishStep(m -> m == Material.TORCH,
                    WellBlueprint.torchSpot(project.origin())));
            return;
        }
        furnish.add(new FurnishStep(m -> m.name().endsWith("_DOOR"),
                HouseBlueprint.doorBottom(project.origin(), project.facing())));
        BlockPos chest = HouseBlueprint.bedSpot(project.origin(), project.facing());
        furnish.add(new FurnishStep(m -> m == Material.CHEST, chest));
        furnish.add(new FurnishStep(m -> m == Material.CHEST, chestNeighbor(chest)));
        furnish.add(new FurnishStep(m -> m == Material.TORCH,
                HouseBlueprint.torchSpot(project.origin(), project.facing())));
    }

    /**
     * Druhá půlka dvojtruhly: vnitřní soused truhly, který není stanovištěm
     * stavitele ani místem pochodně – v interiéru 2×2 vyjde jednoznačně.
     */
    private BlockPos chestNeighbor(BlockPos chest) {
        BlockPos stand = HouseBlueprint.standPoint(project.origin(), project.facing());
        BlockPos torch = HouseBlueprint.torchSpot(project.origin(), project.facing());
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
            BlockPos candidate = chest.offset(d[0], 0, d[1]);
            int lx = candidate.x() - project.origin().x();
            int lz = candidate.z() - project.origin().z();
            if (lx >= 1 && lx <= HouseBlueprint.SIZE - 2
                    && lz >= 1 && lz <= HouseBlueprint.SIZE - 2
                    && !candidate.equals(stand) && !candidate.equals(torch)) {
                return candidate;
            }
        }
        return chest.offset(1, 0, 0);
    }

    private void finishProject(BotContext ctx, Bot bot) {
        var tier = ctx.settlements().projectFinished(project.settlementId(), project.kind());
        claimed = false;
        String name = settlementName(ctx, bot);
        if (name != null) {
            ctx.chat().sayFrom(project.kind() == ProjectKind.WELL
                    ? PhraseCategory.SETTLEMENT_WELL_DONE
                    : PhraseCategory.SETTLEMENT_GRANARY_DONE, name);
        }
        tier.ifPresent(t -> dev.botalive.core.settlement.SettlementAnnouncer
                .sayTierUp(ctx.chat(), t, name));
        ctx.gainExperience(dev.botalive.core.personality.PersonalityEvolution
                .BotExperience.HOUSE_BUILT);
        cooldownTicks = 6000;
        phase = Phase.DONE;
    }

    private void giveUp(BotContext ctx, int cooldown) {
        if (claimed && project != null) {
            ctx.settlements().releaseProject(project.settlementId(), project.kind(), selfId);
            claimed = false;
        }
        cooldownTicks = cooldown;
        phase = Phase.DONE;
    }

    private String settlementName(BotContext ctx, Bot bot) {
        return ctx.settlements().settlementOf(bot.id())
                .map(SettlementService.SettlementInfo::name).orElse(null);
    }

    @Override
    public void stop(Bot bot) {
        // Zamluvení se při přepnutí cíle DRŽÍ (závazek + bonus utility) –
        // stavitel se ke studni vrátí; kdyby nadobro zmizel, zamluvení ve
        // službě expiruje a projekt si vezme soused. start() naváže tam,
        // kde stavba skončila (položené bloky se přeskakují).
        ctx(bot).navigator().stop();
        current = null;
    }

    @Override
    public boolean blocksRelocation() {
        return true;
    }

    @Override
    public boolean finished(Bot bot) {
        return phase == Phase.DONE;
    }

    @Override
    public String explain(Bot bot) {
        return project != null && project.kind() == ProjectKind.GRANARY
                ? "stavím sýpku pro sídlo" : "stavím studnu pro sídlo";
    }
}
