package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.personality.Trait;
import dev.botalive.api.role.BotRole;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.chat.PhraseCategory;
import dev.botalive.core.settlement.SettlementService;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.WorldView;

/**
 * Noční hlídka – lovec nespí, obchází vesnici po perimetru.
 *
 * <p>Osvětlení pomohlo návsi, ale okraj vesnice je tma a noc patří mobům.
 * Bot s rolí {@link BotRole#HUNTER} si místo spánku bere noční směnu:
 * krouží po prstenci waypointů za parcelami (poloměr z rozestupu parcel),
 * na každém stanovišti se chvíli rozhlédne a jde dál. Nepřátele cestou
 * řeší existující bojová AI – boj má vyšší utilitu a hlídku přebije,
 * po boji se hlídka sama obnoví. Ráno směna končí; vesnice má míň mrtvol
 * a role lovce dostává smysl i po setmění.</p>
 */
public final class GuardGoal extends AbstractGoal {

    /** Počet stanovišť na obchůzce. */
    private static final int WAYPOINTS = 8;

    private BlockPos center;
    private int radius;
    private int waypoint;
    private int direction = 1;
    private int pauseTicks;
    private int travelTicks;
    private int cooldownTicks;

    /** Vytvoří cíl. */
    public GuardGoal() {
        super("guard");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        // Hlídá se vesnice v overworldu.
        if (outsideOverworld(ctx)) {
            return 0;
        }
        if (bot.role() != BotRole.HUNTER || ctx.clientState().dead()) {
            return 0;
        }
        if (!isNight(ctx.worldTime()) || ctx.clientState().health() <= 8) {
            return 0; // zraněný hlídku nedrží – ať se doléčí nebo vyspí
        }
        if (guardedVillageCenter(ctx, bot) == null) {
            return 0;
        }
        double courage = bot.personality().trait(Trait.COURAGE);
        return 10 + courage * 10;
    }

    @Override
    public void start(Bot bot) {
        BotContext ctx = ctx(bot);
        center = guardedVillageCenter(ctx, bot);
        radius = ctx.config().settlement().plotSpacing() * 2 + 4;
        waypoint = ctx.rng().rangeInt(0, WAYPOINTS - 1);
        direction = ctx.rng().chance(0.5) ? 1 : -1;
        pauseTicks = 0;
        travelTicks = 0;
        if (center != null && ctx.rng().chance(0.35)) {
            ctx.chat().sayFrom(PhraseCategory.GUARD_NIGHT, null);
        }
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        if (center == null) {
            cooldownTicks = 1200;
            return;
        }
        if (pauseTicks > 0) {
            pauseTicks--; // rozhlížení na stanovišti řeší idle humanizer
            return;
        }
        BlockPos target = waypointPos(ctx, waypoint);
        if (target == null) {
            nextWaypoint(ctx);
            return;
        }
        double distSq = ctx.position().toBlockPos().distanceSquared(target);
        if (distSq <= 2.5 * 2.5) {
            ctx.navigator().stop();
            pauseTicks = ctx.rng().rangeInt(60, 140);
            travelTicks = 0;
            nextWaypoint(ctx);
            return;
        }
        if (++travelTicks > 600) {
            travelTicks = 0;
            nextWaypoint(ctx); // stanoviště nedostupné – jde se na další
            return;
        }
        ctx.navigator().navigateTo(ctx.position(), target);
        if (!ctx.navigator().navigating() && !ctx.navigator().hasPath()) {
            travelTicks += 20; // cesta selhala – po pár pokusech přeskočit
        }
    }

    @Override
    public boolean finished(Bot bot) {
        BotContext ctx = ctx(bot);
        long time = ctx.worldTime();
        boolean morning = time >= 0 && !isNight(time);
        boolean hurt = ctx.clientState().health() <= 6;
        if (morning || hurt || center == null) {
            cooldownTicks = 600;
            return true;
        }
        return false;
    }

    @Override
    public String explain(Bot bot) {
        return "držím noční hlídku kolem vesnice";
    }

    // ==================================================================

    /** Noc/soumrak (neznámý čas není noc). */
    private static boolean isNight(long time) {
        return time >= 12500 && time <= 23000;
    }

    /** Náves vlastní vesnice ve stejném světě, jinak {@code null}. */
    private BlockPos guardedVillageCenter(BotContext ctx, Bot bot) {
        if (!ctx.config().settlement().enabled() || ctx.settlements() == null
                || ctx.worldView() == null) {
            return null;
        }
        return ctx.settlements().settlementOf(bot.id())
                .filter(s -> s.world().equals(ctx.worldView().worldName()))
                .map(SettlementService.SettlementInfo::center)
                .orElse(null);
    }

    private void nextWaypoint(BotContext ctx) {
        if (ctx.rng().chance(0.12)) {
            direction = -direction; // občas otočit směr, ať obchůzka není hodinky
        }
        waypoint = Math.floorMod(waypoint + direction, WAYPOINTS);
    }

    /** Pochozí bod stanoviště na prstenci (hledá se schůdná výška). */
    private BlockPos waypointPos(BotContext ctx, int index) {
        WorldView world = ctx.worldView();
        if (world == null) {
            return null;
        }
        double angle = index * (Math.PI * 2 / WAYPOINTS);
        int x = center.x() + (int) Math.round(Math.cos(angle) * radius);
        int z = center.z() + (int) Math.round(Math.sin(angle) * radius);
        for (int dy = 0; dy <= 6; dy++) {
            for (int sign : new int[]{1, -1}) {
                BlockPos feet = new BlockPos(x, center.y() + dy * sign, z);
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
        return null;
    }
}
