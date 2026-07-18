package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.chat.PhraseCategory;
import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.pvp.PvpCoordinator;
import dev.botalive.core.util.Vec3;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * PvP – souboje s jinými boty a hráči, včetně aliancí.
 *
 * <p>Čtyři zdroje motivace (v pořadí priority):</p>
 * <ol>
 *   <li><b>Obrana</b> – bot byl právě napaden; statečný se brání (zbabělce
 *       přebije {@code SurviveGoal} a uteče). Obrana je povolená vždy, když
 *       je PvP zapnuté – i proti hráčům.</li>
 *   <li><b>Asistence</b> – spojenec volá o pomoc ({@link PvpCoordinator});
 *       společný boj prohlubuje přátelství (FRIEND paměť).</li>
 *   <li><b>Pomsta</b> – čerstvý nepřítel z paměti je poblíž.</li>
 *   <li><b>Vyvolání potyčky</b> – velmi agresivní a odvážní boti si občas
 *       najdou oběť sami (útok na hráče jen s {@code pvp.attack-players}).</li>
 * </ol>
 *
 * <p>Nikdy neútočí na spojence a respektuje férovostní strop útočníků na
 * jeden cíl. Vlastní souboj vede {@code CombatController} (strafing, sprint
 * reset, štít, luk) – proti hráčům bojuje bot úplně stejně jako proti mobům.</p>
 */
public final class PvpGoal extends AbstractGoal {

    /** Čerstvost ENEMY vzpomínky pro pomstu (ms). */
    private static final long FEUD_FRESHNESS_MS = 10 * 60_000;

    private final PvpCoordinator pvp;

    private UUID targetUuid;
    private boolean defensive;
    private UUID assistedFriend;
    private boolean registered;
    private int lostTicks;
    private int cooldownTicks;
    private boolean announced;

    /**
     * @param pvp sdílený PvP koordinátor
     */
    public PvpGoal(PvpCoordinator pvp) {
        super("pvp");
        this.pvp = pvp;
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        if (!ctx.config().pvp().enabled() || ctx.clientState().dead()
                || !ctx.config().combat().enabled()) {
            return 0;
        }
        double courage = bot.personality().trait(Trait.COURAGE);
        double aggression = bot.personality().trait(Trait.AGGRESSION);
        double helpfulness = bot.personality().trait(Trait.HELPFULNESS);

        // 1) Obrana po napadení.
        Optional<PvpCoordinator.Threat> threat = pvp.threat(bot.id());
        if (threat.isPresent()
                && resolve(ctx, threat.get().attacker()).isPresent()
                && pvp.mayEngage(bot, threat.get().attacker(), true)) {
            return 28 + courage * 20 + aggression * 8;
        }
        // 2) Asistence spojenci.
        Optional<PvpCoordinator.Assist> assist = pvp.assist(bot.id());
        if (assist.isPresent()
                && resolve(ctx, assist.get().target()).isPresent()
                && pvp.mayEngage(bot, assist.get().target(), true)) {
            return 22 + helpfulness * 12 + courage * 6;
        }
        // 3) Pomsta čerstvému nepříteli poblíž.
        if (findFeudTarget(ctx, bot).isPresent()) {
            return 8 + aggression * 14;
        }
        // 4) Vyvolání potyčky – jen rváči.
        if (aggression > 0.7 && courage > 0.55 && findPickFightTarget(ctx, bot).isPresent()) {
            return 2 + (aggression - 0.7) * 30;
        }
        return 0;
    }

