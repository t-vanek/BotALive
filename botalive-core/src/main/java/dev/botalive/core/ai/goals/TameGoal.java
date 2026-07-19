package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.tame.TameService;
import dev.botalive.core.util.Vec3;
import org.bukkit.Material;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;
import dev.botalive.core.pathfinding.PathGoal;

/**
 * Ochočování zvířat – vanilla taming mechaniky.
 *
 * <p>Dvě cesty, obě přes reálné pakety a s mechanikou na straně serveru:</p>
 * <ul>
 *   <li><b>Item-based</b> (vlk – kost, kočka – ryba, papoušek – semínka):
 *       bot dojde ke zvířeti, vezme správný item do ruky a opakovaně na něj
 *       klikne s lidskými rozestupy; server hází kostkou a krmí srdíčka.</li>
 *   <li><b>Mount-based</b> (kůň, osel, mula, lama): bot opakovaně nasedá
 *       (interact), kůň ho shazuje (server ruší SetPassengers), dokud si
 *       ho neochočí – přesně jako hráč.</li>
 * </ul>
 *
 * <p>Ochočené zvíře si bot uloží do paměti ({@link MemoryKind#PET}); vlci pak
 * botovi vanilla mechanikou pomáhají v boji, což se přirozeně potkává s PvP
 * aliancemi. Stav ochočení se autoritativně ověřuje přes {@link TameService}.</p>
 */
public final class TameGoal extends AbstractGoal {

    /** Strop ochočených zvířat na bota. */
    private static final int MAX_PETS = 3;

    /** Strop pokusů v jedné session. */
    private static final int MAX_ATTEMPTS = 15;

    private enum Phase { FIND, VERIFY, APPROACH, TAME_ITEM, TAME_MOUNT, SUCCESS, DONE }

    private final TameService taming;

    private Phase phase = Phase.FIND;
    private UUID animalUuid;
    private int animalEntityId;
    private EntityType animalType;
    private CompletableFuture<TameService.TameCheck> pendingCheck;
    private final Set<UUID> rejected = new HashSet<>();
    private int waitTicks;
    private int attempts;
    private int cooldownTicks;

    /**
     * @param taming sdílená služba ochočování
     */
    public TameGoal(TameService taming) {
        super("tame");
        this.taming = taming;
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        if (ctx.clientState().dead() || bot.memory().recall(MemoryKind.PET).size() >= MAX_PETS) {
            return 0;
        }
        if (findCandidate(ctx, bot).isEmpty()) {
            return 0;
        }
        double sociability = bot.personality().trait(Trait.SOCIABILITY);
        double helpfulness = bot.personality().trait(Trait.HELPFULNESS);
        return 5 + sociability * 8 + helpfulness * 6;
    }

