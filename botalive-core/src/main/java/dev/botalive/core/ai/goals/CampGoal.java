package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.chat.PhraseCategory;
import dev.botalive.core.tasks.BotTask;
import dev.botalive.core.tasks.PlaceBlockTask;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.WorldDimension;
import dev.botalive.core.world.WorldView;

import org.bukkit.Material;

/**
 * Tábor na cestách – bot daleko od domova si na noc rozdělá oheň.
 *
 * <p>Noční pochod přes půl mapy domů je zbytečné martyrium (a přesně tak
 * vznikaly noční smrti na srázech): kdo má domov dál než
 * {@value #FAR_FROM_HOME} bloků, večer si vybere rovné místo vedle sebe,
 * položí ohniště (fallback pochodeň – tu nosí každý) a přečká noc u ohně
 * s pohledem do plamenů. Ráno jde dál; ohniště zůstává ve světě jako stopa
 * po táboření. Bezdomovce řeší {@code BuildShelterGoal} (zdi), tábor je pro
 * poutníky. Bez postele – spaní u ohně je v2.</p>
 */
public final class CampGoal extends AbstractGoal {

    private enum Phase { SETUP, CAMP, DONE }

    /** Od jaké vzdálenosti od domova se místo návratu táboří (bloky). */
    private static final int FAR_FROM_HOME = 96;
    /** Večerní okno, kdy má smysl tábor zakládat (do půlnoci). */
    private static final long DUSK = 11_000L;
    private static final long MIDNIGHT = 18_000L;
    /** Konec noci – tábor se ráno balí. */
    private static final long DAWN = 23_000L;

    private Phase phase = Phase.DONE;
    private BlockPos fireSpot;
    private BotTask current;
    private int cooldownTicks;
    private int gazeCooldown;

    /** Vytvoří cíl. */
    public CampGoal() {
        super("camp");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        if (ctx.worldView() == null || ctx.dimension() != WorldDimension.OVERWORLD) {
            return 0;
        }
        long time = ctx.worldTime();
        boolean evening = time >= DUSK && time <= MIDNIGHT;
        boolean camping = phase == Phase.CAMP && time > DUSK && time < DAWN;
        if (!evening && !camping) {
            return 0;
        }
        BlockPos pos = ctx.position().toBlockPos();
        var home = bot.memory().recallNearest(MemoryKind.HOME,
                ctx.worldView().worldName(), pos.x(), pos.y(), pos.z());
        if (home.isEmpty()) {
            return 0; // bez domova staví přístřešek, ne tábor
        }
        double distance = Math.sqrt(home.get().distanceSquared(pos.x(), pos.y(), pos.z()));
        if (distance < FAR_FROM_HOME) {
            return 0;
        }
        if (!hasFireSource(ctx)) {
            return 0;
        }
        // Opatrní táboří raději, než by nočně putovali; rozdělaný tábor drží.
        double caution = bot.personality().trait(Trait.CAUTION);
        return 16 + caution * 22 + (camping ? 8 : 0);
    }

    private boolean hasFireSource(BotContext ctx) {
        var snapshot = ctx.serverView().latest();
        return ctx.inventory().countItem(snapshot, Material.CAMPFIRE) > 0
                || ctx.inventory().countItem(snapshot, Material.TORCH) > 0;
    }

    @Override
    public void start(Bot bot) {
        phase = Phase.SETUP;
        fireSpot = null;
        current = null;
        gazeCooldown = 0;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        switch (phase) {
            case SETUP -> tickSetup(ctx, bot);
            case CAMP -> tickCamp(ctx);
            case DONE -> {
            }
        }
    }

    private void tickSetup(BotContext ctx, Bot bot) {
        if (fireSpot == null) {
            fireSpot = pickFireSpot(ctx);
            if (fireSpot == null) {
                cooldownTicks = 1200; // kolem není kousek rovné země
                phase = Phase.DONE;
                return;
            }
            var snapshot = ctx.serverView().latest();
            if (!ctx.inventory().equipMatching(snapshot, m -> m == Material.CAMPFIRE)
                    && !ctx.inventory().equipMatching(snapshot, m -> m == Material.TORCH)) {
                cooldownTicks = 1200;
                phase = Phase.DONE;
                return;
            }
            current = new PlaceBlockTask(fireSpot);
            if (ctx.rng().chance(0.7)) {
                ctx.chat().sayFrom(PhraseCategory.CAMP_SETUP, null);
            }
        }
        if (current != null && current.tick(ctx)) {
            current = null;
            phase = Phase.CAMP;
        }
    }

    /**
     * Rovné místo pro oheň hned vedle bota: průchozí buňka s pevnou zemí,
     * na kterou není šlápnuto.
     */
    private BlockPos pickFireSpot(BotContext ctx) {
        WorldView world = ctx.worldView();
        BlockPos feet = ctx.position().toBlockPos();
        for (int[] d : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1},
                {1, 1}, {-1, -1}, {1, -1}, {-1, 1}}) {
            BlockPos cell = feet.offset(d[0], 0, d[1]);
            if (world.traitsAt(cell).lowProfile()
                    && world.traitsAt(cell.up()).lowProfile()
                    && world.traitsAt(cell.down()).solid()
                    && !world.traitsAt(cell.down()).hazard()) {
                return cell;
            }
        }
        return null;
    }

    private void tickCamp(BotContext ctx) {
        long time = ctx.worldTime();
        if (time < DUSK || time > DAWN) {
            // Ráno: jde se dál; ohniště zůstává jako stopa po táboření.
            cooldownTicks = 6000;
            phase = Phase.DONE;
            return;
        }
        // Držet se u ohně; posedávání a pohled do plamenů dodá humanizer.
        if (ctx.position().toBlockPos().distanceSquared(fireSpot) > 16) {
            ctx.navigator().navigateTo(ctx.position(),
                    dev.botalive.core.pathfinding.PathGoal.near(fireSpot, 2));
            return;
        }
        ctx.navigator().stop();
        if (--gazeCooldown <= 0) {
            gazeCooldown = ctx.rng().rangeInt(40, 120);
            ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0),
                    fireSpot.center().add(0, 0.3, 0));
        }
    }

    @Override
    public void stop(Bot bot) {
        ctx(bot).navigator().stop();
        current = null;
    }

    @Override
    public boolean finished(Bot bot) {
        return phase == Phase.DONE;
    }

    @Override
    public String explain(Bot bot) {
        return "táborím u ohně, domů je daleko";
    }
}
