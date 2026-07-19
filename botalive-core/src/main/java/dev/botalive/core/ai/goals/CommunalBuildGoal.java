package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.ai.BotNeeds;
import dev.botalive.core.build.WellBlueprint;
import dev.botalive.core.chat.PhraseCategory;
import dev.botalive.core.settlement.SettlementService;
import dev.botalive.core.tasks.BotTask;
import dev.botalive.core.tasks.MineBlockTask;
import dev.botalive.core.tasks.PlaceBlockTask;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.WorldDimension;
import dev.botalive.core.world.WorldView;

import java.util.ArrayDeque;
import java.util.Deque;

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

    private enum Phase { CLAIM, GOTO, TERRAFORM, BUILD, FINISH, DONE }

    /** Rezerva bloků nad čistou spotřebu věnce (zásypy podlahy). */
    private static final int BLOCK_RESERVE = 8;

    private Phase phase = Phase.DONE;
    private SettlementService.ProjectInfo project;
    private final Deque<BotTask> terraform = new ArrayDeque<>();
    private final Deque<BlockPos> placements = new ArrayDeque<>();
    private BotTask current;
    private int cooldownTicks;
    private boolean claimed;
    private java.util.UUID selfId;

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
        if (ctx.settlements() == null
                || ctx.settlements().neededProject(bot.id()).isEmpty()) {
            return 0;
        }
        BotNeeds needs = BotNeeds.assess(ctx.serverView().latest());
        if (needs.buildingBlocks() < WellBlueprint.blocksNeeded() + BLOCK_RESERVE) {
            return 0;
        }
        double helpfulness = bot.personality().trait(Trait.HELPFULNESS);
        return 9 + helpfulness * 9;
    }

    @Override
    public void start(Bot bot) {
        phase = Phase.CLAIM;
        project = null;
        claimed = false;
        selfId = bot.id();
        terraform.clear();
        placements.clear();
        current = null;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        switch (phase) {
            case CLAIM -> tickClaim(ctx, bot);
            case GOTO -> tickGoto(ctx);
            case TERRAFORM -> tickTasks(ctx, Phase.BUILD);
            case BUILD -> tickBuild(ctx);
            case FINISH -> tickFinish(ctx, bot);
            case DONE -> {
            }
        }
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
        if (name != null && ctx.rng().chance(0.6)) {
            ctx.chat().sayFrom(PhraseCategory.SETTLEMENT_WELL_START, name);
        }
        phase = Phase.GOTO;
    }

    private void tickGoto(BotContext ctx) {
        BlockPos stand = WellBlueprint.standPoint(project.origin());
        if (ctx.position().toBlockPos().distanceSquared(stand) <= 2) {
            ctx.navigator().stop();
            planWork(ctx);
            phase = terraform.isEmpty() ? Phase.BUILD : Phase.TERRAFORM;
            return;
        }
        ctx.navigator().navigateTo(ctx.position(), stand);
        if (!ctx.navigator().navigating()) {
            cooldownTicks = 1200; // staveniště nedostupné – zkusit jindy
            phase = Phase.DONE;
        }
    }

    /** Naplánuje výkopy, zásypy a pokládku věnce (vzor BuildHouseGoal). */
    private void planWork(BotContext ctx) {
        WorldView world = ctx.worldView();
        var structure = new java.util.HashSet<>(WellBlueprint.placements(project.origin()));
        for (BlockPos space : WellBlueprint.clearVolume(project.origin())) {
            if (structure.contains(space)) {
                continue;
            }
            if (world.traitsAt(space).solid()) {
                terraform.add(new MineBlockTask(space));
            }
        }
        for (BlockPos ground : WellBlueprint.groundColumns(project.origin())) {
            if (!world.traitsAt(ground).solid()) {
                terraform.add(new PlaceBlockTask(ground));
            }
        }
        placements.addAll(WellBlueprint.placements(project.origin()));
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
            if (current.tick(ctx)) {
                current = null;
            } else {
                return;
            }
            finishProject(ctx, bot);
            return;
        }
        // Pochodeň na obrubu – bez ní stavba platí taky (dekorace, ne podmínka).
        BlockPos torch = WellBlueprint.torchSpot(project.origin());
        if (ctx.worldView() != null && !ctx.worldView().traitsAt(torch).solid()
                && ctx.inventory().equipMatching(ctx.serverView().latest(),
                        m -> m == org.bukkit.Material.TORCH)) {
            current = new PlaceBlockTask(torch);
            return;
        }
        finishProject(ctx, bot);
    }

    private void finishProject(BotContext ctx, Bot bot) {
        var tier = ctx.settlements().projectFinished(project.settlementId(), project.kind());
        claimed = false;
        String name = settlementName(ctx, bot);
        if (name != null) {
            ctx.chat().sayFrom(PhraseCategory.SETTLEMENT_WELL_DONE, name);
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
        BotContext ctx = ctx(bot);
        if (claimed && project != null && phase != Phase.DONE) {
            ctx.settlements().releaseProject(project.settlementId(), project.kind(),
                    bot.id());
            claimed = false;
        }
        ctx.navigator().stop();
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
        return "stavím studnu pro sídlo";
    }
}
