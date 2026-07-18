package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.chat.PhraseCategory;
import dev.botalive.core.entity.TrackedEntity;

import java.util.Optional;

/**
 * Boj s nepřáteli – hostilní moby v okolí a hráči, kteří botovi ublížili.
 *
 * <p>Výběr cíle: nejbližší hostilní mob v dohledu, nebo hráč vedený v paměti
 * jako {@link MemoryKind#ENEMY} (pomsta – závisí na agresivitě). Vlastní souboj
 * řídí {@link dev.botalive.core.combat.CombatController} (strafing, sprint
 * reset, ústup). Utility škáluje s odvahou, agresivitou a aktuálním zdravím.</p>
 */
public final class CombatGoal extends AbstractGoal {

    private int lostTargetTicks;

    /** Vytvoří cíl. */
    public CombatGoal() {
        super("combat");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (!ctx.config().combat().enabled() || ctx.clientState().dead()) {
            return 0;
        }
        float health = ctx.clientState().health();
        double courage = bot.personality().trait(Trait.COURAGE);
        double aggression = bot.personality().trait(Trait.AGGRESSION);
        if (health < 6 + (1.0 - courage) * 6) {
            return 0; // moc zraněný – přenechat SurviveGoal
        }
        Optional<TrackedEntity> target = findTarget(ctx, bot);
        if (target.isEmpty()) {
            return 0;
        }
        double distance = target.get().position().distance(ctx.position());
        double proximityUrgency = Math.max(0, 24 - distance);
        return 25 + proximityUrgency * 2 + aggression * 20 + courage * 10;
    }

    @Override
    public void start(Bot bot) {
        lostTargetTicks = 0;
        equipShieldToOffhand(ctx(bot), bot);
    }

    /**
     * Přesune štít z inventáře do druhé ruky (server-side, na vlákně entity).
     * Hráči nosí štít v offhandu trvale – bot dělá totéž při vstupu do boje.
     */
    private void equipShieldToOffhand(BotContext ctx, Bot bot) {
        if (!ctx.config().combat().shieldUse()) {
            return;
        }
        org.bukkit.entity.Player player = org.bukkit.Bukkit.getPlayer(bot.id());
        if (player == null) {
            return;
        }
        ctx.bridge().runForEntity(player, () -> {
            var inv = player.getInventory();
            if (!inv.getItemInOffHand().getType().isAir()) {
                return;
            }
            int slot = inv.first(org.bukkit.Material.SHIELD);
            if (slot >= 0) {
                inv.setItemInOffHand(inv.getItem(slot));
                inv.setItem(slot, null);
            }
        }, null);
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        TrackedEntity current = ctx.combat().target();

        // Ztráta cíle (zabit/despawn/utekl) → zkusit najít nový.
        boolean currentValid = current != null
                && ctx.entities().byId(current.entityId()).isPresent()
                && current.position().distance(ctx.position()) < 32;
        if (!currentValid) {
            if (current != null) {
                // Cíl zmizel z trackeru – pravděpodobně zabit.
                if (ctx.entities().byId(current.entityId()).isEmpty()) {
                    ctx.stats().addKill();
                    if (ctx.rng().chance(0.3)) {
                        ctx.chat().sayFrom(PhraseCategory.COMBAT_TAUNTS, null);
                    }
                }
                ctx.combat().disengage();
            }
            Optional<TrackedEntity> next = findTarget(ctx, bot);
            if (next.isPresent()) {
                ctx.combat().engage(next.get());
                lostTargetTicks = 0;
            } else {
                lostTargetTicks++;
                return;
            }
        }

        // Bojový pohyb (výběr zbraně – melee/luk/štít – řídí controller);
        // strafing a ústup nesmí přenést bota přes hranu srázu či voidu.
        ctx.requestMove(EdgeGuard.apply(ctx.worldView(), ctx.position(),
                ctx.combat().tick(ctx.position(), ctx.clientState().health(),
                        ctx.onGround(), ctx.serverView().latest())));
    }

    @Override
    public void stop(Bot bot) {
        ctx(bot).combat().disengage();
        super.stop(bot);
    }

    @Override
    public boolean finished(Bot bot) {
        return lostTargetTicks > 40;
    }

    /**
     * Najde vhodný cíl – hostilní mob, nebo neutrální zvíře, které bota
     * nedávno napadlo (rozzuřený vlk, včely...). Souboje s hráči a jinými
     * boty (obrana, pomsta, aliance) řeší {@code PvpGoal} s vlastními
     * pojistkami.
     */
    private Optional<TrackedEntity> findTarget(BotContext ctx, Bot bot) {
        double viewDistance = ctx.config().ai().viewDistanceBlocks();
        Optional<TrackedEntity> hostile = ctx.entities()
                .nearest(ctx.position(), viewDistance, TrackedEntity::isHostile);
        if (hostile.isPresent()) {
            return hostile;
        }
        // Neutrální útočník z čerstvé paměti (ne hráč – to je věc PvpGoal,
        // a nikdy ne vlastní mazlíček).
        long now = System.currentTimeMillis();
        return ctx.entities().nearby(ctx.position(), viewDistance,
                        e -> !e.isPlayer() && e.uuid() != null)
                .stream()
                .filter(e -> bot.memory().recallAbout(e.uuid()).stream()
                        .anyMatch(r -> r.kind() == MemoryKind.ENEMY
                                && now - r.updatedAt() < 60_000))
                .filter(e -> bot.memory().recallAbout(e.uuid()).stream()
                        .noneMatch(r -> r.kind() == MemoryKind.PET))
                .findFirst();
    }

    @Override
    public String explain(dev.botalive.api.bot.Bot bot) {
        return "bojuju o život!";
    }
}
