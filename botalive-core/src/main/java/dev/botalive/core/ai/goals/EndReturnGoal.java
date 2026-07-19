package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.ai.EndKnowledge;
import dev.botalive.core.chat.PhraseCategory;
import dev.botalive.core.inventory.InventoryHelper;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.WorldDimension;
import dev.botalive.core.world.WorldView;
import org.bukkit.Material;
import dev.botalive.core.pathfinding.PathGoal;

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

    private Phase phase = Phase.GO_CENTER;
    private PortalEntry entry;
    private BlockPos centerTarget;
    private int cooldownTicks;
    private int travelTicks;
    /** Sken výstupního portálu přes studenou chunk cache chce pár pokusů. */
    private final ScanRetry portalScan = new ScanRetry(4, 30);

    /** Vytvoří cíl. */
    public EndReturnGoal() {
        super("end-return");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (ctx.clientState().dead() || ctx.dimension() != WorldDimension.END) {
            return 0;
        }
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        // Bazální chuť domů: End je výprava, ne domov – i bot bez nouze
        // (třeba hozený do Endu hráčem, bez vlastního průchodu) má důvod
        // dojít k fontáně a zkusit výstup, místo věčného bloumání.
        double urge = 6;
        int food = ctx.clientState().food();
        if (food <= 8) {
            urge += 25;
        }
        if (ctx.clientState().health() < 10) {
            urge += 20;
        }
        if (food <= 8 && ctx.clientState().health() < 10) {
            // Na dně: hlad a zranění zároveň musí přebít i dračí souboj
            // s hysterezí (45+10 odvahy ×1,15 ≈ 63) – jinak bot bojuje
            // do smrti a nikdy se nepokusí odejít.
            urge += 18;
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

    /** Délka aktuální výpravy (minuty) – od posledního vlastního průchodu do Endu. */
    private static long expeditionMinutes(Bot bot) {
        long entered = bot.memory().recall(MemoryKind.PORTAL).stream()
                .filter(r -> !"gossip".equals(r.data().get("via")))
                .filter(r -> {
                    String to = r.data().get("to");
                    return to != null && WorldDimension.fromWorldKey(to) == WorldDimension.END;
                })
                .mapToLong(dev.botalive.api.memory.MemoryRecord::updatedAt)
                .max().orElse(0);
        return entered == 0 ? 0 : (System.currentTimeMillis() - entered) / 60_000;
    }

    @Override
    public void start(Bot bot) {
        BotContext ctx = ctx(bot);
        phase = Phase.GO_CENTER;
        entry = null;
        centerTarget = null;
        travelTicks = 0;
        portalScan.reset();
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
                    ctx.navigator().navigateTo(pos, PathGoal.near(centerTarget, 8));
                    return;
                }
                // U fontány: existuje výstupní portál? (jen po smrti draka)
                // Sken se středí na fontánu, ne na bota – při náběhu po ose
                // by portál ležel mimo dosah skenu. Studená chunk cache chce
                // pár pokusů s prefetch, jinak by „nenašel" znamenalo omyl.
                if (portalScan.waiting()) {
                    return;
                }
                BlockPos fountain = new BlockPos(0, pos.toBlockPos().y(), 0);
                BlockPos portalBlock = PortalEntry.findExitPortal(world, fountain);
                if (portalBlock == null) {
                    if (!portalScan.shouldRetry()) {
                        giveUp(2400); // drak žije – domů se zatím nedá
                    } else if (portalScan.firstFailure()) {
                        world.prefetch(fountain, 1);
                    }
                    return;
                }
                entry = new PortalEntry(world, portalBlock);
                ctx.navigator().stop();
                phase = Phase.ENTER;
            }
            case ENTER -> {
                // Průchod pozná finished() podle změny dimenze.
                if (entry.tick(ctx, PortalEntry.DEFAULT_BUDGET_TICKS)
                        == PortalEntry.Result.GAVE_UP) {
                    giveUp(600);
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
        return phase == Phase.DONE || ctx(bot).dimension() != WorldDimension.END;
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
