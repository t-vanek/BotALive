package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.inventory.InventoryHelper;

import java.util.Optional;

/**
 * Lov zvěře – maso, kůže a peří (vanilla mechanika zabíjení zvířat).
 *
 * <p>Bot vyhledá lovnou zvěř (kráva, prase, ovce, slepice, králík), zabije ji
 * bojovým kontrolérem (stejné pakety jako v boji s moby) a kořist sebere
 * {@code CollectItemsGoal}. Session má limit úlovků – lovec nevybíjí celá
 * stáda – a poté cooldown. Utility roste s hladem a chybějícím jídlem
 * v inventáři.</p>
 */
public final class HuntGoal extends AbstractGoal {

    /** Maximální počet úlovků v jedné lovecké session. */
    private static final int MAX_KILLS_PER_SESSION = 3;

    private int kills;
    private int lostTargetTicks;
    private int cooldownTicks;
    private TrackedEntity target;

    /** Vytvoří cíl. */
    public HuntGoal() {
        super("hunt");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        if (!ctx.config().combat().enabled() || ctx.clientState().dead()) {
            return 0;
        }
        Optional<TrackedEntity> prey = ctx.entities().nearest(ctx.position(),
                ctx.config().ai().viewDistanceBlocks(), TrackedEntity::isHuntableAnimal);
        if (prey.isEmpty()) {
            return 0;
        }
        var snapshot = ctx.serverView().latest();
        boolean hasFood = snapshot != null && snapshot.hasItem(InventoryHelper::isFood);
        double hungerPressure = Math.max(0, 16 - ctx.clientState().food());
        double aggression = bot.personality().trait(Trait.AGGRESSION);
        return 4 + aggression * 8 + hungerPressure * 1.5 + (hasFood ? 0 : 8);
    }

    @Override
    public void start(Bot bot) {
        kills = 0;
        lostTargetTicks = 0;
        target = null;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);

        // Aktuální cíl zmizel (uloven/utekl)?
        boolean targetValid = target != null
                && ctx.entities().byId(target.entityId()).isPresent()
                && target.position().distance(ctx.position()) < 40;
        if (!targetValid) {
            if (target != null && ctx.entities().byId(target.entityId()).isEmpty()) {
                kills++;
                ctx.stats().addKill();
                ctx.combat().disengage();
            }
            Optional<TrackedEntity> prey = ctx.entities().nearest(ctx.position(),
                    ctx.config().ai().viewDistanceBlocks(), TrackedEntity::isHuntableAnimal);
            if (prey.isEmpty() || kills >= MAX_KILLS_PER_SESSION) {
                lostTargetTicks++;
                return;
            }
            target = prey.get();
            ctx.combat().engage(target);
            lostTargetTicks = 0;
        }

        // Zbraň do ruky a lovecký pohyb řídí bojový kontrolér.
        ctx.requestMove(ctx.combat().tick(ctx.position(), ctx.clientState().health(),
                ctx.onGround(), ctx.serverView().latest()));
    }

    @Override
    public void stop(Bot bot) {
        ctx(bot).combat().disengage();
        target = null;
        super.stop(bot);
    }

    @Override
    public boolean finished(Bot bot) {
        if (kills >= MAX_KILLS_PER_SESSION || lostTargetTicks > 60) {
            cooldownTicks = ctx(bot).rng().rangeInt(1200, 3600);
            return true;
        }
        return false;
    }

    @Override
    public String explain(dev.botalive.api.bot.Bot bot) {
        return "lovím zvěř, ať mám co jíst";
    }
}
