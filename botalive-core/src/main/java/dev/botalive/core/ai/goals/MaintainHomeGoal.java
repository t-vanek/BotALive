package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.memory.MemoryRecord;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.build.HouseBlueprint;
import dev.botalive.core.build.HouseFacing;
import dev.botalive.core.chat.PhraseCategory;
import dev.botalive.core.settlement.SettlementService;
import dev.botalive.core.tasks.BotTask;
import dev.botalive.core.tasks.MineBlockTask;
import dev.botalive.core.tasks.PlaceBlockTask;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.BlockTraits;
import dev.botalive.core.world.WorldView;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Set;

/**
 * Údržba vlastního domu – creeper díry, vytlučené zdi, chybějící dveře.
 *
 * <p>Bot čas od času (a jen když je poblíž domova) projde svůj dům proti
 * plánu ({@link HouseBlueprint}): doplní chybějící bloky zdí a střechy,
 * vykope, co se připletlo do dveřního otvoru, a osadí nové dveře, má-li je
 * u sebe. Uzavírá smyčku postav → bydli → <b>opravuj</b>; bez ní by po
 * prvním creeperu vesnice nevratně chátraly.</p>
 *
 * <p>Orientace domu: člen vesnice ji zná z parcely, novější sólo domy
 * z HOME dat ({@code ox/oy/oz/facing}); starým domům bez metadat se origin
 * zrekonstruuje z bodu uložení (stand point, sever).</p>
 */
public final class MaintainHomeGoal extends AbstractGoal {

    /** Kolik bloků nejvýš opravit za jednu seanci (zbytek příště). */
    private static final int MAX_REPAIRS = 16;
    /** Pauza mezi kontrolami, když bylo všechno v pořádku. */
    private static final int CALM_COOLDOWN = 12000;
    /** Pauza po opravě (a mezi neúspěchy). */
    private static final int BUSY_COOLDOWN = 4000;

    private enum Phase { GOTO, REPAIR, DOOR, DONE }

    private Phase phase = Phase.GOTO;
    private BlockPos origin;
    private HouseFacing facing = HouseFacing.NORTH;
    private final Deque<BotTask> repairs = new ArrayDeque<>();
    private BotTask current;
    private int cooldownTicks;
    private int repairedCount;
    private boolean needsDoor;

    /** Vytvoří cíl. */
    public MaintainHomeGoal() {
        super("maintain");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        // Opravuje se za světla (stejné okno jako stavba).
        long time = ctx.worldTime();
        if (time >= 11500 && time <= 23000) {
            return 0;
        }
        MemoryRecord home = houseRecord(bot);
        if (home == null || ctx.worldView() == null
                || !ctx.worldView().worldName().equals(home.world())) {
            return 0;
        }
        // Kontroluje se jen poblíž domova (ráno po probuzení je bot doma).
        BlockPos homePos = new BlockPos(home.x(), home.y(), home.z());
        if (ctx.position().toBlockPos().distanceSquared(homePos) > 64 * 64) {
            return 0;
        }
        double caution = bot.personality().trait(Trait.CAUTION);
        double intelligence = bot.personality().trait(Trait.INTELLIGENCE);
        return 5 + caution * 5 + intelligence * 3;
    }

