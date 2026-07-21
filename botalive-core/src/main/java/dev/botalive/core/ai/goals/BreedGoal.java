package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.husbandry.BreedService;
import dev.botalive.core.bot.ServerSideView;
import dev.botalive.core.pathfinding.PathGoal;
import dev.botalive.core.util.Vec3;

import org.bukkit.Material;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;

import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * Chov hospodářských zvířat – vanilla rozmnožování.
 *
 * <p>Bot s krmivem v hotbaru najde v okolí dvě <b>dospělá</b> zvířata téhož
 * druhu (kráva, ovce, prase, slepice, koza, králík), dojde k nim, vezme jejich
 * krmivo (pšenice / mrkev / semínka) a klikne na ně – přesně jako hráč. O
 * srdíčka, love mode, narození mláděte i pětiminutový cooldown se stará
 * <b>vanilla server</b>; {@link BreedService} jen autoritativně ověří, že jde
 * o dospělé jedince připravené k páření, ať se krmivo neplýtvá na mláďata.</p>
 *
 * <p>Chová se přirozeně u profese farmáře (a krotitele); přemnožení brání
 * strop stáda v okolí. Stádo je zdroj masa, vlny a kůže – potkává se s
 * farmařením i trhem.</p>
 */
public final class BreedGoal extends AbstractGoal {

    private enum Phase { FIND, APPROACH, VERIFY, FEED, SUCCESS, DONE }

    /** Poloměr, ve kterém bot hledá zvířata (bloky). */
    private static final int SCAN_RADIUS = 16;
    /** Strop viditelného stáda druhu – nad ním se nechová (přemnožení). */
    private static final int HERD_CAP = 8;
    /** Dva nakrmení dospělí = jedno mládě. */
    private static final int NEED_TO_BREED = 2;
    /** Strop pokusů v jedné seanci. */
    private static final int MAX_ATTEMPTS = 12;

    private final BreedService breeding;

    private Phase phase = Phase.DONE;
    private EntityType species;
    private final ArrayDeque<UUID> targets = new ArrayDeque<>();
    private UUID currentUuid;
    private int currentEntityId;
    private int fed;
    private int attempts;
    private int waitTicks;
    private int cooldownTicks;
    private CompletableFuture<BreedService.BreedCheck> pendingCheck;

    /**
     * @param breeding server-side část chovu
     */
    public BreedGoal(BreedService breeding) {
        super("breed");
        this.breeding = breeding;
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        // Zvířata a plodiny patří do overworldu.
        if (outsideOverworld(ctx) || ctx.worldView() == null) {
            return 0;
        }
        if (pickSpecies(ctx) == null) {
            return 0; // nemám krmivo / není koho chovat / stádo je plné
        }
        // Chov je klidná, sociální práce: podpoří ji trpělivost a ochota pomoci.
        double patience = bot.personality().trait(Trait.PATIENCE);
        double helpfulness = bot.personality().trait(Trait.HELPFULNESS);
        return 3.5 + patience * 6 + helpfulness * 2;
    }

    @Override
    public void start(Bot bot) {
        phase = Phase.FIND;
        species = null;
        targets.clear();
        currentUuid = null;
        fed = 0;
        attempts = 0;
        waitTicks = 0;
        pendingCheck = null;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        switch (phase) {
            case FIND -> tickFind(ctx);
            case APPROACH -> tickApproach(ctx);
            case VERIFY -> tickVerify(ctx, bot);
            case FEED -> tickFeed(ctx);
            case SUCCESS -> tickSuccess(ctx);
            case DONE -> {
            }
        }
    }

    private void tickFind(BotContext ctx) {
        species = pickSpecies(ctx);
        if (species == null) {
            finish(2400);
            return;
        }
        targets.clear();
        for (TrackedEntity animal : ctx.entities().nearby(ctx.position(), SCAN_RADIUS,
                e -> e.type() == species && e.uuid() != null)) {
            targets.add(animal.uuid());
        }
        if (!nextTarget(ctx)) {
            finish(2400);
        }
    }

