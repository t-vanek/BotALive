package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.ai.EndKnowledge;
import dev.botalive.core.ai.EndReadiness;
import dev.botalive.core.chat.PhraseCategory;
import dev.botalive.core.physics.MoveInput;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.Dimension;
import dev.botalive.core.world.WorldView;
import org.bukkit.Material;

/**
 * Výprava do Endu – cesta ke známému portálu a skok do neznáma.
 *
 * <p>Aktivuje se jen u odvážných, dobře vybavených botů (železný meč, brnění,
 * jídlo, bloky na mosty; luk se počítá k dobru), kteří znají portál do Endu
 * ({@link MemoryKind#PORTAL} – vlastní průchod, objev při toulkách, drby,
 * nebo {@code /botalive end portal}). Bot dojde k portálu, najde portálové
 * bloky, postaví se na rám a vkročí dovnitř – zbytek obstará server
 * (respawn paket) a {@code BotImpl.switchWorld}. V Endu pak přebírají práci
 * cíle {@code dragon-fight}, {@code end-harvest} a {@code end-return}.</p>
 *
 * <p>Rozestup výprav hlídá {@link EndKnowledge#recentEndVisit} – průchod
 * portálem se zapisuje do paměti, takže cooldown přežije i restart.</p>
 */
public final class EndTravelGoal extends AbstractGoal {

    private enum Phase { GO, ENTER, DONE }

    /** Jak dlouho se u portálu hledají portálové bloky, než to bot vzdá (ticky). */
    private static final int SEARCH_BUDGET_TICKS = 300;

    /** Jak dlouho smí trvat samotné vkročení do portálu (ticky). */
    private static final int ENTER_BUDGET_TICKS = 200;

    private Phase phase = Phase.GO;
    private BlockPos rememberedPortal;
    private BlockPos portalBlock;
    private BlockPos standPoint;
    private int cooldownTicks;
    private int searchTicks;
    private int enterTicks;
    private int travelTicks;

    /** Vytvoří cíl. */
    public EndTravelGoal() {
        super("end-travel");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        var cfg = ctx.config().end();
        if (!cfg.enabled() || ctx.clientState().dead()
                || ctx.dimension() != Dimension.OVERWORLD) {
            return 0;
        }
        if (cooldownTicks > 0) {
            cooldownTicks -= ctx.config().ai().decisionIntervalTicks();
            return 0;
        }
        double courage = bot.personality().trait(Trait.COURAGE);
        if (courage < cfg.minCourage()) {
            return 0; // zbabělci do Endu nelezou
        }
        WorldView world = ctx.worldView();
        if (world == null) {
            return 0;
        }
        Vec3 pos = ctx.position();
        var portal = EndKnowledge.nearestEndPortal(bot.memory(), world.worldName(),
                (int) pos.x(), (int) pos.z());
        if (portal.isEmpty()) {
            return 0; // o žádném portálu neví
        }
        long cooldownMs = cfg.expeditionCooldownMinutes() * 60_000L;
        if (EndKnowledge.recentEndVisit(bot.memory().recall(MemoryKind.PORTAL),
                System.currentTimeMillis(), cooldownMs)) {
            return 0; // z Endu se vrátil nedávno
        }
        EndReadiness readiness = EndReadiness.assess(ctx.serverView().latest());
        if (!readiness.expeditionReady()) {
            return 0; // bez výbavy je to sebevražda
        }
        double curiosity = bot.personality().trait(Trait.CURIOSITY);
        return 8 + courage * 10 + curiosity * 4 + (readiness.wellArmed() ? 5 : 0);
    }

    @Override
    public void start(Bot bot) {
        BotContext ctx = ctx(bot);
        phase = Phase.GO;
        portalBlock = null;
        standPoint = null;
        searchTicks = 0;
        enterTicks = 0;
        travelTicks = 0;
        Vec3 pos = ctx.position();
        rememberedPortal = EndKnowledge.nearestEndPortal(bot.memory(),
                        ctx.worldView().worldName(), (int) pos.x(), (int) pos.z())
                .map(r -> new BlockPos(r.x(), r.y(), r.z()))
                .orElse(null);
        if (rememberedPortal == null) {
            phase = Phase.DONE;
            return;
        }
        if (ctx.rng().chance(0.8)) {
            ctx.chat().sayFrom(PhraseCategory.END_DEPART, null);
        }
    }

