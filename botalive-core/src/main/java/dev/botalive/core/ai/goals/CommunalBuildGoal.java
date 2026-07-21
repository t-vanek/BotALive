package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.ai.BotNeeds;
import dev.botalive.core.build.plan.Blueprint;
import dev.botalive.core.build.plan.Blueprints;
import dev.botalive.core.build.plan.BuildPlan;
import dev.botalive.core.build.plan.BuildPlanner;
import dev.botalive.core.build.plan.BuildSession;
import dev.botalive.core.build.plan.Workshops;
import dev.botalive.core.chat.PhraseCategory;
import dev.botalive.core.settlement.SettlementService;
import dev.botalive.core.settlement.SettlementService.ProjectKind;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.world.WorldDimension;
import dev.botalive.core.world.WorldView;

import org.bukkit.Material;

/**
 * Společná stavba pro sídlo (studna, sýpka; růstová roadmapa fáze B).
 *
 * <p>Projekt vlastní {@link SettlementService} (nástěnka po vzoru trhu: první
 * stavitel bere, přerušení uvolňuje dalšímu, restart taky – fyzická stavba je
 * autorita). Vlastní stavbu vede sdílený {@link BuildSession}: druh projektu
 * jen vybere {@link Blueprint} ({@code WELL→studna}, {@code GRANARY→sýpka}) –
 * žádné per-druh větvení geometrie. Staveniště je dané projektem, takže odpadá
 * hledání parcely; zbývá dojít, nechat engine srovnat terén, postavit a osadit,
 * a nahlásit dokončení sídlu (povýšení ohlásí stavitel v chatu).</p>
 */
public final class CommunalBuildGoal extends AbstractGoal {

    private enum Phase { CLAIM, GOTO, SESSION, FINISH, DONE }

    /** Rezerva bloků nad čistou spotřebu stavby (zásypy podlahy). */
    private static final int BLOCK_RESERVE = 8;

    private Phase phase = Phase.DONE;
    private SettlementService.ProjectInfo project;
    private BuildPlan plan;
    private BuildSession session;
    private int cooldownTicks;
    private boolean claimed;
    private java.util.UUID selfId;

    /** Klíč posledního ohlášeného projektu – hláška jen pro nový, ne re-claim. */
    private long lastAnnouncedProject = -1;

    /** Vytvoří cíl. */
    public CommunalBuildGoal() {
        super("communal-build");
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
        if (ctx.settlements() == null) {
            return 0;
        }
        var needed = ctx.settlements().neededProject(bot.id());
        if (needed.isEmpty()) {
            return 0;
        }
        // Vstupní brány platí jen pro ZAHÁJENÍ – rozdělaná stavba smí doběhnout
        // i po setmění a s ubývajícím materiálem (jinak zůstane torzo).
        boolean inProgress = session != null || claimed;
        if (!inProgress) {
            // Staví se za světla, jako domy.
            long time = ctx.worldTime();
            if (time >= 11500 && time <= 23000) {
                return 0;
            }
            BotNeeds needs = BotNeeds.assess(ctx.serverView().latest());
            if (needs.buildingBlocks()
                    < blueprintFor(needed.get().kind()).blocksNeeded() + BLOCK_RESERVE) {
                return 0;
            }
            if (!hasRequiredItems(ctx, needed.get().kind())) {
                // Sýpka/tržiště bez truhel je kůlna; dílna bez stanice prázdná
                // kůlna – zahájí to jen člen, který nutné vybavení má.
                return 0;
            }
        }
        double helpfulness = bot.personality().trait(Trait.HELPFULNESS);
        // Závazek: kdo si stavbu zamluvil, drží se jí – bez bonusu si stavitelé
        // rozdělanou studnu přebírali po pár vteřinách (utility kolotoč).
        return 9 + helpfulness * 9 + (claimed ? 10 : 0);
    }