    private void tickApproach(BotContext ctx) {
        Optional<TrackedEntity> animal = tracked(ctx);
        if (animal.isEmpty()) {
            skipTarget(ctx);
            return;
        }
        Vec3 pos = animal.get().position();
        ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0), pos.add(0, 0.6, 0));
        if (pos.distanceSquared(ctx.position()) > 2.4 * 2.4) {
            ctx.navigator().navigateTo(ctx.position(), PathGoal.near(pos.toBlockPos(), 1));
            if (!ctx.navigator().navigating()) {
                skipTarget(ctx); // nedosažitelné (plot, sráz) – zkus další
            }
            return;
        }
        ctx.navigator().stop();
        pendingCheck = null;
        phase = Phase.VERIFY;
    }

    private void tickVerify(BotContext ctx, Bot bot) {
        if (pendingCheck == null) {
            pendingCheck = breeding.check(bot.id(), currentUuid);
            return;
        }
        if (!pendingCheck.isDone()) {
            return;
        }
        BreedService.BreedCheck check =
                pendingCheck.getNow(BreedService.BreedCheck.MISSING);
        pendingCheck = null;
        if (!check.exists() || !check.adultReady()) {
            skipTarget(ctx); // mládě, na cooldownu, nebo zmizelo
            return;
        }
        waitTicks = ctx.rng().rangeInt(4, 10);
        phase = Phase.FEED;
    }

    private void tickFeed(BotContext ctx) {
        if (--waitTicks > 0) {
            return;
        }
        Optional<TrackedEntity> animal = tracked(ctx);
        if (animal.isEmpty()) {
            skipTarget(ctx);
            return;
        }
        if (animal.get().position().distanceSquared(ctx.position()) > 3.0 * 3.0) {
            phase = Phase.APPROACH; // zvíře popošlo
            return;
        }
        ServerSideView.Snapshot snapshot = ctx.serverView().latest();
        if (snapshot == null
                || !ctx.inventory().equipMatching(snapshot, BreedService.breedingFood(species))) {
            finish(1800); // došlo krmivo / nejde vzít do ruky
            return;
        }
        ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0),
                animal.get().position().add(0, 0.5, 0));
        ctx.actions().interactEntity(currentEntityId);
        fed++;
        attempts++;
        if (fed >= NEED_TO_BREED) {
            phase = Phase.SUCCESS;
            return;
        }
        if (attempts >= MAX_ATTEMPTS || !nextTarget(ctx)) {
            // Nakrmil jen jednoho – páření se nespustí; zkusíme zas později.
            finish(2400);
        }
    }

    private void tickSuccess(BotContext ctx) {
        if (ctx.rng().chance(0.4)) {
            ctx.chat().say(switch (species) {
                case COW, MOOSHROOM -> "krmím dobytek, ať se stádo rozroste";
                case SHEEP -> "přikrmuju ovce, přibude vlny";
                case PIG -> "krmím prasata, bude masa";
                case CHICKEN -> "sype se slepicím, budou kuřata";
                default -> "starám se o chov, ať je zvěře víc";
            });
        }
        finish(ctx.rng().rangeInt(6000, 12000));
    }

    /** Vybere druh, kterého jsou v okolí aspoň dva a bot má na něj krmivo. */
    private EntityType pickSpecies(BotContext ctx) {
        ServerSideView.Snapshot snapshot = ctx.serverView().latest();
        if (snapshot == null) {
            return null;
        }
        Map<EntityType, Integer> counts = new HashMap<>();
        for (TrackedEntity animal : ctx.entities().nearby(ctx.position(), SCAN_RADIUS,
                e -> BreedService.isLivestock(e.type()))) {
            counts.merge(animal.type(), 1, Integer::sum);
        }
        for (Map.Entry<EntityType, Integer> entry : counts.entrySet()) {
            int count = entry.getValue();
            if (count < NEED_TO_BREED || count >= HERD_CAP) {
                continue; // málo na páření, nebo už je stádo plné
            }
            Predicate<Material> food = BreedService.breedingFood(entry.getKey());
            if (food != null && snapshot.hasItem(food)) {
                return entry.getKey(); // krmivo kdekoli v batohu (do ruky ho dotáhne tickFeed)
            }
        }
        return null;
    }

    /** Posune se na další zvíře v seznamu; {@code false} když žádné nezbývá. */
    private boolean nextTarget(BotContext ctx) {
        UUID next = targets.poll();
        if (next == null) {
            return false;
        }
        currentUuid = next;
        Optional<TrackedEntity> animal = ctx.entities().byUuid(next);
        if (animal.isEmpty()) {
            return nextTarget(ctx); // zmizelo mezi skenem a přístupem
        }
        currentEntityId = animal.get().entityId();
        phase = Phase.APPROACH;
        return true;
    }

    /** Aktuální zvíře nevyšlo – zkusí další, jinak seanci ukončí. */
    private void skipTarget(BotContext ctx) {
        if (!nextTarget(ctx)) {
            finish(2400);
        }
    }

    private Optional<TrackedEntity> tracked(BotContext ctx) {
        return currentUuid == null ? Optional.empty() : ctx.entities().byUuid(currentUuid);
    }

    private void finish(int cooldown) {
        cooldownTicks = cooldown;
        phase = Phase.DONE;
    }

    @Override
    public void stop(Bot bot) {
        ctx(bot).navigator().stop();
    }

    @Override
    public boolean finished(Bot bot) {
        return phase == Phase.DONE;
    }

    @Override
    public String explain(Bot bot) {
        return species == null ? "chovám zvířata" : "rozmnožuju stádo";
    }
}
