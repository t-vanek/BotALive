package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.chat.PhraseCategory;
import dev.botalive.core.combat.RangedAttack;
import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.inventory.InventoryHelper;
import dev.botalive.core.inventory.ItemVariants;
import dev.botalive.core.inventory.Items;
import dev.botalive.core.nether.NetherReadiness;
import dev.botalive.core.nether.WitherAltar;
import dev.botalive.core.personality.PersonalityEvolution;
import dev.botalive.core.physics.MoveInput;
import dev.botalive.core.tasks.PlaceBlockTask;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.WorldDimension;
import org.bukkit.Material;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;
import org.geysermc.mcprotocollib.protocol.data.game.entity.type.EntityType;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.Optional;
import dev.botalive.core.pathfinding.PathGoal;

/**
 * Souboj s witherem – vrchol netherové progrese (default vypnutý,
 * {@code nether.wither.enabled}).
 *
 * <p>Bot s lebkami, soul sandem a špičkovou výbavou najde v Netheru rovné
 * místo daleko od vlastní základny i portálu ({@link WitherAltar}), postaví
 * „T", položí krajní lebky – a po prostřední <b>sprintem ustoupí</b>
 * (11 s růstu s nezranitelností je přesně okno na rozestup). Boj kopíruje
 * hráčskou taktiku: zdraví bosse se čte z <b>boss baru</b> (jediný ukazatel,
 * který má i člověk), nad polovinou se střílí lukem s odstupem, obrněná
 * druhá fáze (šípy se odráží) se dobíjí mečem přes standardní
 * {@link dev.botalive.core.combat.CombatController}. Síla z vlastního vaření
 * se pije před bojem. Nether star je trofej ({@code TROPHY type=wither}),
 * vítězství zvedá odvahu.</p>
 *
 * <p>Rozpočet {@code max-fight-minutes} boj utíná – bot uteče a nechá
 * withera witherem (i proto je feature default vypnutá: opuštěný boss je
 * vizitka serveru, ne bota).</p>
 */
public final class WitherFightGoal extends AbstractGoal {

    /** Utility připraveného bota – přebije aktivní nether výpravu (30×1,15). */
    private static final double READY_UTILITY = 38;

    /** Utility rozběhnutého boje – drží bota u bosse (přežití přebíjí dál). */
    private static final double FIGHT_UTILITY = 60;

    /** Minimální odstup oltáře od vlastní základny/portálu (bloky). */
    private static final int SAFE_DISTANCE = 32;

    /** Kolik šípů chce bot mít, než se do withera pustí. */
    private static final int MIN_ARROWS = 24;

    /** Odstup lučištnické fáze (bloky). */
    private static final double RANGED_NEAR = 14;
    private static final double RANGED_FAR = 28;

    /** Kam až ustoupit po položení poslední lebky (bloky). */
    private static final int SUMMON_RETREAT = 20;

    private enum Phase { SITE, GO_SITE, BUILD, SKULLS, RETREAT, FIGHT, COLLECT, DONE }

    private Phase phase = Phase.SITE;
    private BlockPos altarBase;
    private boolean altarAxisX;
    private final Deque<BlockPos> buildQueue = new ArrayDeque<>();
    private final Deque<BlockPos> skullQueue = new ArrayDeque<>();
    private PlaceBlockTask placeTask;
    private RangedAttack bowShot;
    private boolean fightActive;
    private long fightDeadlineMs;
    private int skullTicks;
    private int retreatTicks;
    private int goTicks;
    private int witherGoneTicks;
    private int drinkTicks;
    private int collectTicks;
    private int cooldownTicks;
    /** Doušek síly před bojem: hotovo/vzdáno (láhev se z batohu přitahuje). */
    private boolean strengthDone;
    private int strengthTries;

    /** Vytvoří cíl. */
    public WitherFightGoal() {
        super("wither-fight");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        var cfg = ctx.config().nether().wither();
        if (!cfg.enabled() || !ctx.config().combat().enabled()
                || ctx.clientState().dead() || ctx.dimension() != WorldDimension.NETHER) {
            return 0;
        }
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        if (fightActive) {
            return FIGHT_UTILITY;
        }
        if (bot.personality().trait(Trait.COURAGE) < cfg.minCourage()) {
            return 0;
        }
        var snapshot = ctx.serverView().latest();
        if (snapshot == null || ctx.clientState().health() < 18) {
            return 0;
        }
        boolean equipped = InventoryHelper.countItem(snapshot,
                        Material.WITHER_SKELETON_SKULL) >= WitherAltar.SKULLS_NEEDED
                && InventoryHelper.countItem(snapshot, Material.SOUL_SAND)
                        >= WitherAltar.SOUL_SAND_NEEDED
                && NetherReadiness.assess(snapshot, 5).gearReady(5)
                && snapshot.hasItem(Items::isBow)
                && InventoryHelper.countItem(snapshot, Material.ARROW) >= MIN_ARROWS;
        return equipped ? READY_UTILITY : 0;
    }

