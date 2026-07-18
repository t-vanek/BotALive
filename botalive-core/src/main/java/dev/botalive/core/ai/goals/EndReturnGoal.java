package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.ai.EndKnowledge;
import dev.botalive.core.chat.PhraseCategory;
import dev.botalive.core.inventory.InventoryHelper;
import dev.botalive.core.physics.MoveInput;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.Dimension;
import dev.botalive.core.world.WorldView;
import org.bukkit.Material;

/**
 * Návrat z Endu – výstupním portálem domů.
 *
 * <p>Chuť domů roste s hladem, zraněním, plnou kapsou perel a délkou výpravy;
 * po skolení draka je návrat přirozená tečka. Výstupní portál na fontáně
 * uprostřed ostrova ale existuje jen po drakově smrti – když tam bot dojde
 * a portál nenajde, ví, že je v Endu zavřený, a vrací se k boji či lovu.
 * Průchod portálem vysadí bota u jeho spawnu; doma pak přebírají práci
 * běžné cíle ({@code home}, {@code stash}, {@code sell}…).</p>
 */
public final class EndReturnGoal extends AbstractGoal {

    private enum Phase { GO_CENTER, ENTER, DONE }

    /** Jak dlouho smí trvat vkročení do portálu (ticky). */
    private static final int ENTER_BUDGET_TICKS = 300;

    private Phase phase = Phase.GO_CENTER;
    private BlockPos portalBlock;
    private BlockPos standPoint;
    private BlockPos centerTarget;
    private int cooldownTicks;
    private int enterTicks;
    private int travelTicks;

    /** Vytvoří cíl. */
    public EndReturnGoal() {
        super("end-return");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (ctx.clientState().dead() || ctx.dimension() != Dimension.THE_END) {
            return 0;
        }
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        double urge = 0;
        int food = ctx.clientState().food();
        if (food <= 8) {
            urge += 25;
        }
        if (ctx.clientState().health() < 10) {
            urge += 20;
        }
        var snapshot = ctx.serverView().latest();
        if (snapshot != null && InventoryHelper.countEstimate(snapshot,
                m -> m == Material.ENDER_PEARL) >= 16) {
            urge += 15; // perly v kapse – čas je zpeněžit
        }
        if (EndKnowledge.dragonSlain(bot.memory())) {
            urge += 12; // práce hotová
        }
        if (expeditionMinutes(bot) > 25) {
            urge += 10; // domů se chce i dobrodruhům
        }
        return urge;
    }

    /** Délka aktuální výpravy (minuty) – od posledního průchodu do Endu. */
    private static long expeditionMinutes(Bot bot) {
        long entered = bot.memory().recall(MemoryKind.PORTAL).stream()
                .filter(r -> {
                    String to = r.data().get("to");
                    return to != null && Dimension.fromWorldKey(to) == Dimension.THE_END;
                })
                .mapToLong(dev.botalive.api.memory.MemoryRecord::updatedAt)
                .max().orElse(0);
        return entered == 0 ? 0 : (System.currentTimeMillis() - entered) / 60_000;
    }

    @Override
    public void start(Bot bot) {
        BotContext ctx = ctx(bot);
        phase = Phase.GO_CENTER;
        portalBlock = null;
        standPoint = null;
        centerTarget = null;
        enterTicks = 0;
        travelTicks = 0;
        if (ctx.rng().chance(0.6)) {
            ctx.chat().sayFrom(PhraseCategory.END_RETURN, null);
        }
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        WorldView world = ctx.worldView();
        if (world == null || phase == Phase.DONE) {
            return;
        }
        Vec3 pos = ctx.position();

        switch (phase) {
            case GO_CENTER -> {
                if (pos.horizontal().distance(Vec3.ZERO) > 12) {
                    if (++travelTicks > 2400) {
                        giveUp(1200); // cesta ke středu se vleče
                        return;
                    }
                    // Stabilní cíl – přepočet y každý tick by resetoval mosty.
                    if (centerTarget == null) {
                        centerTarget = new BlockPos(0, (int) pos.y(), 0);
                    }
                    ctx.navigator().navigateTo(pos, centerTarget);
                    return;
                }
                // U fontány: existuje výstupní portál? (jen po smrti draka)
                portalBlock = scanExitPortal(world, pos.toBlockPos());
                if (portalBlock == null) {
                    giveUp(2400); // drak žije – domů se zatím nedá
                    return;
                }
                standPoint = EndTravelGoal.findStandPoint(world, portalBlock);
                ctx.navigator().stop();
                phase = Phase.ENTER;
            }
            case ENTER -> {
                if (++enterTicks > ENTER_BUDGET_TICKS) {
                    giveUp(600);
                    return;
                }
                Vec3 target = portalBlock.center();
                if (standPoint != null
                        && standPoint.center().sub(pos).horizontalLength() > 1.1
                        && target.sub(pos).horizontalLength() > 1.6) {
                    ctx.navigator().navigateTo(pos, standPoint);
                    if (!ctx.navigator().navigating() && !ctx.navigator().hasPath()) {
                        standPoint = null;
                    }
                    return;
                }
                ctx.navigator().stop();
                Vec3 step = target.sub(pos).horizontal();
                ctx.humanizer().lookAt(pos.add(0, 1.62, 0), target);
                if (step.horizontalLength() > 1.0E-3) {
                    ctx.requestMove(MoveInput.walk(step));
                }
            }
            case DONE -> {
                // nic
            }
        }
    }

    private void giveUp(int cooldown) {
        cooldownTicks = cooldown;
        phase = Phase.DONE;
    }

    @Override
    public boolean finished(Bot bot) {
        return phase == Phase.DONE || ctx(bot).dimension() != Dimension.THE_END;
    }

    /** Najde blok výstupního portálu v okolí fontány (aktivní až po drakovi). */
    private static BlockPos scanExitPortal(WorldView world, BlockPos around) {
        for (int dx = -8; dx <= 8; dx++) {
            for (int dy = -6; dy <= 6; dy++) {
                for (int dz = -8; dz <= 8; dz++) {
                    BlockPos p = around.offset(dx, dy, dz);
                    if (world.materialAt(p) == Material.END_PORTAL) {
                        return p;
                    }
                }
            }
        }
        return null;
    }

    @Override
    public String explain(Bot bot) {
        return switch (phase) {
            case GO_CENTER -> "mám toho tady dost, hledám cestu domů";
            case ENTER -> "výstupní portál! jedu domů";
            case DONE -> null;
        };
    }
}
