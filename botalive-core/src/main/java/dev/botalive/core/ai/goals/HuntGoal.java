package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.bot.ServerSideView;
import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.inventory.InventoryHelper;
import dev.botalive.core.inventory.Items;

import org.bukkit.Material;

import java.util.Optional;
import java.util.function.Predicate;

/**
 * Lov zvěře – maso, kůže a peří (vanilla mechanika zabíjení zvířat).
 *
 * <p>Bot vyhledá lovnou zvěř (kráva, prase, ovce, slepice, králík), zabije ji
 * bojovým kontrolérem (stejné pakety jako v boji s moby) a kořist sebere
 * {@code CollectItemsGoal}. Session má limit úlovků – lovec nevybíjí celá
 * stáda – a poté cooldown. Utility roste s hladem a chybějícím jídlem
 * v inventáři.</p>
 *
 * <p>Druhý režim je <b>lov pavouků na provázek</b>: když bot chce luk nebo
 * prut (nemá ani jedno) a nemá provázek, cíleně skolí pavouka – jinak provázek
 * chodí jen náhodou z boje, takže luk, šípy i rybářský prut visely na štěstí.
 * Zvěř na jídlo má přednost; pavouk se loví, jen když poblíž není zvěř a bot
 * je dost zdravý na boj.</p>
 */
public final class HuntGoal extends AbstractGoal {

    /** Maximální počet úlovků v jedné lovecké session. */
    private static final int MAX_KILLS_PER_SESSION = 3;
    /** Minimální zdraví na lov pavouka (kouse, na rozdíl od krávy). */
    private static final int SPIDER_HUNT_MIN_HEALTH = 12;

    private int kills;
    private int lostTargetTicks;
    private int cooldownTicks;
    private TrackedEntity target;
    /** {@code true} = tahle session loví pavouky na provázek, ne zvěř na jídlo. */
    private boolean stringHunt;

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
        int viewDistance = ctx.config().ai().viewDistanceBlocks();
        // Zvěř na jídlo/suroviny má přednost (původní chování).
        if (ctx.entities().nearest(ctx.position(), viewDistance,
                TrackedEntity::isHuntableAnimal).isPresent()) {
            var snapshot = ctx.serverView().latest();
            boolean hasFood = snapshot != null && snapshot.hasItem(InventoryHelper::isFood);
            double hungerPressure = Math.max(0, 16 - ctx.clientState().food());
            double aggression = bot.personality().trait(Trait.AGGRESSION);
            return 4 + aggression * 8 + hungerPressure * 1.5 + (hasFood ? 0 : 8);
        }
        // Není zvěř, ale bot chce provázek a poblíž je pavouk → lov na provázek.
        if (wantsString(ctx) && ctx.entities().nearest(ctx.position(), viewDistance,
                TrackedEntity::isSpider).isPresent()) {
            return 5 + bot.personality().trait(Trait.AGGRESSION) * 5;
        }
        return 0;
    }

    @Override
    public void start(Bot bot) {
        BotContext ctx = ctx(bot);
        int viewDistance = ctx.config().ai().viewDistanceBlocks();
        boolean animalNearby = ctx.entities().nearest(ctx.position(), viewDistance,
                TrackedEntity::isHuntableAnimal).isPresent();
        // Zvěř má přednost; na provázek se jde jen bez zvěře a s chutí po luku/prutu.
        stringHunt = !animalNearby && wantsString(ctx);
        kills = 0;
        lostTargetTicks = 0;
        target = null;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        Predicate<TrackedEntity> preyKind = stringHunt
                ? TrackedEntity::isSpider : TrackedEntity::isHuntableAnimal;

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
                    ctx.config().ai().viewDistanceBlocks(), preyKind);
            if (prey.isEmpty() || kills >= MAX_KILLS_PER_SESSION) {
                lostTargetTicks++;
                return;
            }
            target = prey.get();
            ctx.combat().engage(target);
            lostTargetTicks = 0;
        }

        // Zbraň do ruky a lovecký pohyb řídí bojový kontrolér.
        dev.botalive.core.physics.MoveInput huntMove = ctx.combat().tick(
                ctx.position(), ctx.clientState().health(),
                ctx.onGround(), ctx.serverView().latest());
        if (huntMove != null) {
            ctx.requestMove(huntMove); // null = přiblížení/ústup řídí navigace
        }
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

    /**
     * Chce bot provázek na luk/prut? Nemá luk ani prut, došel mu provázek a je
     * dost zdravý na boj s pavoukem.
     */
    private static boolean wantsString(BotContext ctx) {
        if (ctx.clientState().health() < SPIDER_HUNT_MIN_HEALTH) {
            return false;
        }
        ServerSideView.Snapshot snapshot = ctx.serverView().latest();
        if (snapshot == null) {
            return false;
        }
        boolean hasBow = snapshot.hasItem(Items::isBow);
        boolean hasRod = snapshot.hasItem(m -> m == Material.FISHING_ROD);
        return !hasBow && !hasRod
                && InventoryHelper.countItem(snapshot, Material.STRING) < 3;
    }

    @Override
    public String explain(dev.botalive.api.bot.Bot bot) {
        return stringHunt ? "lovím pavouky na provázek" : "lovím zvěř, ať mám co jíst";
    }
}