    @Override
    public void start(Bot bot) {
        phase = fightActive ? Phase.FIGHT : Phase.SITE;
        BotContext ctx = ctx(bot);
        bowShot = new RangedAttack(ctx.actions(), ctx.humanizer(), ctx.rng(),
                ctx.inventory());
        placeTask = null;
        witherGoneTicks = 0;
        drinkTicks = 0;
        goTicks = 0;
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        if (ctx.worldView() == null || ctx.dimension() != WorldDimension.NETHER) {
            finish(6000);
            return;
        }
        if (drinkTicks > 0) {
            drinkTicks--;
            return;
        }
        switch (phase) {
            case SITE -> tickSite(ctx, bot);
            case GO_SITE -> tickGoSite(ctx);
            case BUILD -> tickBuild(ctx);
            case SKULLS -> tickSkulls(ctx);
            case RETREAT -> tickRetreat(ctx);
            case FIGHT -> tickFight(ctx, bot);
            case COLLECT -> tickCollect(ctx, bot);
            case DONE -> {
                // finished() ukončí
            }
        }
    }

    /** Staveniště: rovina daleko od vlastní základny i portálu domů. */
    private void tickSite(BotContext ctx, Bot bot) {
        BlockPos feet = ctx.position().toBlockPos();
        BlockPos site = WitherAltar.findBuildSite(ctx.worldView(), feet, 20);
        if (site == null || !safeFromBase(bot, ctx, site)) {
            // Tady ne (žádná rovina / moc blízko základny) – zkusit jinde;
            // výprava bota beztak přesouvá po Netheru.
            finish(1800);
            return;
        }
        altarBase = site;
        altarAxisX = ctx.rng().chance(0.5);
        goTicks = 0;
        phase = Phase.GO_SITE;
        if (ctx.rng().chance(0.8)) {
            ctx.chat().sayFrom(PhraseCategory.WITHER_SUMMON, null);
        }
    }

    /** Odstup od OUTPOST/PORTAL vzpomínek – exploze nesmí sebrat základnu. */
    private boolean safeFromBase(Bot bot, BotContext ctx, BlockPos site) {
        String world = ctx.worldView().worldName();
        for (MemoryKind kind : new MemoryKind[]{MemoryKind.OUTPOST, MemoryKind.PORTAL}) {
            var near = bot.memory().recallNearest(kind, world,
                    site.x(), site.y(), site.z());
            if (near.isPresent() && near.get()
                    .distanceSquared(site.x(), site.y(), site.z())
                    < SAFE_DISTANCE * SAFE_DISTANCE) {
                return false;
            }
        }
        return true;
    }

    private void tickGoSite(BotContext ctx) {
        BlockPos stand = WitherAltar.standPoint(altarBase, altarAxisX);
        if (stand.center().distanceSquared(ctx.position()) > 2.5 * 2.5) {
            ctx.navigator().navigateTo(ctx.position(), PathGoal.near(stand, 1));
            if (++goTicks > 400) {
                finish(2400); // staveniště nedosažitelné
            }
            return;
        }
        ctx.navigator().stop();
        if (!WitherAltar.siteUsable(ctx.worldView(), altarBase)) {
            phase = Phase.SITE; // mezitím se něco změnilo
            return;
        }
        buildQueue.clear();
        buildQueue.addAll(WitherAltar.sandPlacements(altarBase, altarAxisX));
        skullQueue.clear();
        skullQueue.addAll(WitherAltar.skullSupports(altarBase, altarAxisX));
        phase = Phase.BUILD;
    }

    /** Pokládka „T" ze soul sandu (každý blok má oporu – pořadí z geometrie). */
    private void tickBuild(BotContext ctx) {
        if (placeTask != null) {
            if (placeTask.tick(ctx)) {
                placeTask = null;
            }
            return;
        }
        BlockPos next = buildQueue.peek();
        if (next == null) {
            skullTicks = 0;
            phase = Phase.SKULLS;
            return;
        }
        if (ctx.worldView().traitsAt(next).solid()) {
            buildQueue.poll(); // blok už stojí
            return;
        }
        var snapshot = ctx.serverView().latest();
        if (snapshot == null || !ctx.inventory().equipItem(snapshot, Material.SOUL_SAND)) {
            finish(4800); // sand zmizel – bez oltáře není boj
            return;
        }
        buildQueue.poll();
        placeTask = new PlaceBlockTask(next);
    }

