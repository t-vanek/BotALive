package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.personality.Trait;
import dev.botalive.api.role.BotRole;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.settlement.SettlementService;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Cardinal;
import dev.botalive.core.world.WorldView;
import dev.botalive.core.pathfinding.PathGoal;

/**
 * Stráž na staveništi – ochrana stavitele a rozestavěné stavby.
 *
 * <p>Velká společná stavba stojí a padá se stavitelem: než dozdí radnici,
 * je z něj snadný terč pro moby i nepřátelské hráče a rozdělaná zeď je
 * bezbranná. Ochránce sídla ({@link BotRole#GUARDIAN}, {@link BotRole#HUNTER})
 * proto při rozestavěné stavbě ({@link SettlementService#activeProject})
 * drží stráž u staveniště: postaví se před stavbu a hlídá. Nepřátele řeší
 * existující bojová AI ({@code CombatGoal} bere každého hostila v dohledu,
 * {@code PvpGoal} hráče) – boj má vyšší utilitu a stráž přebije; po boji se
 * stráž sama obnoví. „Někdo staví, někdo hlídá, ať jiný hráč nebo mob
 * nezabije stavitele a nezničí stavbu."</p>
 */
public final class BuildGuardGoal extends AbstractGoal {

    /** Za tímto poloměrem (v blocích) je staveniště mimo dosah stráže. */
    private static final int SITE_REACH = 96;

    private BlockPos site;
    private BlockPos post;
    private int pauseTicks;
    private int travelTicks;
    private int cooldownTicks;

    /** Vytvoří cíl. */
    public BuildGuardGoal() {
        super("build-guard");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        if (outsideOverworld(ctx) || ctx.settlements() == null || ctx.worldView() == null
                || ctx.clientState().dead()) {
            return 0;
        }
        // Stráž drží ochranné role; ostatní ať staví a shánějí materiál.
        if (bot.role() != BotRole.GUARDIAN && bot.role() != BotRole.HUNTER) {
            return 0;
        }
        if (ctx.clientState().health() <= 8) {
            return 0; // zraněný ať se doléčí, hlídku neudrží
        }
        if (guardedSite(ctx, bot) == null) {
            return 0; // nic se nestaví, nebo je staveniště daleko/v jiném světě
        }
        double courage = bot.personality().trait(Trait.COURAGE);
        // Záměrně pod bojem (>=25, aby ho hrozba u staveniště přebila),
        // ale nad idle/wander – stráž drží, dokud se nic neděje.
        return 13 + courage * 8;
    }

    @Override
    public void start(Bot bot) {
        BotContext ctx = ctx(bot);
        site = guardedSite(ctx, bot);
        Cardinal facing = ctx.settlements().activeProject(bot.id())
                .map(SettlementService.ProjectInfo::facing).orElse(Cardinal.NORTH);
        post = site == null ? null : guardPost(ctx, site, facing);
        pauseTicks = 0;
        travelTicks = 0;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        if (site == null || post == null) {
            cooldownTicks = 600;
            return;
        }
        if (pauseTicks > 0) {
            pauseTicks--; // rozhlížení na stanovišti řeší idle humanizer
            return;
        }
        double distSq = ctx.position().toBlockPos().distanceSquared(post);
        if (distSq <= 2.5 * 2.5) {
            ctx.navigator().stop();
            // Dívat se ke stavbě – odtud přijde hrozba na stavitele.
            ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0), site.center().add(0, 1.0, 0));
            pauseTicks = ctx.rng().rangeInt(40, 100);
            travelTicks = 0;
            return;
        }
        if (++travelTicks > 600) {
            cooldownTicks = 600;
            site = null; // stanoviště nedostupné – tuhle směnu vzdát
            return;
        }
        ctx.navigator().navigateTo(ctx.position(), PathGoal.near(post, 1));
        if (!ctx.navigator().navigating() && !ctx.navigator().hasPath()) {
            travelTicks += 20; // cesta selhala – po pár pokusech vzdát
        }
    }

    @Override
    public boolean finished(Bot bot) {
        BotContext ctx = ctx(bot);
        // Stavba dokončená (staveniště zmizelo z activeProject) nebo zranění →
        // konec směny; stráž se obnoví, dokud se staví.
        if (ctx.clientState().health() <= 6 || guardedSite(ctx, bot) == null) {
            cooldownTicks = 300;
            return true;
        }
        return false;
    }

    @Override
    public String explain(Bot bot) {
        return "hlídám staveniště, ať nikdo nezabije stavitele";
    }

    // ==================================================================

    /** Origin rozestavěné stavby ve stejném světě a v dosahu, jinak {@code null}. */
    private BlockPos guardedSite(BotContext ctx, Bot bot) {
        if (!ctx.config().settlement().enabled()) {
            return null;
        }
        WorldView world = ctx.worldView();
        BlockPos origin = ctx.settlements().activeProject(bot.id())
                .map(SettlementService.ProjectInfo::origin).orElse(null);
        if (origin == null || world == null) {
            return null;
        }
        if (origin.distanceSquared(ctx.position().toBlockPos()) > SITE_REACH * SITE_REACH) {
            return null; // staveniště přes půl světa – tam stráž nedojde
        }
        return origin;
    }

    /**
     * Stanoviště stráže: schůdné místo pár bloků před stavbou (kudy chodí
     * stavitel i hrozba). Sloupec se dopočítá z geometrie ({@link
     * #guardPostColumn}), výška se dohledá – staveniště bývá na svahu.
     */
    private BlockPos guardPost(BotContext ctx, BlockPos origin, Cardinal facing) {
        WorldView world = ctx.worldView();
        BlockPos column = guardPostColumn(origin, facing);
        if (world == null) {
            return column;
        }
        for (int dy = 0; dy <= 6; dy++) {
            for (int sign : new int[]{1, -1}) {
                BlockPos feet = new BlockPos(column.x(), origin.y() + dy * sign, column.z());
                if (world.traitsAt(feet).passable()
                        && world.traitsAt(feet.up()).passable()
                        && world.traitsAt(feet.down()).solid()) {
                    return feet;
                }
                if (dy == 0) {
                    break; // ±0 stačí jednou
                }
            }
        }
        return column;
    }

    /**
     * Sloupec stanoviště stráže (čistá geometrie – testovatelná): pár bloků
     * před stavbou ve směru přístupu (dveře míří k návsi = {@code facing}),
     * mimo půdorys. Výšku dohledá {@link #guardPost}.
     *
     * @param origin roh půdorysu stavby
     * @param facing orientace stavby (dveře k návsi)
     * @return sloupec (x,y,z) stanoviště; y = úroveň originu
     */
    static BlockPos guardPostColumn(BlockPos origin, Cardinal facing) {
        return new BlockPos(origin.x() + 2 + facing.dx() * 3, origin.y(),
                origin.z() + 2 + facing.dz() * 3);
    }
}