    @Override
    public void start(Bot bot) {
        BotContext ctx = ctx(bot);
        targetUuid = null;
        assistedFriend = null;
        registered = false;
        announced = false;
        lostTicks = 0;

        // Výběr cíle ve stejné prioritě jako utility.
        Optional<PvpCoordinator.Threat> threat = pvp.threat(bot.id());
        if (threat.isPresent() && pvp.mayEngage(bot, threat.get().attacker(), true)) {
            targetUuid = threat.get().attacker();
            defensive = true;
            // Sebeobrana obchází férovostní strop.
            registered = pvp.registerAttacker(targetUuid, bot.id(), true);
            if (ctx.rng().chance(0.35)) {
                ctx.chat().sayFrom(PhraseCategory.PVP_HELP_CALLS, null);
            }
            return;
        }
        Optional<PvpCoordinator.Assist> assist = pvp.assist(bot.id());
        if (assist.isPresent() && pvp.mayEngage(bot, assist.get().target(), true)) {
            targetUuid = assist.get().target();
            assistedFriend = assist.get().friend();
            defensive = true;
            registered = pvp.registerAttacker(targetUuid, bot.id(), false);
            if (registered && ctx.rng().chance(0.4)) {
                ctx.chat().sayFrom(PhraseCategory.PVP_ASSIST, null);
            }
            return;
        }
        Optional<TrackedEntity> feud = findFeudTarget(ctx, bot);
        if (feud.isPresent()) {
            targetUuid = feud.get().uuid();
            defensive = false;
            registered = pvp.registerAttacker(targetUuid, bot.id(), false);
            return;
        }
        Optional<TrackedEntity> pick = findPickFightTarget(ctx, bot);
        if (pick.isPresent()) {
            targetUuid = pick.get().uuid();
            defensive = false;
            registered = pvp.registerAttacker(targetUuid, bot.id(), false);
        }
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        if (targetUuid == null || !registered) {
            finish(ctx, 400);
            return;
        }
        Optional<TrackedEntity> target = resolve(ctx, targetUuid);
        if (target.isEmpty()) {
            // Cíl zmizel – poražen, odpojen nebo utekl z dohledu.
            if (++lostTicks > 40) {
                onVictoryOrEscape(ctx, bot);
            }
            return;
        }
        lostTicks = 0;
        TrackedEntity entity = target.get();
        if (entity.position().distance(ctx.position()) > 40) {
            onVictoryOrEscape(ctx, bot); // nechat běžet – nehonit přes půl mapy
            return;
        }
        if (ctx.combat().target() == null
                || ctx.combat().target().entityId() != entity.entityId()) {
            ctx.combat().engage(entity);
        }
        // Bojový pohyb s ochranou hran – strafing nesmí bota poslat ze srázu.
        ctx.requestMove(EdgeGuard.apply(ctx.worldView(), ctx.position(),
                ctx.combat().tick(ctx.position(), ctx.clientState().health(),
                        ctx.onGround(), ctx.serverView().latest())));
    }

    @Override
    public void stop(Bot bot) {
        BotContext ctx = ctx(bot);
        ctx.combat().disengage();
        if (targetUuid != null) {
            pvp.unregisterAttacker(targetUuid, bot.id());
        }
        pvp.clearAssist(bot.id());
        super.stop(bot);
    }

    @Override
    public boolean finished(Bot bot) {
        return cooldownTicks > 0;
    }

    /** Konec souboje: hlášky, prohloubení aliance, cooldown. */
    private void onVictoryOrEscape(BotContext ctx, Bot bot) {
        boolean targetGone = ctx.entities().byUuid(targetUuid).isEmpty();
        if (targetGone && !announced) {
            announced = true;
            ctx.stats().addKill();
            // Vítězství formuje povahu: odvaha a chuť do soubojů rostou.
            ctx.gainExperience(dev.botalive.core.personality.PersonalityEvolution
                    .BotExperience.PVP_KILL);
            if (ctx.rng().chance(0.4)) {
                ctx.chat().sayFrom(PhraseCategory.PVP_TAUNTS, null);
            }
        }
        // Společný boj prohlubuje přátelství se zachráněným spojencem.
        if (assistedFriend != null && ctx.worldView() != null) {
            Vec3 pos = ctx.position();
            bot.memory().remember(MemoryKind.FRIEND, ctx.worldView().worldName(),
                    (int) pos.x(), (int) pos.y(), (int) pos.z(), assistedFriend,
                    Map.of("bond", "fought-together"), 0.6);
        }
        // Iniciované potyčky mají výrazně delší cooldown než obrana.
        finish(ctx, defensive ? ctx.rng().rangeInt(400, 1200)
                : ctx.rng().rangeInt(2400, 9600));
    }

    private void finish(BotContext ctx, int cooldown) {
        cooldownTicks = cooldown;
    }

    /** Cíl (hráč/bot) podle UUID v dohledu. */
    private Optional<TrackedEntity> resolve(BotContext ctx, UUID uuid) {
        return ctx.entities().byUuid(uuid).filter(TrackedEntity::isPlayer);
    }

    /** Čerstvý nepřítel z paměti, který je poblíž a smí být napaden. */
    private Optional<TrackedEntity> findFeudTarget(BotContext ctx, Bot bot) {
        long now = System.currentTimeMillis();
        return ctx.entities().nearby(ctx.position(),
                        ctx.config().ai().viewDistanceBlocks(), TrackedEntity::isPlayer)
                .stream()
                .filter(e -> e.uuid() != null)
                .filter(e -> bot.memory().recallAbout(e.uuid()).stream()
                        .anyMatch(r -> r.kind() == MemoryKind.ENEMY
                                && now - r.updatedAt() < FEUD_FRESHNESS_MS))
                .filter(e -> pvp.mayEngage(bot, e.uuid(), false))
                .findFirst();
    }

    /** Oběť pro vyvolanou potyčku – nejbližší ne-spojenec, na kterého se smí. */
    private Optional<TrackedEntity> findPickFightTarget(BotContext ctx, Bot bot) {
        return ctx.entities().nearby(ctx.position(), 16, TrackedEntity::isPlayer)
                .stream()
                .filter(e -> e.uuid() != null)
                .filter(e -> pvp.mayEngage(bot, e.uuid(), false))
                .filter(e -> pvp.attackerCount(e.uuid())
                        < ctx.config().pvp().maxAttackersPerTarget())
                .findFirst();
    }
}
