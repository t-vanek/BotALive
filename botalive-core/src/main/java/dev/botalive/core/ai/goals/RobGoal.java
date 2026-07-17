package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.ai.BotNeeds;
import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.pvp.PvpCoordinator;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;

import java.util.Optional;
import java.util.UUID;

/**
 * Přepadení z krajní nouze – hladovějící bot si kořist vezme silou.
 *
 * <p>Poslední záchrana, když bot hladoví, nemá jídlo a krádež z truhly
 * nevyšla. <b>Vždy respektuje nastavení PvP</b>: bez {@code pvp.enabled}
 * se nekoná, útok na hráče vyžaduje {@code pvp.attack-players}, na boty
 * {@code pvp.attack-bots}, a platí férovostní stropy koordinátora
 * ({@link PvpCoordinator#mayEngage}). Kamarády z paměti bot nepřepadá.
 * Po vítězství posbírá, co z oběti vypadlo. Vypínatelné přes
 * {@code ai.desperation}.</p>
 */
public final class RobGoal extends AbstractGoal {

    private enum Phase { HUNT, LOOT, DONE }

    private final PvpCoordinator pvp;

    private Phase phase = Phase.HUNT;
    private UUID targetUuid;
    private boolean registered;
    private int lostTicks;
    private int cooldownTicks;
    private BlockPos lootPos;
    private int lootTicks;

    /**
     * @param pvp koordinátor PvP (férovost, config gating)
     */
    public RobGoal(PvpCoordinator pvp) {
        super("rob");
        this.pvp = pvp;
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        if (!ctx.config().ai().desperation() || !ctx.config().pvp().enabled()
                || !ctx.config().combat().enabled() || ctx.clientState().dead()) {
            return 0;
        }
        BotNeeds needs = BotNeeds.assess(ctx.serverView().latest());
        if (!needs.starving(ctx.clientState().food())) {
            return 0; // loupí se jen z hladu, ne z chamtivosti
        }
        Optional<TrackedEntity> victim = findVictim(ctx, bot);
        if (victim.isEmpty()) {
            return 0;
        }
        double aggression = bot.personality().trait(Trait.AGGRESSION);
        double courage = bot.personality().trait(Trait.COURAGE);
        // Pod krádeží z truhly (30) – násilí je až poslední možnost.
        return 14 + aggression * 8 + courage * 4;
    }

    @Override
    public void start(Bot bot) {
        BotContext ctx = ctx(bot);
        phase = Phase.HUNT;
        lostTicks = 0;
        lootPos = null;
        lootTicks = 0;
        registered = false;
        targetUuid = findVictim(ctx, bot).map(TrackedEntity::uuid).orElse(null);
        if (targetUuid == null) {
            finish(ctx, 400);
            return;
        }
        registered = pvp.registerAttacker(targetUuid, bot.id(), false);
        if (!registered) {
            finish(ctx, 400); // férovostní strop / config – nechat být
            return;
        }
        if (ctx.rng().chance(0.5)) {
            ctx.chat().say("mam hlad... dej sem neco k jidlu nebo si to vezmu!");
        }
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        switch (phase) {
            case HUNT -> tickHunt(ctx, bot);
            case LOOT -> tickLoot(ctx);
            case DONE -> {
            }
        }
    }

    private void tickHunt(BotContext ctx, Bot bot) {
        if (targetUuid == null || !registered) {
            finish(ctx, 400);
            return;
        }
        Optional<TrackedEntity> target = ctx.entities().byUuid(targetUuid);
        if (target.isEmpty()) {
            // Oběť padla nebo zmizela – jít pro kořist na poslední známé místo.
            if (++lostTicks > 40) {
                ctx.combat().disengage();
                if (lootPos != null) {
                    // Oběť padla – povedené přepadení formuje povahu.
                    ctx.gainExperience(dev.botalive.core.personality.PersonalityEvolution
                            .BotExperience.ROB_SUCCESS);
                    phase = Phase.LOOT;
                    lootTicks = 0;
                } else {
                    finish(ctx, 2400);
                }
            }
            return;
        }
        lostTicks = 0;
        TrackedEntity entity = target.get();
        Vec3 pos = entity.position();
        if (pos != null) {
            lootPos = pos.toBlockPos();
        }
        if (pos == null || pos.distance(ctx.position()) > 32) {
            finish(ctx, 2400); // utekl – nehonit přes půl mapy
            return;
        }
        if (ctx.combat().target() == null
                || ctx.combat().target().entityId() != entity.entityId()) {
            ctx.combat().engage(entity);
        }
        ctx.requestMove(ctx.combat().tick(ctx.position(), ctx.clientState().health(),
                ctx.onGround(), ctx.serverView().latest()));
    }

    /** Po vítězství posbírat, co vypadlo (chvíli pobýt u místa souboje). */
    private void tickLoot(BotContext ctx) {
        if (lootPos == null || ++lootTicks > 120) {
            finish(ctx, 6000);
            return;
        }
        if (ctx.position().toBlockPos().distanceSquared(lootPos) > 2 * 2) {
            ctx.navigator().navigateTo(ctx.position(), lootPos);
            if (!ctx.navigator().navigating()) {
                finish(ctx, 6000);
            }
            return;
        }
        ctx.navigator().stop(); // dropy k botovi přitáhne server sám
    }

    @Override
    public void stop(Bot bot) {
        BotContext ctx = ctx(bot);
        ctx.combat().disengage();
        if (targetUuid != null) {
            pvp.unregisterAttacker(targetUuid, bot.id());
        }
        super.stop(bot);
    }

    @Override
    public boolean finished(Bot bot) {
        return phase == Phase.DONE;
    }

    @Override
    public String explain(Bot bot) {
        return switch (phase) {
            case HUNT -> "hlad mě dohnal k nejhoršímu – přepadám kvůli jídlu";
            case LOOT -> "sbírám kořist po souboji";
            case DONE -> null;
        };
    }

    // ==================================================================

    private void finish(BotContext ctx, int cooldown) {
        if (registered && targetUuid != null) {
            pvp.unregisterAttacker(targetUuid, ctx.bot().id());
            registered = false;
        }
        ctx.combat().disengage();
        cooldownTicks = cooldown;
        phase = Phase.DONE;
    }

    /** Nejbližší povolená oběť: hráč/bot do 14 bloků, ne kamarád, dle pvp configu. */
    private Optional<TrackedEntity> findVictim(BotContext ctx, Bot bot) {
        return ctx.entities().nearby(ctx.position(), 14, TrackedEntity::isPlayer).stream()
                .filter(entity -> !isFriend(bot, entity.uuid()))
                .filter(entity -> pvp.mayEngage(bot, entity.uuid(), false))
                .findFirst();
    }

    private boolean isFriend(Bot bot, UUID uuid) {
        return bot.memory().recallAbout(uuid).stream()
                .anyMatch(record -> record.kind() == MemoryKind.FRIEND);
    }
}
