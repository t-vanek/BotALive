package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.chat.PhraseCategory;
import dev.botalive.core.combat.RangedAttack;
import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.inventory.ItemVariants;
import dev.botalive.core.personality.PersonalityEvolution;
import dev.botalive.core.physics.EdgeGuard;
import dev.botalive.core.physics.MoveInput;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.WorldDimension;

import java.util.HashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Souboj s ender drakem – vrchol výpravy do Endu.
 *
 * <p>Postup jako u hráčů: dostat se z obsidiánové plošiny na hlavní ostrov
 * (mosty přes void staví obstacle pipeline), lukem sestřelit dračí krystaly
 * (léčí draka; na pilíře se neleze – střílí se zezdola), a pak drak samotný:
 * boj vede standardní {@link dev.botalive.core.combat.CombatController}
 * (luk na létajícího, meč na usazeného), úhyby před dračím dechem a ochrana
 * hran {@link EdgeGuard} drží bota na ostrově. Vítězství se pozná podle
 * zmizení draka poblíž středu – bot si zapíše trofej ({@code TROPHY
 * type=dragon}), oslaví to a odvaha mu stoupne.</p>
 */
public final class DragonFightGoal extends AbstractGoal {

    /** Vodorovná vzdálenost od (0,0), za kterou se bot považuje za „mimo ostrov". */
    private static final double ISLAND_RADIUS = 85;

    /** Dostřel na krystaly (šikmo vzhůru na pilíř). */
    private static final double CRYSTAL_RANGE = 30;

    /** Bezpečný odstup od paty pilíře při střelbě (výbuch krystalu). */
    private static final double CRYSTAL_MIN_DISTANCE = 8;

    /** Po kolika ticích bez draka u středu je vyhráno (smrt = odregistrace entity). */
    private static final int DRAGON_GONE_TICKS = 100;

    private RangedAttack crystalShot;
    private boolean fightStarted;
    private int dragonGoneTicks;
    private int tauntCooldown;
    /** Rozpočet zátahu (end.max-fight-minutes) – po vyčerpání pauza. */
    private long fightDeadlineMs;
    private int cooldownTicks;
    /** Krystaly, do kterých se marně střílelo (klece z mříží) – přeskakují se. */
    private final Set<Integer> crystalBlacklist = new HashSet<>();
    private int crystalTargetId = -1;
    private int crystalEngageTicks;
    /** Doušek regenerace/healu před bojem (ticky pití). */
    private int drinkTicks;
    /** Stabilní navigační cíl ke středu – přepočet každý tick by resetoval
     *  rozjeté přemosťování voidu (y se při stavbě mostu mění). */
    private BlockPos centerTarget;
    /** Přiblížení ke krystalu (per krystal, stejný důvod). */
    private BlockPos crystalApproach;
    private int crystalApproachId = -1;