    @Override
    public void start(Bot bot) {
        phase = Phase.CLAIM;
        project = null;
        plan = null;
        session = null;
        claimed = false;
        selfId = bot.id();
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        switch (phase) {
            case CLAIM -> tickClaim(ctx, bot);
            case GOTO -> tickGoto(ctx);
            case SESSION -> tickSession(ctx, bot);
            case FINISH -> finishProject(ctx, bot);
            case DONE -> {
            }
        }
    }

    private Blueprint blueprintFor(ProjectKind kind) {
        if (kind.isWorkshop()) {
            return Workshops.blueprint(kind.workshopRole().orElseThrow());
        }
        return switch (kind) {
            case WELL -> Blueprints.well();
            case GRANARY -> Blueprints.granary();
            case MARKET_STALL -> Blueprints.marketStall();
            default -> throw new IllegalStateException("není blueprint pro " + kind);
        };
    }

    /**
     * Vybavení, které musí mít stavitel v inventáři, aby stavbu <b>zahájil</b>
     * (jinak by vznikla prázdná kůlna): sýpka dvojtruhla, tržiště jedna,
     * dílna svou hlavní stanici. Vedlejší stanice je bonus (osadí se, když ji
     * stavitel má) – proto tu není.
     */
    private static java.util.List<Material> requiredItems(ProjectKind kind) {
        if (kind.isWorkshop()) {
            return java.util.List.of(
                    Workshops.spec(kind.workshopRole().orElseThrow()).station());
        }
        return switch (kind) {
            case GRANARY -> java.util.List.of(Material.CHEST, Material.CHEST);
            case MARKET_STALL -> java.util.List.of(Material.CHEST);
            default -> java.util.List.of();
        };
    }

    /** Má stavitel v batohu vše nutné k zahájení dané stavby? */
    private static boolean hasRequiredItems(BotContext ctx, ProjectKind kind) {
        var snapshot = ctx.serverView().latest();
        for (Material material : requiredItems(kind).stream().distinct().toList()) {
            long need = requiredItems(kind).stream().filter(m -> m == material).count();
            if (ctx.inventory().countItem(snapshot, material) < need) {
                return false;
            }
        }
        return true;
    }

    private static PhraseCategory startPhrase(ProjectKind kind) {
        if (kind.isWorkshop()) {
            return PhraseCategory.SETTLEMENT_WORKSHOP_START;
        }
        return switch (kind) {
            case WELL -> PhraseCategory.SETTLEMENT_WELL_START;
            case GRANARY -> PhraseCategory.SETTLEMENT_GRANARY_START;
            case MARKET_STALL -> PhraseCategory.SETTLEMENT_MARKET_START;
            default -> throw new IllegalStateException("není hláška pro " + kind);
        };
    }

    private static PhraseCategory donePhrase(ProjectKind kind) {
        if (kind.isWorkshop()) {
            return PhraseCategory.SETTLEMENT_WORKSHOP_DONE;
        }
        return switch (kind) {
            case WELL -> PhraseCategory.SETTLEMENT_WELL_DONE;
            case GRANARY -> PhraseCategory.SETTLEMENT_GRANARY_DONE;
            case MARKET_STALL -> PhraseCategory.SETTLEMENT_MARKET_DONE;
            default -> throw new IllegalStateException("není hláška pro " + kind);
        };
    }

    /**
     * Argument pro hlášku o projektu: u dílny její český název (aby chat řekl
     * „stavíme kovárnu"), u infrastruktury jméno sídla.
     */
    private static String phraseArg(ProjectKind kind, String settlementName) {
        if (kind.isWorkshop()) {
            return Workshops.spec(kind.workshopRole().orElseThrow()).csName();
        }
        return settlementName;
    }