    @Override
    public void tick(Bot bot) {
        BotContext ctx = ctx(bot);
        if (phase == Phase.DONE || rememberedPortal == null) {
            return;
        }
        WorldView world = ctx.worldView();
        if (world == null) {
            return;
        }
        Vec3 pos = ctx.position();

        switch (phase) {
            case GO -> {
                double distSq = rememberedPortal.center().distanceSquared(pos);
                if (distSq > 10 * 10) {
                    // Cesta může být dlouhá (stronghold bývá daleko) – budget
                    // je jediný spolehlivý konec, navigateTo cíl vždy obnoví.
                    if (++travelTicks > 6000) {
                        giveUp(1200);
                        return;
                    }
                    ctx.navigator().navigateTo(pos, rememberedPortal);
                    return;
                }
                // U portálu: najít skutečné portálové bloky (paměť je ±pár bloků).
                portalBlock = findPortalBlock(world, rememberedPortal, pos.toBlockPos());
                if (portalBlock != null) {
                    standPoint = findStandPoint(world, portalBlock);
                    ctx.navigator().stop();
                    phase = Phase.ENTER;
                    return;
                }
                if (++searchTicks > SEARCH_BUDGET_TICKS) {
                    // Portál tu (už) není – vzpomínku smazat, ať se nechodí dokola.
                    BlockPos remembered = rememberedPortal;
                    bot.memory().forgetIf(MemoryKind.PORTAL, r ->
                            EndKnowledge.isEndPortal(r)
                                    && r.distanceSquared(remembered.x(), remembered.y(),
                                    remembered.z()) < 12 * 12);
                    giveUp(1200);
                }
            }
            case ENTER -> {
                if (++enterTicks > ENTER_BUDGET_TICKS) {
                    giveUp(600);
                    return;
                }
                Vec3 target = portalBlock.center();
                // Nejdřív na nástupní bod (rám portálu), pak krok do portálu.
                if (standPoint != null
                        && standPoint.center().sub(pos).horizontalLength() > 1.1
                        && target.sub(pos).horizontalLength() > 1.6) {
                    ctx.navigator().navigateTo(pos, standPoint);
                    if (!ctx.navigator().navigating() && !ctx.navigator().hasPath()) {
                        standPoint = null; // nástupní bod nedostupný – jít napřímo
                    }
                    return;
                }
                ctx.navigator().stop();
                Vec3 step = target.sub(pos).horizontal();
                ctx.humanizer().lookAt(pos.add(0, 1.62, 0), target);
                if (step.horizontalLength() > 1.0E-3) {
                    ctx.requestMove(MoveInput.walk(step));
                }
                // Průchod pozná finished() podle změny dimenze.
            }
            case DONE -> {
                // nic
            }
        }
    }

    private void giveUp(int cooldown) {
        cooldownTicks = cooldown;
        phase = Phase.DONE;
    }

    @Override
    public boolean finished(Bot bot) {
        return phase == Phase.DONE || ctx(bot).dimension() == Dimension.THE_END;
    }

    /**
     * Najde blok END_PORTAL (příp. rám) poblíž zapamatované pozice.
     * Portál má přednost – do rámu se vkročit nedá.
     */
    private static BlockPos findPortalBlock(WorldView world, BlockPos around, BlockPos feet) {
        BlockPos center = around.distanceSquared(feet) < 12 * 12 ? around : feet;
        BlockPos frame = null;
        for (int dx = -6; dx <= 6; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -6; dz <= 6; dz++) {
                    BlockPos p = center.offset(dx, dy, dz);
                    Material material = world.materialAt(p);
                    if (material == Material.END_PORTAL) {
                        return p;
                    }
                    if (material == Material.END_PORTAL_FRAME && frame == null) {
                        frame = p;
                    }
                }
            }
        }
        // Jen rám bez portálu (chybí oči) – vkročit není kam.
        return null;
    }

    /**
     * Nástupní bod u portálu: sousední buňka, na které se dá stát – typicky
     * rám portálu (částečná podlaha 13/16), u portálů v podlaze obyčejný
     * okraj. Z ní se do portálu vchází jedním krokem. Sdílené s návratem
     * ({@link EndReturnGoal}).
     */
    static BlockPos findStandPoint(WorldView world, BlockPos portal) {
        int[][] dirs = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}, {1, 1}, {1, -1}, {-1, 1}, {-1, -1}};
        for (int[] d : dirs) {
            BlockPos cell = portal.offset(d[0], 0, d[1]);
            var traits = world.traitsAt(cell);
            // Rám portálu: buňka s částečnou podlahou, nad ní volno.
            if (traits.floorHeight() > 0 && traits.floorHeight() <= 1.01
                    && world.traitsAt(cell.up()).passable()) {
                return cell;
            }
            // Portál zapuštěný v podlaze: volná buňka s pevným podkladem.
            if (traits.passable() && world.traitsAt(cell.down()).solid()) {
                return cell;
            }
        }
        return null;
    }

    @Override
    public String explain(Bot bot) {
        return switch (phase) {
            case GO -> "mířím k portálu do Endu – jdu si to vyříkat s drakem";
            case ENTER -> "stojím před portálem do Endu... tak jo, jdu tam";
            case DONE -> null;
        };
    }
}