    @Override
    public void start(Bot bot) {
        phase = Phase.GOTO;
        repairs.clear();
        current = null;
        repairedCount = 0;
        needsDoor = false;
        resolveOriginAndFacing(bot);
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        if (origin == null || ctx.worldView() == null) {
            cooldownTicks = CALM_COOLDOWN;
            phase = Phase.DONE;
            return;
        }
        switch (phase) {
            case GOTO -> gotoHome(ctx, bot);
            case REPAIR -> tickRepair(ctx);
            case DOOR -> tickDoor(ctx, bot);
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
        repairs.clear();
        super.stop(bot);
    }

    @Override
    public boolean finished(Bot bot) {
        return phase == Phase.DONE;
    }

    @Override
    public String explain(Bot bot) {
        return switch (phase) {
            case GOTO -> "jdu zkontrolovat dům";
            case REPAIR -> "opravuju dům (zbývá " + (repairs.size()
                    + (current != null ? 1 : 0)) + " bloků)";
            case DOOR -> "věším nové dveře";
            case DONE -> null;
        };
    }

    // ==================================================================

    /** Zjistí origin a orientaci domu: parcela → HOME data → rekonstrukce. */
    private void resolveOriginAndFacing(Bot bot) {
        BotContext ctx = ctx(bot);
        origin = null;
        facing = HouseFacing.NORTH;
        SettlementService settlements = ctx.settlements();
        if (settlements != null) {
            var plot = settlements.claimedPlot(bot.id());
            if (plot.isPresent() && plot.get().origin() != null) {
                origin = plot.get().origin();
                facing = plot.get().facing();
                return;
            }
        }
        MemoryRecord home = houseRecord(bot);
        if (home == null) {
            return;
        }
        String ox = home.data().get("ox");
        if (ox != null) {
            try {
                origin = new BlockPos(Integer.parseInt(ox),
                        Integer.parseInt(home.data().get("oy")),
                        Integer.parseInt(home.data().get("oz")));
                String storedFacing = home.data().get("facing");
                if (storedFacing != null) {
                    facing = HouseFacing.valueOf(storedFacing);
                }
                return;
            } catch (RuntimeException e) {
                origin = null; // poškozená metadata – spadnout na rekonstrukci
            }
        }
        // Starý dům bez metadat: HOME je stand point, stavělo se na sever.
        origin = new BlockPos(home.x(), home.y(), home.z()).offset(-2, 0, -2);
    }

    /** Dojít k domu a sepsat, co chybí. */
    private void gotoHome(BotContext ctx, Bot bot) {
        BlockPos stand = HouseBlueprint.standPoint(origin, facing);
        if (ctx.position().toBlockPos().distanceSquared(stand) > 9) {
            ctx.navigator().navigateTo(ctx.position(), stand);
            if (!ctx.navigator().navigating()) {
                cooldownTicks = BUSY_COOLDOWN;
                phase = Phase.DONE;
            }
            return;
        }
        ctx.navigator().stop();
        planRepairs(ctx, bot);
    }

    /** Diff domu proti plánu: díry ve zdech, ucpaný vchod, chybějící dveře. */
    private void planRepairs(BotContext ctx, Bot bot) {
        WorldView world = ctx.worldView();
        BlockPos doorBottom = HouseBlueprint.doorBottom(origin, facing);
        BlockPos doorTop = doorBottom.up();
        List<BlockPos> structure = HouseBlueprint.placements(origin, facing);
        Set<BlockPos> doorway = Set.of(doorBottom, doorTop);

        int missing = 0;
        for (BlockPos pos : structure) {
            BlockTraits traits = world.traitsAt(pos);
            if (traits == BlockTraits.UNKNOWN) {
                cooldownTicks = BUSY_COOLDOWN; // okolí nedotažené – příště
                phase = Phase.DONE;
                return;
            }
            if (!traits.solid() && missing < MAX_REPAIRS) {
                repairs.add(new PlaceBlockTask(pos));
                missing++;
            }
        }
        // Vchod: co tam nepatří, vykopat; dveře osadit, pokud chybí a jsou.
        for (BlockPos pos : doorway) {
            var material = world.materialAt(pos);
            if (world.traitsAt(pos).solid()) {
                repairs.add(new MineBlockTask(pos));
            } else if (pos.equals(doorBottom)
                    && (material == null || !material.name().endsWith("_DOOR"))) {
                var snapshot = ctx.serverView().latest();
                needsDoor = snapshot != null
                        && snapshot.hasItem(m -> m.name().endsWith("_DOOR"));
            }
        }
        if (repairs.isEmpty() && !needsDoor) {
            cooldownTicks = CALM_COOLDOWN; // všechno drží pohromadě
            phase = Phase.DONE;
            return;
        }
        if (ctx.rng().chance(0.5)) {
            ctx.chat().sayFrom(PhraseCategory.HOME_REPAIR, null);
        }
        phase = Phase.REPAIR;
    }

    /** Opravuje frontu: kopání ucpávek a doplňování bloků. */
    private void tickRepair(BotContext ctx) {
        if (current == null) {
            current = repairs.poll();
            if (current == null) {
                phase = needsDoor ? Phase.DOOR : Phase.DONE;
                if (phase == Phase.DONE) {
                    cooldownTicks = BUSY_COOLDOWN;
                }
                return;
            }
            if (current instanceof PlaceBlockTask
                    && !ctx.inventory().equipBuildingBlock(ctx.serverView().latest())) {
                // Došly bloky – zbytek oprav příště.
                current = null;
                repairs.clear();
                phase = needsDoor ? Phase.DOOR : Phase.DONE;
                cooldownTicks = BUSY_COOLDOWN;
                return;
            }
        }
        if (current.tick(ctx)) {
            if (current instanceof PlaceBlockTask) {
                repairedCount++;
                ctx.stats().addPlaced();
            }
            current = null;
        }
    }

    /** Osadí nové dveře do prázdného otvoru. */
    private void tickDoor(BotContext ctx, Bot bot) {
        if (current == null) {
            if (!ctx.inventory().equipMatching(ctx.serverView().latest(),
                    m -> m.name().endsWith("_DOOR"))) {
                cooldownTicks = BUSY_COOLDOWN;
                phase = Phase.DONE;
                return;
            }
            current = new PlaceBlockTask(HouseBlueprint.doorBottom(origin, facing));
        }
        if (current.tick(ctx)) {
            current = null;
            cooldownTicks = BUSY_COOLDOWN;
            phase = Phase.DONE;
        }
    }

    /** HOME záznam typu house. */
    private static MemoryRecord houseRecord(Bot bot) {
        for (MemoryRecord record : bot.memory().recall(MemoryKind.HOME)) {
            if ("house".equals(record.data().get("type"))) {
                return record;
            }
        }
        return null;
    }
}
