package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.chat.PhraseCategory;
import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.physics.MoveInput;

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
    /** Útočný splash se hází nejvýš jednou za souboj. */
    private boolean splashThrown;
    /**
     * Cíle, ke kterým se bot v tomhle souboji nedokázal dostat.
     *
     * <p>Bez blacklistu by si mob za zdí vybral znovu hned příští tick a bot
     * by u něj stál dál – jen s krátkou pauzou navíc.</p>
     */
    private final java.util.Set<Integer> unreachableTargets = new java.util.HashSet<>();

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
        splashThrown = false;
        // Nový souboj = nový pokus i na dřív nedosažitelné cíle (bot se mezitím
        // přesunul, mob slezl z římsy).
        unreachableTargets.clear();
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

        // Nedosažitelný cíl (mob přes vodu, za zdí, na římse) se musí pustit.
        // Dřív zůstal "platný" – byl v dosahu trackeru – takže lostTargetTicks
        // nerostlo, finished() nenastalo a bot u něj stál, dokud ho něco
        // nezabilo. Byl to zdaleka nejčastější zdroj nehybných botů.
        if (current != null && ctx.combat().targetUnreachable()) {
            unreachableTargets.add(current.entityId());
            ctx.combat().disengage();
            lostTargetTicks = Integer.MAX_VALUE / 2; // cíl skončí, mozek rozhodne znovu
            return;
        }

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

        maybeThrowSplash(ctx);

        // Bojový pohyb (výběr zbraně – melee/luk/štít – řídí controller).
        // V Endu strafing nesmí přenést bota přes hranu do voidu; v overworldu
        // zůstává pohyb volný – guard by blokoval přiblížení přes seskok.
        MoveInput combatMove = ctx.combat().tick(ctx.position(), ctx.clientState().health(),
                ctx.onGround(), ctx.serverView().latest());
        if (combatMove == null) {
            return; // přiblížení/ústup řídí navigace
        }
        // End chrání každou hranu (void), overworld jen smrtící pády – malé
        // seskoky ke strafingu patří (fáze 18 vs. provozní pády v jeskyních).
        combatMove = ctx.dimension() == dev.botalive.core.world.WorldDimension.END
                ? dev.botalive.core.physics.EdgeGuard.apply(
                        ctx.worldView(), ctx.position(), combatMove)
                : dev.botalive.core.physics.EdgeGuard.applyLethal(
                        ctx.worldView(), ctx.position(), combatMove);
        ctx.requestMove(combatMove);
    }

    /**
     * Útočný splash (zranění/jed) na střední vzdálenost, jednou za souboj.
     * Nemrtvé zranění LÉČÍ a jed na ně nefunguje – ti se vynechávají;
     * balistika je hrubá (mírné nadhození dle vzdálenosti), AoE odpustí.
     */
    private void maybeThrowSplash(BotContext ctx) {
        if (splashThrown || !ctx.config().combat().splashPotions()) {
            return;
        }
        TrackedEntity target = ctx.combat().target();
        if (target == null || target.isUndead() || target.isPlayer()) {
            return;
        }
        double dist = target.position().distance(ctx.position());
        if (dist < 3 || dist > 7) {
            return;
        }
        splashThrown = true; // i bez lektvaru – nescanovat každý tick
        var snapshot = ctx.serverView().latest();
        int slot = dev.botalive.core.inventory.ItemVariants.findSplashSlot(
                snapshot, dev.botalive.core.inventory.ItemVariants.HARMING);
        if (slot < 0) {
            slot = dev.botalive.core.inventory.ItemVariants.findSplashSlot(
                    snapshot, dev.botalive.core.inventory.ItemVariants.POISON);
        }
        if (slot < 0 || slot >= 9) {
            return; // jen z hotbaru – přetahování uprostřed boje nemá čas
        }
        dev.botalive.core.util.Vec3 eye = ctx.position().add(0, 1.62, 0);
        dev.botalive.core.util.Vec3 to = target.position().add(0, 1.0, 0).sub(eye);
        double horizontal = Math.hypot(to.x(), to.z());
        float yaw = (float) Math.toDegrees(Math.atan2(-to.x(), to.z()));
        float pitch = (float) -Math.toDegrees(Math.atan2(to.y(), horizontal));
        pitch -= (float) (dist * 1.5); // oblouk vrhu – kousek nad cíl
        ctx.actions().selectHotbar(slot);
        ctx.actions().useItem(yaw, pitch);
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
        // Nevyprovokovaného hostila napadat jen v „angažovacím" okruhu, který
        // roste s agresí (12 klidný – 24 útočný), ne na celý dohled (32). Dřív
        // bot opouštěl práci, aby honil libovolného moba do 32 bloků – i toho,
        // co ho nenapadl a je 30 bloků daleko v tmě/nebezpečí. Blízké noční
        // moby (spawnují u bota) i tak spadnou do okruhu; sebeobrana níž ne.
        double aggression = bot.personality().trait(Trait.AGGRESSION);
        double engageRadius = 12 + aggression * 12;
        Optional<TrackedEntity> hostile = ctx.entities()
                .nearby(ctx.position(), engageRadius, TrackedEntity::isHostile)
                .stream()
                .filter(e -> !unreachableTargets.contains(e.entityId()))
                .findFirst();
        if (hostile.isPresent()) {
            return hostile;
        }
        // Čerstvý agresor (napadl bota) – sebeobrana/pomsta i na plný dohled
        // (ne hráč – to je věc PvpGoal – a nikdy ne vlastní mazlíček). Sdílený
        // helper s únikem SurviveGoal.
        return ctx.entities().nearby(ctx.position(), viewDistance,
                        e -> !e.isPlayer() && e.uuid() != null)
                .stream()
                .filter(e -> !unreachableTargets.contains(e.entityId()))
                .filter(e -> recentAggressor(bot, e))
                .findFirst();
    }

    @Override
    public String explain(dev.botalive.api.bot.Bot bot) {
        return "bojuju o život!";
    }
}
