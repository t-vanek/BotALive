package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.pathfinding.PathGoal;
import dev.botalive.core.pathfinding.SegmentPlanner;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.WorldDimension;

/**
 * Únik na povrch – hluboko uvězněný bot se vyšplhá či prokope k dennímu
 * světlu.
 *
 * <p>Provozní vzor „Filip13/Lucka": bot vzkříšený hluboko v jeskyních má
 * povrchový domov, ale jeden A* na 60+ bloků svislého bludiště nestačí
 * (uzlové/časové rozpočty) – backoff nedosažitelných cílů zastavil replán
 * bouři, jenže bot se domů sám nikdy nedostane. Řešení po hráčovsku: krátké
 * svislé etapy ({@link PathGoal#yLevel}, +{@value #LEG} bloků) – pěší etapa
 * jeskyněmi je zadarmo a zazděnou vyláme existující eskalace kopacích hran
 * (respektuje {@code ai.terraforming}). Na povrchu cíl končí a domov je zase
 * v dosahu běžné navigace.</p>
 */
public final class EscapeGoal extends AbstractGoal {

    /** Výška jedné svislé etapy (bloky) – malý, spolehlivě plánovatelný skok. */
    private static final int LEG = 12;
    /** Hloubka pod domovem, od které se únik vůbec zvažuje. */
    private static final int DEEP_BELOW_HOME = 24;
    /** Klid po marném úniku (ms) – prostor pro jiné cíle a změnu situace. */
    private static final long FAIL_COOLDOWN_MS = 120_000L;

    private BlockPos leg;
    private int legStartY;
    private int failedLegs;
    private boolean done;
    private long failUntilMs;

    /** Vytvoří cíl. */
    public EscapeGoal() {
        super("escape");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (ctx.worldView() == null || ctx.dimension() != WorldDimension.OVERWORLD
                || System.currentTimeMillis() < failUntilMs) {
            return 0;
        }
        BlockPos pos = ctx.position().toBlockPos();
        var home = bot.memory().recallNearest(MemoryKind.HOME,
                ctx.worldView().worldName(), pos.x(), pos.y(), pos.z());
        if (home.isEmpty() || home.get().y() - pos.y() < DEEP_BELOW_HOME) {
            return 0;
        }
        // Únik má smysl, až když běžná navigace domů prokazatelně selhala –
        // horník v hloubce s průchozí cestou nahoru žádný nepotřebuje.
        BlockPos anchor = new BlockPos(home.get().x(), home.get().y(), home.get().z());
        if (!ctx.navigator().recentlyUnreachable(anchor)) {
            return 0;
        }
        return 26; // nad pracovními cíli, pod přežitím a jídlem
    }

    @Override
    public void start(Bot bot) {
        leg = null;
        failedLegs = 0;
        done = false;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        BlockPos pos = ctx.position().toBlockPos();
        // U povrchu (sonda sloupce má omezený svislý dosah – hluboko vrací
        // null) únik splnil účel; domov převezme běžné rozhodování.
        BlockPos surface = SegmentPlanner.surfaceAt(ctx.worldView(), pos.x(), pos.y(), pos.z());
        if (surface != null && pos.y() >= surface.y() - 2) {
            done = true;
            ctx.navigator().stop();
            return;
        }
        if (leg != null && pos.y() >= leg.y() - 2) {
            leg = null; // etapa zdolána → další
        }
        if (leg == null) {
            legStartY = pos.y();
            leg = new BlockPos(pos.x(), pos.y() + LEG, pos.z());
            ctx.navigator().navigateTo(ctx.position(), PathGoal.yLevel(leg.y(), pos));
            return;
        }
        if (!ctx.navigator().navigating() && !ctx.navigator().hasPath()) {
            if (pos.y() >= legStartY + 3) {
                failedLegs = 0; // etapa nesla výšku – pokračovat novou
            } else if (++failedLegs >= 3) {
                // Ani kopací hrany nepomohly (bedrock strop, láva všude) –
                // na čas to vzdát, ať bot žije aspoň lokálně.
                done = true;
                failUntilMs = System.currentTimeMillis() + FAIL_COOLDOWN_MS;
                ctx.navigator().stop();
                return;
            }
            leg = null;
        }
    }

    @Override
    public boolean finished(Bot bot) {
        return done;
    }

    @Override
    public String explain(Bot bot) {
        return "dostávám se z hloubky na povrch";
    }
}