    /** Vytvoří cíl. */
    public DragonFightGoal() {
        super("dragon-fight");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        var cfg = ctx.config().end();
        if (!cfg.enabled() || !cfg.dragonFight() || !ctx.config().combat().enabled()
                || ctx.clientState().dead() || ctx.dimension() != WorldDimension.END) {
            return 0;
        }
        if (cooldownTicks > 0) {
            // Pauza po vyčerpaném zátahu – prostor pro jídlo, kořist a návrat.
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        boolean dragonVisible = findDragon(ctx).isPresent();
        if (!dragonVisible && !fightStarted) {
            return 0;
        }
        double courage = bot.personality().trait(Trait.COURAGE);
        return 45 + courage * 10;
    }

    @Override
    public void start(Bot bot) {
        BotContext ctx = ctx(bot);
        crystalShot = new RangedAttack(ctx.actions(), ctx.humanizer(), ctx.rng(),
                ctx.inventory());
        dragonGoneTicks = 0;
        tauntCooldown = 0;
        centerTarget = null;
        crystalApproach = null;
        crystalApproachId = -1;
        // Instance cíle žije přes výpravy i smrti – bez resetu by starý boj
        // „strašil" a zmizení draka z trackeru by vyrobilo falešné vítězství.
        fightStarted = false;
        fightDeadlineMs = System.currentTimeMillis()
                + ctx.config().end().maxFightMinutes() * 60_000L;
        crystalBlacklist.clear();
        crystalTargetId = -1;
        crystalEngageTicks = 0;
        // Doušek před bojem: regenerace/heal z hotbaru (vzor nether výpravy,
        // která pije odolnost ohni před sestupem) – reaktivní pití v nouzi
        // dál řeší cíl „drink".
        drinkTicks = 0;
        var snapshot = ctx.serverView().latest();
        int slot = ItemVariants.findPotionSlot(snapshot, ItemVariants.REGENERATION);
        if (slot < 0 || slot >= 9) {
            slot = ItemVariants.findPotionSlot(snapshot, ItemVariants.HEALING);
        }
        if (slot >= 0 && slot < 9) {
            ctx.navigator().stop();
            ctx.actions().selectHotbar(slot);
            ctx.actions().useItem(ctx.humanizer().yaw(), ctx.humanizer().pitch());
            drinkTicks = 40;
        }
    }

    /** Navigace ke středu ostrova se stabilním cílem (mosty přes void). */
    private void navigateCenter(BotContext ctx, Vec3 pos) {
        if (pos.horizontal().distance(Vec3.ZERO) <= 12) {
            centerTarget = null;
            return;
        }
        if (centerTarget == null) {
            centerTarget = new BlockPos(0, (int) pos.y(), 0);
        }
        ctx.navigator().navigateTo(pos, centerTarget);
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        Vec3 pos = ctx.position();
        if (drinkTicks > 0) {
            drinkTicks--; // dopít doušek před bojem
            return;
        }
        if (tauntCooldown > 0) {
            tauntCooldown--;
        }
        // Vyčerpaný rozpočet zátahu: pauza místo boje do smrti – EndReturn,
        // jídlo a kořist dostanou prostor, pak se zátah zopakuje.
        if (fightStarted && System.currentTimeMillis() > fightDeadlineMs) {
            fightStarted = false;
            ctx.combat().disengage();
            if (crystalShot != null && crystalShot.busy()) {
                crystalShot.reset();
            }
            cooldownTicks = 2400;
            return;
        }

        Optional<TrackedEntity> dragon = findDragon(ctx);
        if (dragon.isPresent()) {
            fightStarted = true;
            dragonGoneTicks = 0;
        } else if (fightStarted
                && pos.horizontal().distance(Vec3.ZERO) < 80
                && ++dragonGoneTicks > DRAGON_GONE_TICKS) {
            // Drak zmizel z trackeru: mrtvý, nebo jen krouží mimo dosah
            // sledování entit. Rozhoduje výstupní portál – ten se objeví
            // pouze po jeho smrti.
            if (PortalEntry.findExitPortal(ctx.worldView(),
                    new BlockPos(0, (int) pos.y(), 0)) != null) {
                celebrate(ctx, bot);
                return;
            }
            dragonGoneTicks = 0; // portál nikde → drak žije, jen je daleko
        }

        // Z obsidiánové plošiny (a okrajů) na hlavní ostrov – mosty přes void
        // staví obstacle pipeline sama, tady stačí chtít ke středu.
        if (pos.horizontal().distance(Vec3.ZERO) > ISLAND_RADIUS) {
            navigateCenter(ctx, pos);
            return;
        }

        // Dračí dech: z oblaku se uteče hned, přes něj nevede žádný útok.
        Optional<TrackedEntity> cloud = ctx.entities()
                .nearest(pos, 4.5, TrackedEntity::isEffectCloud);
        if (cloud.isPresent()) {
            Vec3 away = pos.sub(cloud.get().position()).horizontal().normalized();
            ctx.combat().disengage();
            ctx.requestMove(EdgeGuard.apply(ctx.worldView(), pos,
                    MoveInput.of(away, true, false)));
            return;
        }

        // Krystaly léčí draka – dokud stojí a je čím střílet, mají přednost.
        Optional<TrackedEntity> crystal = findCrystal(ctx);
        if (crystal.isPresent() && crystalShot != null
                && crystalShot.canUse(ctx.serverView().latest())) {
            tickCrystal(ctx, crystal.get());
            return;
        }
        if (crystalShot != null && crystalShot.busy()) {
            crystalShot.reset();
        }

        if (dragon.isPresent()) {
            tickDragon(ctx, dragon.get());
            return;
        }

        // Drak mimo dohled: držet se u středu ostrova (fontána, usazování).
        ctx.combat().disengage();
        navigateCenter(ctx, pos);
    }

    /** Střelba na krystal: bezpečný odstup od pilíře a šípy šikmo vzhůru. */
    private void tickCrystal(BotContext ctx, TrackedEntity crystal) {
        Vec3 pos = ctx.position();
        // Marná palba (krystal v kleci z mříží, špatný úhel): po ~15 s bez
        // zásahu krystal na blacklist – šípy jsou v Endu drahé.
        if (crystalTargetId != crystal.entityId()) {
            crystalTargetId = crystal.entityId();
            crystalEngageTicks = 0;
        }
        if (++crystalEngageTicks > 300) {
            crystalBlacklist.add(crystal.entityId());
            crystalTargetId = -1;
            if (crystalShot != null && crystalShot.busy()) {
                crystalShot.reset();
            }
            return;
        }
        Vec3 base = new Vec3(crystal.position().x(), pos.y(), crystal.position().z());
        double baseDistance = pos.horizontal().distance(base.horizontal());
        if (baseDistance > CRYSTAL_RANGE) {
            if (crystalApproachId != crystal.entityId()) {
                crystalApproachId = crystal.entityId();
                crystalApproach = base.toBlockPos();
            }
            ctx.navigator().navigateTo(pos, crystalApproach);
            return;
        }
        if (baseDistance < CRYSTAL_MIN_DISTANCE) {
            // Moc blízko pilíře – výbuch krystalu shazuje kusy obsidiánu.
            Vec3 away = pos.sub(base).horizontal().normalized();
            ctx.requestMove(EdgeGuard.apply(ctx.worldView(), pos,
                    MoveInput.of(away, false, false)));
            return;
        }
        ctx.navigator().stop();
        ctx.requestMove(EdgeGuard.apply(ctx.worldView(), pos,
                crystalShot.tick(pos, crystal, ctx.serverView().latest())));
    }

    /** Souboj s drakem vede bojový kontrolér; tady jen cíl a ochrana hran. */
    private void tickDragon(BotContext ctx, TrackedEntity dragon) {
        double distance = dragon.position().distance(ctx.position());
        if (distance > 40) {
            // Drak krouží daleko – čekat u fontány, tam se usazuje.
            ctx.combat().disengage();
            navigateCenter(ctx, ctx.position());
            return;
        }
        ctx.navigator().stop();
        ctx.combat().engage(dragon);
        if (tauntCooldown == 0 && ctx.rng().chance(0.05)) {
            ctx.chat().sayFrom(PhraseCategory.COMBAT_TAUNTS, null);
            tauntCooldown = 600;
        }
        ctx.requestMove(EdgeGuard.apply(ctx.worldView(), ctx.position(),
                ctx.combat().tick(ctx.position(), ctx.clientState().health(),
                        ctx.onGround(), ctx.serverView().latest())));
    }

    /** Vítězství: trofej, oslava, zkušenost – a předání štafety návratu. */
    private void celebrate(BotContext ctx, Bot bot) {
        fightStarted = false;
        ctx.combat().disengage();
        Vec3 pos = ctx.position();
        bot.memory().remember(MemoryKind.TROPHY, ctx.worldView().worldName(),
                (int) pos.x(), (int) pos.y(), (int) pos.z(), null,
                Map.of("type", "dragon"), 1.0);
        ctx.stats().addKill();
        ctx.chat().sayFrom(PhraseCategory.DRAGON_SLAIN, null);
        ctx.gainExperience(PersonalityEvolution.BotExperience.DRAGON_SLAIN);
    }

    @Override
    public void stop(Bot bot) {
        if (crystalShot != null && crystalShot.busy()) {
            crystalShot.reset();
        }
        ctx(bot).combat().disengage();
        super.stop(bot);
    }

    @Override
    public boolean finished(Bot bot) {
        // Hotovo, když po započatém boji drak zmizel a oslava proběhla
        // (fightStarted se shodil), nebo když bot z Endu odešel.
        BotContext ctx = ctx(bot);
        if (ctx.dimension() != WorldDimension.END) {
            return true;
        }
        return !fightStarted && findDragon(ctx).isEmpty()
                && dev.botalive.core.ai.EndKnowledge.dragonSlain(bot.memory());
    }

    private static Optional<TrackedEntity> findDragon(BotContext ctx) {
        return ctx.entities().nearest(ctx.position(), 200, TrackedEntity::isEnderDragon);
    }

    /** Nejbližší krystal v dostřelu (mimo blacklist marné palby). */
    private Optional<TrackedEntity> findCrystal(BotContext ctx) {
        Vec3 pos = ctx.position();
        return ctx.entities().nearest(pos, CRYSTAL_RANGE + 16,
                e -> e.isEndCrystal() && !crystalBlacklist.contains(e.entityId()));
    }

    @Override
    public String explain(Bot bot) {
        BotContext ctx = ctx(bot);
        if (findCrystal(ctx).isPresent() && crystalShot != null) {
            return "sestřeluju dračí krystaly, ať se drak neléčí";
        }
        return "bojuju s ender drakem!";
    }
}