    @Override
    public void start(Bot bot) {
        phase = Phase.FIND;
        animalUuid = null;
        pendingCheck = null;
        rejected.clear();
        attempts = 0;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        switch (phase) {
            case FIND -> {
                Optional<TrackedEntity> candidate = findCandidate(ctx, bot);
                if (candidate.isEmpty()) {
                    finish(ctx, 1800);
                    return;
                }
                animalUuid = candidate.get().uuid();
                animalEntityId = candidate.get().entityId();
                animalType = candidate.get().type();
                pendingCheck = null;
                phase = Phase.VERIFY;
            }
            case VERIFY -> {
                // Autoritativní kontrola: zvíře existuje a nikomu nepatří.
                if (pendingCheck == null) {
                    pendingCheck = taming.check(bot.id(), animalUuid);
                    return;
                }
                if (!pendingCheck.isDone()) {
                    return;
                }
                TameService.TameCheck check = pendingCheck.getNow(TameService.TameCheck.MISSING);
                pendingCheck = null;
                if (!check.exists() || !check.tameable() || check.tamedBySomeone()) {
                    rejected.add(animalUuid);
                    phase = Phase.FIND;
                    return;
                }
                phase = Phase.APPROACH;
            }
            case APPROACH -> {
                Optional<TrackedEntity> animal = tracked(ctx);
                if (animal.isEmpty()) {
                    rejected.add(animalUuid);
                    phase = Phase.FIND;
                    return;
                }
                Vec3 pos = animal.get().position();
                ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0), pos.add(0, 0.6, 0));
                if (pos.distanceSquared(ctx.position()) > 2.2 * 2.2) {
                    ctx.navigator().navigateTo(ctx.position(), PathGoal.near(pos.toBlockPos(), 1));
                    if (!ctx.navigator().navigating()) {
                        finish(ctx, 1200);
                    }
                    return;
                }
                ctx.navigator().stop();
                waitTicks = ctx.rng().rangeInt(5, 12);
                phase = TameService.tamedByMounting(animalType)
                        ? Phase.TAME_MOUNT : Phase.TAME_ITEM;
            }
            case TAME_ITEM -> tickItemTaming(ctx, bot);
            case TAME_MOUNT -> tickMountTaming(ctx, bot);
            case SUCCESS -> {
                rememberPet(ctx, bot);
                if (ctx.rng().chance(0.5)) {
                    ctx.chat().say(switch (animalType) {
                        case WOLF -> "mám pejska!";
                        case CAT -> "ochočil jsem kočku";
                        case PARROT -> "mám papouška :D";
                        default -> "ochočil jsem si " + animalType.name().toLowerCase();
                    });
                }
                finish(ctx, ctx.rng().rangeInt(3600, 9600));
            }
            case DONE -> {
                // finished() ukončí
            }
        }
    }

    /** Item-based: klik se správným itemem, lidské rozestupy, průběžné ověřování. */
    private void tickItemTaming(BotContext ctx, Bot bot) {
        // Průběžná kontrola úspěchu (každý ~3. pokus).
        if (pendingCheck != null) {
            if (!pendingCheck.isDone()) {
                return;
            }
            TameService.TameCheck check = pendingCheck.getNow(TameService.TameCheck.MISSING);
            pendingCheck = null;
            if (check.tamedByBot()) {
                phase = Phase.SUCCESS;
                return;
            }
            if (!check.exists() || check.tamedBySomeone()) {
                rejected.add(animalUuid);
                phase = Phase.FIND;
                return;
            }
        }
        if (--waitTicks > 0) {
            return;
        }
        Optional<TrackedEntity> animal = tracked(ctx);
        if (animal.isEmpty()) {
            rejected.add(animalUuid);
            phase = Phase.FIND;
            return;
        }
        if (animal.get().position().distanceSquared(ctx.position()) > 3.0 * 3.0) {
            phase = Phase.APPROACH; // zvíře popošlo
            return;
        }
        Predicate<Material> item = TameService.tamingItem(animalType);
        var snapshot = ctx.serverView().latest();
        int slot = snapshot == null || item == null ? -1 : snapshot.findHotbarSlot(item);
        if (slot < 0) {
            finish(ctx, 1800); // došly kosti/ryby/semínka
            return;
        }
        ctx.actions().selectHotbar(slot);
        ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0),
                animal.get().position().add(0, 0.6, 0));
        ctx.actions().interactEntity(animalEntityId);
        waitTicks = ctx.rng().rangeInt(12, 28);
        if (++attempts % 3 == 0) {
            pendingCheck = taming.check(bot.id(), animalUuid);
        }
        if (attempts >= MAX_ATTEMPTS) {
            finish(ctx, 2400);
        }
    }

    /** Mount-based: nasedat, nechat se shazovat, dokud kůň nepovolí. */
    private void tickMountTaming(BotContext ctx, Bot bot) {
        if (pendingCheck != null && pendingCheck.isDone()) {
            TameService.TameCheck check = pendingCheck.getNow(TameService.TameCheck.MISSING);
            pendingCheck = null;
            if (check.tamedByBot()) {
                ctx.vehicle().requestDismount();
                phase = Phase.SUCCESS;
                return;
            }
            if (!check.exists() || check.tamedBySomeone()) {
                if (ctx.vehicle().mounted()) {
                    ctx.vehicle().requestDismount();
                }
                rejected.add(animalUuid);
                phase = Phase.FIND;
                return;
            }
        }
        if (ctx.vehicle().mounted()) {
            // Sedíme – server rozhoduje, jestli nás shodí; občas ověřit úspěch.
            if (--waitTicks <= 0 && pendingCheck == null) {
                pendingCheck = taming.check(bot.id(), animalUuid);
                waitTicks = 40;
            }
            return;
        }
        // Shozen (nebo ještě nenasedl) → znovu nasednout po chvilce.
        if (--waitTicks > 0) {
            return;
        }
        Optional<TrackedEntity> animal = tracked(ctx);
        if (animal.isEmpty()) {
            rejected.add(animalUuid);
            phase = Phase.FIND;
            return;
        }
        if (animal.get().position().distanceSquared(ctx.position()) > 3.0 * 3.0) {
            phase = Phase.APPROACH;
            return;
        }
        // Nasedá se prázdnou rukou (item by koně krmil/sedlal).
        var snapshot = ctx.serverView().latest();
        if (snapshot != null) {
            for (int i = 0; i < 9; i++) {
                if (snapshot.hotbar()[i] == null) {
                    ctx.actions().selectHotbar(i);
                    break;
                }
            }
        }
        ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0),
                animal.get().position().add(0, 1.0, 0));
        ctx.actions().interactEntity(animalEntityId);
        waitTicks = ctx.rng().rangeInt(20, 40);
        if (++attempts >= MAX_ATTEMPTS) {
            finish(ctx, 2400);
        }
    }

    @Override
    public void stop(Bot bot) {
        BotContext ctx = ctx(bot);
        if (phase == Phase.TAME_MOUNT && ctx.vehicle().mounted()) {
            ctx.vehicle().requestDismount();
        }
        super.stop(bot);
    }

    @Override
    public boolean finished(Bot bot) {
        return phase == Phase.DONE;
    }

    private void finish(BotContext ctx, int cooldown) {
        cooldownTicks = cooldown;
        phase = Phase.DONE;
    }

    /** Uloží mazlíčka do paměti. */
    private void rememberPet(BotContext ctx, Bot bot) {
        if (ctx.worldView() == null || animalUuid == null) {
            return;
        }
        Vec3 pos = ctx.position();
        bot.memory().remember(MemoryKind.PET, ctx.worldView().worldName(),
                (int) pos.x(), (int) pos.y(), (int) pos.z(), animalUuid,
                Map.of("type", animalType.name()), 0.9);
    }

    private Optional<TrackedEntity> tracked(BotContext ctx) {
        return animalUuid == null ? Optional.empty() : ctx.entities().byUuid(animalUuid);
    }

    /**
     * Kandidát: ochočitelný druh v dohledu, neodmítnutý, a bot má prostředky
     * (item v hotbaru, u koní se nasedá).
     */
    private Optional<TrackedEntity> findCandidate(BotContext ctx, Bot bot) {
        var snapshot = ctx.serverView().latest();
        return ctx.entities().nearby(ctx.position(), 24, TrackedEntity::isTameableType)
                .stream()
                .filter(e -> e.uuid() != null && !rejected.contains(e.uuid()))
                .filter(e -> bot.memory().recallAbout(e.uuid()).stream()
                        .noneMatch(r -> r.kind() == MemoryKind.PET))
                .filter(e -> {
                    Predicate<Material> item = TameService.tamingItem(e.type());
                    if (item == null) {
                        return true; // mount-based
                    }
                    return snapshot != null && snapshot.findHotbarSlot(item) >= 0;
                })
                .findFirst();
    }
}
