package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.bot.ServerSideView;
import dev.botalive.core.chat.PhraseCategory;
import dev.botalive.core.economy.EmploymentService;
import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.inventory.InventoryHelper;
import dev.botalive.core.pathfinding.PathGoal;
import dev.botalive.core.util.Vec3;
import org.bukkit.Material;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Donáška výtěžku – najatý dělník pravidelně nosí zaměstnavateli, co
 * vydělal.
 *
 * <p>Jednou za čas (a jen když má z čeho) vybere dělník z inventáře koš
 * komodit podle toho, co jeho práce nese – horník uhlí a železo, farmář
 * chleba a pšenici, lovec maso a kůži – dojde k zaměstnavateli a v lidském
 * tempu mu zboží upustí (stejná mechanika předávky jako trh a sdílení
 * jídla). Bot si vždy nechává vlastní rezervu, doručuje jen přebytek.</p>
 */
public final class WorkDeliveryGoal extends AbstractGoal {

    /** Rozestup donášek (ms) – zhruba dvakrát za hodinu hraní. */
    private static final long DELIVERY_INTERVAL_MS = 20 * 60_000;

    /** Jak daleko dělník za zaměstnavatelem dojde (bloky). */
    private static final double SIGHT_RADIUS = 64;

    /**
     * Koš doručovaných komodit: materiál → (rezerva bota, kolik nese).
     * Doručuje se první položka, které má bot víc než rezervu.
     */
    private static final Map<Material, int[]> BASKET = new LinkedHashMap<>();

    static {
        BASKET.put(Material.IRON_INGOT, new int[]{8, 4});
        BASKET.put(Material.COAL, new int[]{16, 8});
        BASKET.put(Material.GOLD_INGOT, new int[]{6, 3});
        BASKET.put(Material.COPPER_INGOT, new int[]{12, 6});
        BASKET.put(Material.BREAD, new int[]{8, 4});
        BASKET.put(Material.COOKED_BEEF, new int[]{8, 4});
        BASKET.put(Material.COOKED_PORKCHOP, new int[]{8, 4});
        BASKET.put(Material.COOKED_CHICKEN, new int[]{8, 4});
        BASKET.put(Material.COOKED_MUTTON, new int[]{8, 4});
        BASKET.put(Material.COOKED_COD, new int[]{8, 4});
        BASKET.put(Material.COOKED_SALMON, new int[]{8, 4});
        BASKET.put(Material.WHEAT, new int[]{16, 8});
        BASKET.put(Material.LEATHER, new int[]{6, 3});
    }

    private enum Phase { APPROACH, GIVE, DONE }

    private final EmploymentService employment;

    private Phase phase = Phase.APPROACH;
    private Material delivering;
    private int toGive;
    private int given;
    private int giveTicks;
    private int approachTicks;
    private int cooldownTicks;

    /**
     * @param employment služba najímání (smlouvy, evidence donášek)
     */
    public WorkDeliveryGoal(EmploymentService employment) {
        super("deliver-work");
        this.employment = employment;
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        Optional<EmploymentService.Contract> contract = employment.contractOf(bot.id());
        if (contract.isEmpty()
                || contract.get().kind() != EmploymentService.Kind.WORKER) {
            return 0;
        }
        long since = Math.max(contract.get().lastDeliveryAt(), contract.get().hiredAt());
        if (System.currentTimeMillis() - since < DELIVERY_INTERVAL_MS) {
            return 0;
        }
        if (employerEntity(ctx, contract.get()).isEmpty()
                || pickDelivery(ctx.serverView().latest()) == null) {
            return 0;
        }
        return 16 + bot.personality().trait(
                dev.botalive.api.personality.Trait.HELPFULNESS) * 4;
    }

    @Override
    public void start(Bot bot) {
        phase = Phase.APPROACH;
        delivering = null;
        toGive = 0;
        given = 0;
        giveTicks = 0;
        approachTicks = 0;
        BotContext ctx = ctx(bot);
        Material pick = pickDelivery(ctx.serverView().latest());
        if (pick == null) {
            finish(ctx);
            return;
        }
        delivering = pick;
        toGive = BASKET.get(pick)[1];
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        Optional<EmploymentService.Contract> contract = employment.contractOf(bot.id());
        if (contract.isEmpty() || phase == Phase.DONE) {
            finish(ctx);
            return;
        }
        Optional<TrackedEntity> employer = employerEntity(ctx, contract.get());
        if (employer.isEmpty()) {
            finish(ctx); // zaměstnavatel zmizel, zkusí se to příště
            return;
        }
        Vec3 targetPos = employer.get().position();
        switch (phase) {
            case APPROACH -> {
                if (ctx.position().distanceSquared(targetPos) <= 3 * 3) {
                    ctx.navigator().stop();
                    phase = Phase.GIVE;
                    return;
                }
                if (++approachTicks > 400) {
                    finish(ctx);
                    return;
                }
                ctx.navigator().navigateTo(ctx.position(),
                        PathGoal.near(targetPos.toBlockPos(), 2));
            }
            case GIVE -> {
                ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0),
                        targetPos.add(0, 1.5, 0));
                if (--giveTicks > 0) {
                    return;
                }
                ServerSideView.Snapshot snapshot = ctx.serverView().latest();
                if (given >= toGive || snapshot == null
                        || !ctx.inventory().equipItem(snapshot, delivering)) {
                    onDelivered(ctx, bot, contract.get());
                    return;
                }
                ctx.actions().dropItem();
                given++;
                giveTicks = ctx.rng().rangeInt(8, 16);
            }
            case DONE -> finish(ctx);
        }
    }

    @Override
    public void stop(Bot bot) {
        super.stop(bot);
    }

    @Override
    public boolean finished(Bot bot) {
        return phase == Phase.DONE;
    }

    @Override
    public String explain(Bot bot) {
        return employment.contractOf(bot.id())
                .map(c -> "nesu výdělek zaměstnavateli – " + c.employerName())
                .orElse(null);
    }

    // ==================================================================

    /** Po donášce: evidence, hláška, prohloubení vztahu. */
    private void onDelivered(BotContext ctx, Bot bot,
                             EmploymentService.Contract contract) {
        if (given > 0) {
            employment.markDelivered(bot.id());
            if (ctx.worldView() != null) {
                Vec3 pos = ctx.position();
                bot.memory().remember(MemoryKind.FRIEND, ctx.worldView().worldName(),
                        (int) pos.x(), (int) pos.y(), (int) pos.z(),
                        contract.employer(), Map.of("via", "employment"), 0.5);
            }
            if (ctx.rng().chance(0.7)) {
                ctx.chat().sayFrom(PhraseCategory.HIRE_DELIVER,
                        contract.employerName());
            }
        }
        finish(ctx);
    }

    private void finish(BotContext ctx) {
        phase = Phase.DONE;
        cooldownTicks = 1200;
        ctx.navigator().stop();
    }

    /** Zaměstnavatel v dochozí vzdálenosti. */
    private Optional<TrackedEntity> employerEntity(BotContext ctx,
                                                   EmploymentService.Contract contract) {
        return ctx.entities().byUuid(contract.employer())
                .filter(e -> e.position().distance(ctx.position()) <= SIGHT_RADIUS);
    }

    /** První komodita koše, které má bot víc než vlastní rezervu. */
    private static Material pickDelivery(ServerSideView.Snapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        for (Map.Entry<Material, int[]> entry : BASKET.entrySet()) {
            int have = InventoryHelper.countItem(snapshot, entry.getKey());
            if (have > entry.getValue()[0]) {
                return entry.getKey();
            }
        }
        return null;
    }
}
