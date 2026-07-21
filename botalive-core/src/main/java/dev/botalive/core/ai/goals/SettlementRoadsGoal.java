package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.build.SettlementRoads;
import dev.botalive.core.build.VillageDecor;
import dev.botalive.core.settlement.SettlementService;
import dev.botalive.core.settlement.SettlementTier;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.WorldDimension;
import dev.botalive.core.world.WorldView;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Dusání silniční sítě uvnitř sídla (vesnice/město).
 *
 * <p>Zvelebení domů ({@link VillageDecor}) dělá tenkou cestičku od dveří
 * k návsi – tenhle cíl na to navazuje o patro výš: propojí celé sídlo
 * hlavními ulicemi od návsi k domům a městu přidá obvodový okruh
 * ({@link SettlementRoads}). Síť vlastní {@link SettlementService} (jeden
 * stavitel naráz, jako společné projekty), samotné dusání vede sdílený
 * {@link DecorWorker} – tatáž chůze, lopata a pauzy jako u cestiček.</p>
 *
 * <p>Idempotence dělá cíl bezúdržbovým: dusá se jen po trávě, takže hotová
 * cesta se přeskočí a nové parcely (růst sídla) přibydou k síti v další
 * seanci. Stavba běží ve dne a poblíž domova; strop kroků drží jednu
 * seanci krátkou, zbytek se dodělá později.</p>
 */
public final class SettlementRoadsGoal extends AbstractGoal {

    private enum Phase { CLAIM, WORK, DONE }

    /** Strop kroků na jednu seanci (velká síť se dodusá napříč seancemi). */
    private static final int MAX_STEPS = 40;
    /** Minimální odstup mezi seancemi dusání téže sítě (ms). */
    private static final long RECHECK_INTERVAL_MS = 5 * 60_000L;
    /** Okruh kolem návsi, ve kterém bot síť řeší (nechodí kvůli ní přes půl světa). */
    private static final int NEAR_HOME_RINGS = 6;

    private Phase phase = Phase.DONE;
    private long settlementId;
    private DecorWorker decor;
    private int cooldownTicks;
    private UUID selfId;

    /** Vytvoří cíl. */
    public SettlementRoadsGoal() {
        super("settlement-roads");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        var cfg = ctx.config().settlement();
        if (!cfg.enabled() || !cfg.paths()) {
            return 0;
        }
        WorldView world = ctx.worldView();
        if (world == null || ctx.dimension() != WorldDimension.OVERWORLD
                || ctx.settlements() == null) {
            return 0;
        }
        var info = ctx.settlements().settlementOf(bot.id());
        if (info.isEmpty()) {
            return 0;
        }
        SettlementService.SettlementInfo settlement = info.get();
        // Cesty jsou znak vesnice+; osada je moc malá, aby stálo za to ji rozčleňovat.
        if (settlement.tier().ordinal() < SettlementTier.VESNICE.ordinal()
                || !settlement.world().equals(world.worldName())) {
            return 0;
        }
        boolean inProgress = decor != null || phase == Phase.CLAIM;
        if (!inProgress) {
            // Staví se za světla, jako domy a společné stavby.
            long time = ctx.worldTime();
            if (time >= 11500 && time <= 23000) {
                return 0;
            }
            // Jen poblíž domova a jen s lopatou (bez ní není čím dusat).
            int reach = cfg.plotSpacing() * NEAR_HOME_RINGS;
            if (ctx.position().toBlockPos().distanceSquared(settlement.center())
                    > (double) reach * reach) {
                return 0;
            }
            var snapshot = ctx.serverView().latest();
            if (snapshot == null || !snapshot.hasItem(m -> m.name().endsWith("_SHOVEL"))) {
                return 0;
            }
            if (!ctx.settlements().roadsDue(settlement.id(), RECHECK_INTERVAL_MS, bot.id())) {
                return 0;
            }
        }
        // Nižší priorita než dům/studna – dělá se, když je klid a chuť pomáhat.
        double helpfulness = bot.personality().trait(Trait.HELPFULNESS);
        return 5 + helpfulness * 5;
    }

    @Override
    public void start(Bot bot) {
        phase = Phase.CLAIM;
        decor = null;
        selfId = bot.id();
        settlementId = 0;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        switch (phase) {
            case CLAIM -> tickClaim(ctx, bot);
            case WORK -> tickWork(ctx);
            case DONE -> {
            }
        }
    }

    private void tickClaim(BotContext ctx, Bot bot) {
        var info = ctx.settlements().settlementOf(bot.id());
        if (info.isEmpty()
                || info.get().tier().ordinal() < SettlementTier.VESNICE.ordinal()) {
            phase = Phase.DONE;
            return;
        }
        SettlementService.SettlementInfo settlement = info.get();
        settlementId = settlement.id();
        if (!ctx.settlements().claimRoads(settlementId, bot.id())) {
            cooldownTicks = 1200; // dusá někdo jiný – ať to dodělá on
            phase = Phase.DONE;
            return;
        }
        List<BlockPos> plotOrigins = new ArrayList<>();
        for (SettlementService.MemberInfo member : settlement.members()) {
            if (member.plotOrigin() != null) {
                plotOrigins.add(member.plotOrigin());
            }
        }
        boolean ringRoad = settlement.tier() == SettlementTier.MESTO;
        List<VillageDecor.Step> steps = SettlementRoads.plan(ctx.worldView(),
                settlement.center(), ctx.config().settlement().plotSpacing(),
                plotOrigins, ringRoad, MAX_STEPS);
        if (steps.isEmpty()) {
            finish(ctx); // síť už drží (nebo není kde dusat) – odstup a konec
            return;
        }
        decor = new DecorWorker(steps);
        phase = Phase.WORK;
    }

    private void tickWork(BotContext ctx) {
        if (decor == null || decor.tick(ctx)) {
            ctx.gainExperience(dev.botalive.core.personality.PersonalityEvolution
                    .BotExperience.HOUSE_BUILT);
            finish(ctx);
        }
    }

    /** Seance doběhla: nastaví sídlu odstup, uvolní síť a ukončí cíl. */
    private void finish(BotContext ctx) {
        ctx.settlements().roadsBuilt(settlementId);
        decor = null;
        cooldownTicks = 6000;
        phase = Phase.DONE;
    }

    @Override
    public void stop(Bot bot) {
        BotContext ctx = ctx(bot);
        if (decor != null) {
            decor.cancel(ctx);
            decor = null;
        }
        if (settlementId != 0) {
            ctx.settlements().releaseRoads(settlementId, selfId);
        }
        ctx.navigator().stop();
    }

    @Override
    public boolean finished(Bot bot) {
        return phase == Phase.DONE;
    }

    @Override
    public String explain(Bot bot) {
        return "dusám cesty v sídle";
    }
}
