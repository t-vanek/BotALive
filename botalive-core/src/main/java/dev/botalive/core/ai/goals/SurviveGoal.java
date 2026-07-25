package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.physics.MoveInput;
import dev.botalive.core.util.Vec3;

import java.util.Map;
import java.util.Optional;

/**
 * Přežití – nejvyšší priorita, když jde do tuhého.
 *
 * <p>Aktivuje se při nízkém zdraví nebo hoření. Útěk je dvoustupňový:
 * okamžitá přímočará panika drží bota v pohybu hned (v Endu s ochranou
 * hran), a jakmile se dopočítá <b>plánovaný ústup</b>
 * ({@code PathGoal.awayFrom} – po pochozím terénu, žádné slepé kouty,
 * hrany ani láva), převezme řízení navigace. Nebezpečné místo si bot
 * zapamatuje. Utility roste s chybějícím zdravím a klesá s odvahou –
 * stateční boti bojují déle.</p>
 */
public final class SurviveGoal extends AbstractGoal {

    /** Minimální vzdálenost plánovaného ústupu od hrozby (bloky). */
    private static final int FLEE_DISTANCE = 16;
    /**
     * Dosah skenu čerstvých útočníků z paměti (bloky). Záměrně víc než 24
     * u pasivního skenu hostilů: střelec (skeleton, hráč s lukem) trefí bota
     * z 25–32 bloků, tedy dřív, než ho krátký sken vůbec uvidí.
     */
    private static final int AGGRESSOR_RANGE = 32;
    /** Čerstvost ENEMY vzpomínky na hráče-útočníka (ms). */
    private static final long PLAYER_AGGRESSOR_WINDOW_MS = 60_000;
    /** Cíl útěku z ohně: aspoň takhle daleko od hořícího místa (bloky). */
    private static final int FIRE_ESCAPE_DISTANCE = 6;
    private static final int[][] FIRE_ESCAPE_DIRS = {
            {1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};

    private int safeTicks;
    /** Cache zuřícího neutrála (id) + odpočet drahé paměťové kontroly. */
    private int aggressorId = -1;
    private int aggressorScanCooldown;

    /** Vytvoří cíl. */
    public SurviveGoal() {
        super("survive");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        if (ctx.clientState().dead()) {
            return 0;
        }
        float health = ctx.clientState().health();
        var snapshot = ctx.serverView().latest();
        boolean burning = snapshot != null && (snapshot.onFire() || snapshot.inLava());

        double courage = bot.personality().trait(Trait.COURAGE);
        double threshold = 6 + (1.0 - courage) * 6; // 6–12 HP podle odvahy
        if (health > threshold && !burning) {
            return 0;
        }
        double missing = 20 - health;
        double panic = 80 + missing * 8 + (burning ? 60 : 0);
        // Hoření = panika vždy: tick z ohně/lávy aktivně utíká i bez hrozby.
        if (burning) {
            return panic;
        }
        // Nízké zdraví (i kriticky nízké) BEZ hrozby řízení uvolní. Survive
        // bez hrozby v ticku jen stojí – panika 80+ by přebila eat/drink,
        // takže by se bot bez jídla nikdy nezotavil: finished (health≥8)
        // doběhne → mozek hned znovu vybere survive → start() vynuluje
        // safeTicks → stání dokola (motor smyčky smrti; dřív žil v pásmu
        // ≤ 6 HP, kde byla panika bezpodmínečná). Jed/dušení stání na místě
        // stejně nevyléčí – jídlo a lektvary potřebují dostat slovo. Jakmile
        // se hrozba objeví, survive se spustí znovu.
        return threatNear(ctx, bot) ? panic : 0;
    }

    /**
     * Je poblíž hrozba, před kterou má smysl utíkat – hostilní mob do 24
     * bloků, nebo čerstvý útočník z paměti (zuřící neutrál i HRÁČ) do
     * {@link #AGGRESSOR_RANGE}? Read-only (na rozdíl od
     * {@link #aggressorThreat} necachuje aggressorId), aby ho směla volat
     * {@link #utility}.
     *
     * @param ctx kontext bota
     * @param bot bot
     * @return {@code true} pokud je hrozba poblíž
     */
    private boolean threatNear(BotContext ctx, Bot bot) {
        Vec3 pos = ctx.position();
        if (ctx.entities().nearest(pos, 24, TrackedEntity::isHostile).isPresent()) {
            return true;
        }
        for (TrackedEntity entity : ctx.entities().nearby(pos, AGGRESSOR_RANGE,
                e -> e.uuid() != null)) {
            if (freshAggressor(bot, entity)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Čerstvý útočník z paměti. Mobové jdou přes {@link #recentAggressor};
     * hráči mají vlastní větev – {@code recentAggressor} je záměrně vylučuje
     * (sdílí ho {@code CombatGoal}, který na hráče útočit nesmí), jenže před
     * hráčem, který bota právě zbil, se utéct MUSÍ. ENEMY vzpomínku zapisuje
     * {@code ServerEventListener} při každém zásahu (včetně střelce za
     * projektilem), takže tohle vidí i útočníky mimo PvP koordinátor.
     *
     * @param bot    bot
     * @param entity podezřelá entita
     * @return {@code true} pokud entita bota čerstvě napadla
     */
    private static boolean freshAggressor(Bot bot, TrackedEntity entity) {
        if (!entity.isPlayer()) {
            return recentAggressor(bot, entity);
        }
        long now = System.currentTimeMillis();
        return bot.memory().recallAbout(entity.uuid()).stream()
                .anyMatch(r -> r.kind() == MemoryKind.ENEMY
                        && now - r.updatedAt() < PLAYER_AGGRESSOR_WINDOW_MS);
    }

    @Override
    public void start(Bot bot) {
        BotContext ctx = ctx(bot);
        safeTicks = 0;
        aggressorId = -1;
        aggressorScanCooldown = 0;
        ctx.combat().disengage();
        ctx.navigator().stop();
        // Zapamatovat si nebezpečné místo.
        Vec3 pos = ctx.position();
        if (ctx.worldView() != null) {
            bot.memory().remember(MemoryKind.DANGER, ctx.worldView().worldName(),
                    (int) pos.x(), (int) pos.y(), (int) pos.z(), null,
                    Map.of("reason", "low-health"), 0.7);
        }
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        Vec3 pos = ctx.position();

        // Hrozba je i útočník, který v isHostile nefiguruje: zuřící neutrál
        // (enderman, vlk…) i hráč, který bota právě zbil – před oběma se
        // utéct musí, jinak bot pod prahem paniky stojí jako socha na dobití.
        Optional<TrackedEntity> threat = ctx.entities().nearest(pos, 24, TrackedEntity::isHostile);
        if (threat.isEmpty()) {
            threat = aggressorThreat(ctx, bot, pos);
        }
        if (threat.isPresent()) {
            Vec3 threatPos = threat.get().position();
            // Plánovaný ústup: awayFrom vede po pochozím terénu (obchází
            // lávu, hrany i slepé kouty, respektuje DANGER paměť). Drift
            // throttle navigace zvládá pohybující se hrozbu bez replan bouří.
            ctx.navigator().navigateTo(pos, dev.botalive.core.pathfinding.PathGoal
                    .awayFrom(threatPos.toBlockPos(), FLEE_DISTANCE));
            if (!ctx.navigator().pathReady()) {
                // Než se ústup dopočítá, drží bota v pohybu přímočará panika.
                // V Endu nesmí skončit ve voidu; v overworldu zůstává přímá –
                // slepá zatáčka podél hrany umí vrátit bota k hrozbě.
                Vec3 away = pos.sub(threatPos).horizontal().normalized();
                MoveInput flee = MoveInput.of(away, true, ctx.onGround() && ctx.rng().chance(0.2));
                // End chrání každou hranu; overworld jen smrtící pády – slepá
                // zatáčka podél malé hrany by bota vracela k hrozbě, ale útěk
                // do propasti/lávy je horší než hrozba za zády.
                flee = ctx.dimension() == dev.botalive.core.world.WorldDimension.END
                        ? dev.botalive.core.physics.EdgeGuard.apply(ctx.worldView(), pos, flee)
                        : dev.botalive.core.physics.EdgeGuard.applyLethal(ctx.worldView(), pos, flee);
                ctx.requestMove(flee);
                if (flee.direction().horizontalLength() > 1.0E-4) {
                    ctx.humanizer().lookAlong(flee.direction());
                }
            }
            safeTicks = 0;
        } else {
            var snapshot = ctx.serverView().latest();
            boolean burning = snapshot != null
                    && (snapshot.onFire() || snapshot.inLava());
            if (burning) {
                // Hoří a nikdo za tím nestojí: hrozbou je oheň sám – ven
                // z něj, jinak bot uhoří vestoje (utility drží paniku).
                escapeFire(ctx);
                safeTicks = 0;
            } else {
                safeTicks++;
            }
        }
    }

    /**
     * Útěk z ohně/lávy, když kolem není žádná hrozba, od které by se dalo
     * utíkat. Plánovaný ústup vede pryč z místa (plánovač lávě i ohni uhýbá
     * sám); než se dopočítá, jde bot přímým krokem k nejbližšímu sloupci,
     * kde se nehoří.
     *
     * @param ctx kontext bota
     */
    private void escapeFire(BotContext ctx) {
        Vec3 pos = ctx.position();
        ctx.navigator().navigateTo(pos, dev.botalive.core.pathfinding.PathGoal
                .awayFrom(pos.toBlockPos(), FIRE_ESCAPE_DISTANCE));
        if (ctx.navigator().pathReady() || ctx.worldView() == null) {
            return;
        }
        dev.botalive.core.util.BlockPos feet = pos.toBlockPos();
        for (int radius = 1; radius <= 3; radius++) {
            for (int[] d : FIRE_ESCAPE_DIRS) {
                var target = feet.offset(d[0] * radius, 0, d[1] * radius);
                if (!safeFromFire(ctx, target)) {
                    continue;
                }
                Vec3 direction = target.center().sub(pos).horizontal().normalized();
                MoveInput step = MoveInput.of(direction, true, ctx.onGround());
                step = ctx.dimension() == dev.botalive.core.world.WorldDimension.END
                        ? dev.botalive.core.physics.EdgeGuard.apply(
                                ctx.worldView(), pos, step)
                        : dev.botalive.core.physics.EdgeGuard.applyLethal(
                                ctx.worldView(), pos, step);
                ctx.requestMove(step);
                if (step.direction().horizontalLength() > 1.0E-4) {
                    ctx.humanizer().lookAlong(step.direction());
                }
                return;
            }
        }
    }

    /** Sloupec, kde se dá stát a nehoří to (neznámý chunk = nebezpečí). */
    private static boolean safeFromFire(BotContext ctx,
                                        dev.botalive.core.util.BlockPos target) {
        var world = ctx.worldView();
        if (!world.traitsAt(target).passable() || !world.traitsAt(target.up()).passable()
                || !world.traitsAt(target.down()).solid()) {
            return false;
        }
        var at = world.materialAt(target);
        var below = world.materialAt(target.down());
        return at != null && below != null
                && !burningMaterial(at) && !burningMaterial(below);
    }

    private static boolean burningMaterial(org.bukkit.Material material) {
        return material == org.bukkit.Material.LAVA
                || material == org.bukkit.Material.FIRE
                || material == org.bukkit.Material.SOUL_FIRE
                || material == org.bukkit.Material.MAGMA_BLOCK
                || material == org.bukkit.Material.CAMPFIRE
                || material == org.bukkit.Material.SOUL_CAMPFIRE;
    }

    /**
     * Čerstvý útočník z paměti (zuřící neutrál i hráč) – kontrola je dražší
     * (recallAbout na entitu), proto běží po pěti ticích a nalezený útočník
     * se drží podle id.
     */
    private Optional<TrackedEntity> aggressorThreat(BotContext ctx, Bot bot, Vec3 pos) {
        if (aggressorId >= 0) {
            Optional<TrackedEntity> cached = ctx.entities().byId(aggressorId);
            if (cached.isPresent()
                    && cached.get().position().distance(pos) < AGGRESSOR_RANGE) {
                return cached;
            }
            aggressorId = -1;
        }
        if (--aggressorScanCooldown > 0) {
            return Optional.empty();
        }
        aggressorScanCooldown = 5;
        for (TrackedEntity entity : ctx.entities().nearby(pos, AGGRESSOR_RANGE,
                e -> e.uuid() != null)) {
            if (freshAggressor(bot, entity)) {
                aggressorId = entity.entityId();
                return Optional.of(entity);
            }
        }
        return Optional.empty();
    }

    @Override
    public boolean finished(Bot bot) {
        BotContext ctx = ctx(bot);
        float health = ctx.clientState().health();
        var snapshot = ctx.serverView().latest();
        boolean burning = snapshot != null && (snapshot.onFire() || snapshot.inLava());
        // Konec, když je bot chvíli v bezpečí a neregeneruje-li, aspoň nehoří.
        return safeTicks > 60 && !burning && health >= 8;
    }

    @Override
    public String explain(dev.botalive.api.bot.Bot bot) {
        return "teď hlavně přežít";
    }
}