    /** Lebky na horní plochy – prostřední poslední, pak okamžitý ústup. */
    private void tickSkulls(BotContext ctx) {
        if (--skullTicks > 0) {
            return;
        }
        BlockPos support = skullQueue.poll();
        if (support == null) {
            // Poslední lebka položena – wither roste (11 s nezranitelnosti).
            retreatTicks = 0;
            fightActive = true;
            fightDeadlineMs = System.currentTimeMillis()
                    + ctx.config().nether().wither().maxFightMinutes() * 60_000L;
            phase = Phase.RETREAT;
            return;
        }
        var snapshot = ctx.serverView().latest();
        if (snapshot == null
                || !ctx.inventory().equipItem(snapshot, Material.WITHER_SKELETON_SKULL)) {
            skullTicks = 4;
            skullQueue.addFirst(support);
            return;
        }
        ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0),
                support.center().add(0, 0.5, 0));
        ctx.actions().useItemOn(support, Direction.UP);
        skullTicks = ctx.rng().rangeInt(8, 14);
    }

    /** Sprint od rostoucího bosse – okno nezranitelnosti je na rozestup. */
    private void tickRetreat(BotContext ctx) {
        double distance = ctx.position().horizontal()
                .distance(altarBase.center().horizontal());
        if (distance >= SUMMON_RETREAT || ++retreatTicks > 220) {
            ctx.navigator().stop();
            witherGoneTicks = 0;
            strengthDone = false;
            strengthTries = 0;
            phase = Phase.FIGHT;
            return;
        }
        ctx.navigator().navigateTo(ctx.position(),
                PathGoal.awayFrom(altarBase, SUMMON_RETREAT));
        if (!ctx.navigator().navigating() && !ctx.navigator().hasPath()) {
            Vec3 away = ctx.position().sub(altarBase.center()).horizontal().normalized();
            ctx.requestMove(MoveInput.of(away, true, false));
        }
    }

    /**
     * Doušek síly před bojem (vzor dračího souboje – regenerace). Láhev
     * zastrčená v batohu se nejdřív přitáhne do hotbaru (vzor cíle drink);
     * roste-li boss, okno nezranitelnosti na to čas dává.
     */
    private void drinkStrength(BotContext ctx) {
        var snapshot = ctx.serverView().latest();
        int slot = ItemVariants.findPotionSlot(snapshot, ItemVariants.STRENGTH);
        if (slot < 0 || ++strengthTries > 3) {
            strengthDone = true; // sílu nemá (nebo přesun nevyšel) – do boje i tak
            return;
        }
        if (slot >= 9) {
            int dump = InventoryHelper.chooseHotbarDumpSlot(snapshot);
            ctx.clicker().moveToHotbar(0, slot, dump >= 0 ? dump : 8);
            drinkTicks = 10; // přesun doběhne, další tick to zkusí znovu
            return;
        }
        ctx.actions().selectHotbar(slot);
        ctx.actions().useItem(ctx.humanizer().yaw(), ctx.humanizer().pitch());
        drinkTicks = 40;
        strengthDone = true;
    }

    /**
     * Boj: nad polovinou boss baru luk s odstupem (kite), pod polovinou je
     * wither obrněný (šípy se odráží) – meč přes bojový kontrolér.
     */
    private void tickFight(BotContext ctx, Bot bot) {
        if (System.currentTimeMillis() > fightDeadlineMs) {
            abandon(ctx);
            return;
        }
        if (!strengthDone) {
            drinkStrength(ctx); // síla z vlastního vaření – teď, nebo nikdy
            return;
        }
        Optional<TrackedEntity> wither = findWither(ctx);
        if (wither.isEmpty()) {
            // Zmizel z trackeru: mrtvý (boss bar zhasl), nebo jen daleko.
            if (ctx.clientState().bossBarHealth() < 0 && ++witherGoneTicks > 60) {
                fightActive = false;
                ctx.combat().disengage();
                collectTicks = 0;
                phase = Phase.COLLECT;
                return;
            }
            ctx.navigator().navigateTo(ctx.position(), PathGoal.near(altarBase, 6));
            return;
        }
        witherGoneTicks = 0;
        TrackedEntity boss = wither.get();
        float bossHealth = ctx.clientState().bossBarHealth();
        double distance = boss.position().distance(ctx.position());
        var snapshot = ctx.serverView().latest();

        boolean rangedPhase = bossHealth > 0.5f && bowShot != null
                && bowShot.canUse(snapshot);
        if (rangedPhase) {
            ctx.combat().disengage();
            if (distance < RANGED_NEAR) {
                // Moc blízko – kite: couvat a držet dostřel.
                bowShot.reset();
                Vec3 away = ctx.position().sub(boss.position()).horizontal().normalized();
                ctx.requestMove(MoveInput.of(away, true, false));
                return;
            }
            if (distance > RANGED_FAR) {
                ctx.navigator().navigateTo(ctx.position(),
                        PathGoal.near(boss.position().toBlockPos(), (int) RANGED_NEAR));
                return;
            }
            ctx.navigator().stop();
            ctx.requestMove(bowShot.tick(ctx.position(), boss, snapshot));
            return;
        }
        if (bowShot != null && bowShot.busy()) {
            bowShot.reset();
        }
        // Obrněná fáze (nebo bez luku): meč – wither v druhé fázi klesá
        // k zemi a jde po botovi sám.
        ctx.navigator().stop();
        ctx.combat().engage(boss);
        MoveInput move = ctx.combat().tick(ctx.position(), ctx.clientState().health(),
                ctx.onGround(), snapshot);
        if (move != null) {
            ctx.requestMove(move);
        }
    }

    /** Vyčerpaný rozpočet: utéct a nechat withera witherem. */
    private void abandon(BotContext ctx) {
        fightActive = false;
        ctx.combat().disengage();
        if (bowShot != null && bowShot.busy()) {
            bowShot.reset();
        }
        ctx.navigator().navigateTo(ctx.position(),
                PathGoal.awayFrom(altarBase != null ? altarBase
                        : ctx.position().toBlockPos(), 48));
        ctx.chat().say("na tohle nemam, mizim");
        finish(48_000); // ~40 minut – dnes už ne
    }

    /**
     * Dojít si pro nether star. Trofej se slaví <b>jen s hvězdou v batohu</b>
     * – zmizelý boss bar bez staru může být i wither zatoulaný mimo dosah
     * (nebo boj přerušený smrtí) a falešná oslava by lhala do paměti.
     */
    private void tickCollect(BotContext ctx, Bot bot) {
        Optional<TrackedEntity> drop = ctx.entities().nearest(
                altarBase != null ? altarBase.center() : ctx.position(), 24,
                TrackedEntity::isItem);
        if (drop.isPresent() && ++collectTicks < 300) {
            Vec3 item = drop.get().position();
            if (item.distanceSquared(ctx.position()) > 1.5 * 1.5) {
                ctx.navigator().navigateTo(ctx.position(), item.toBlockPos());
                return;
            }
        }
        ctx.navigator().stop();
        var snapshot = ctx.serverView().latest();
        if (InventoryHelper.countItem(snapshot, Material.NETHER_STAR) == 0) {
            finish(48_000); // bez hvězdy žádná sláva – boss možná jen odletěl
            return;
        }
        BlockPos feet = ctx.position().toBlockPos();
        bot.memory().remember(MemoryKind.TROPHY, ctx.worldView().worldName(),
                feet.x(), feet.y(), feet.z(), null, Map.of("type", "wither"), 1.0);
        ctx.stats().addKill();
        ctx.chat().sayFrom(PhraseCategory.WITHER_SLAIN, null);
        ctx.gainExperience(PersonalityEvolution.BotExperience.WITHER_SLAIN);
        finish(48_000);
    }

    private Optional<TrackedEntity> findWither(BotContext ctx) {
        return ctx.entities().nearest(ctx.position(), 80,
                e -> e.type() == EntityType.WITHER);
    }

    private void finish(int cooldown) {
        cooldownTicks = cooldown;
        fightActive = false;
        phase = Phase.DONE;
    }

    @Override
    public void stop(Bot bot) {
        BotContext ctx = ctx(bot);
        if (placeTask != null) {
            placeTask.cancel(ctx);
            placeTask = null;
        }
        if (bowShot != null && bowShot.busy()) {
            bowShot.reset();
        }
        ctx.combat().disengage();
        super.stop(bot);
    }

    @Override
    public boolean finished(Bot bot) {
        return phase == Phase.DONE;
    }

    @Override
    public boolean blocksRelocation() {
        return true; // uprostřed bosse se nerozhoduje o stěhování vesnice
    }

    @Override
    public String explain(Bot bot) {
        return switch (phase) {
            case SITE, GO_SITE, BUILD, SKULLS -> "stavím oltář witheru – tři lebky a soul sand";
            case RETREAT -> "wither roste – rychle pryč!";
            case FIGHT -> "bojuju s witherem!";
            case COLLECT -> "sbírám nether star – trofej trofejí";
            default -> null;
        };
    }
}