    private void tickClaim(BotContext ctx, Bot bot) {
        var needed = ctx.settlements().neededProject(bot.id());
        if (needed.isEmpty()) {
            phase = Phase.DONE;
            return;
        }
        project = needed.get();
        if (!ctx.settlements().claimProject(project.settlementId(), project.kind(), bot.id())) {
            cooldownTicks = 600; // předběhl mě soused – ať staví on
            phase = Phase.DONE;
            return;
        }
        claimed = true;
        plan = BuildPlan.of(blueprintFor(project.kind()), project.origin(), project.facing());
        String name = settlementName(ctx, bot);
        long announceKey = project.settlementId() * 31 + project.kind().ordinal();
        if (name != null && announceKey != lastAnnouncedProject && ctx.rng().chance(0.6)) {
            // Hlásí se jen NOVÝ projekt – opakované zamluvení téhož (návrat
            // ke stavbě, marné pokusy) by chat zaplavilo.
            lastAnnouncedProject = announceKey;
            ctx.chat().sayFrom(startPhrase(project.kind()), phraseArg(project.kind(), name));
        }
        phase = Phase.GOTO;
    }

    private void tickGoto(BotContext ctx) {
        // Ke staveništi se chodí „do okruhu" – pevné stanoviště umělo být
        // zrovna nedostupné (křoví, soused) a stavba se zbytečně vzdávala;
        // přesné stoupnutí na stanoviště pak řeší BuildSession.
        BlockPos stand = plan.stand();
        if (ctx.position().toBlockPos().distanceSquared(stand) <= 9) {
            ctx.navigator().stop();
            WorldView world = ctx.worldView();
            if (world == null) {
                giveUp(ctx, 1200);
                return;
            }
            session = new BuildSession(BuildPlanner.schedule(plan, world));
            // Rozpočet: vzdát se dřív, než zůstane stavba poloviční.
            if (BotNeeds.assess(ctx.serverView().latest()).buildingBlocks()
                    < plan.cells().size()) {
                giveUp(ctx, 2400);
                return;
            }
            phase = Phase.SESSION;
            return;
        }
        ctx.navigator().navigateTo(ctx.position(),
                dev.botalive.core.pathfinding.PathGoal.near(stand, 2));
        if (!ctx.navigator().navigating()) {
            // Staveniště nedostupné (typicky v backoffu nedosažitelnosti po
            // dálkovém selhání) – cooldown musí být delší než backoff, jinak
            // se claim/hláška točí naprázdno každou minutu.
            giveUp(ctx, 2400);
        }
    }

    private void tickSession(BotContext ctx, Bot bot) {
        switch (session.tick(ctx)) {
            case RUNNING -> {
            }
            case DONE -> finishProject(ctx, bot);
            case BLOCKED_MATERIAL, UNREACHABLE -> giveUp(ctx, 2400);
        }
    }

    private void finishProject(BotContext ctx, Bot bot) {
        var tier = ctx.settlements().projectFinished(project.settlementId(), project.kind());
        claimed = false;
        String name = settlementName(ctx, bot);
        if (name != null) {
            ctx.chat().sayFrom(donePhrase(project.kind()), phraseArg(project.kind(), name));
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
        // Zamluvení se při přepnutí cíle DRŽÍ (závazek + bonus utility) –
        // stavitel se ke stavbě vrátí; kdyby nadobro zmizel, zamluvení ve
        // službě expiruje a projekt si vezme soused. Nová session naváže
        // tam, kde stavba skončila (položené bloky se přeskakují).
        if (session != null) {
            session.cancel(ctx(bot));
        }
        ctx(bot).navigator().stop();
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
        if (project == null) {
            return "stavím studnu pro sídlo";
        }
        if (project.kind().isWorkshop()) {
            return "stavím dílnu pro sídlo – "
                    + Workshops.spec(project.kind().workshopRole().orElseThrow()).csName();
        }
        return switch (project.kind()) {
            case WELL -> "stavím studnu pro sídlo";
            case GRANARY -> "stavím sýpku pro sídlo";
            case MARKET_STALL -> "stavím tržiště pro sídlo";
            default -> "stavím pro sídlo";
        };
    }
}
