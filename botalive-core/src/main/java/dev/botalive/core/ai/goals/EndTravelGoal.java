package dev.botalive.core.ai.goals;

import dev.botalive.api.bot.Bot;
import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.memory.MemoryRecord;
import dev.botalive.api.personality.Trait;
import dev.botalive.core.ai.BotContext;
import dev.botalive.core.ai.EndKnowledge;
import dev.botalive.core.ai.EndReadiness;
import dev.botalive.core.chat.PhraseCategory;
import dev.botalive.core.inventory.InventoryHelper;
import dev.botalive.core.util.BlockPos;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.WorldDimension;
import dev.botalive.core.world.WorldView;
import org.bukkit.Material;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import dev.botalive.core.pathfinding.PathGoal;

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
 * <p>Nezaplněný rám není konec výpravy: s očima Enderu v batohu
 * ({@code CraftPlanner} je mele z perel a blaze prachu) bot obejde prstenec
 * a proklikne oko do každého rámu – vyplněné rámy vklad ignorují, u prázdných
 * ho server spotřebuje. Jakmile se portál probudí, vstupuje se; když očí bylo
 * míň než prázdných slotů, poznamená si {@code eyes=missing} a bez zásoby očí
 * se k rámu už zbytečně nevrací.</p>
 *
 * <p>Rozestup výprav hlídá {@link EndKnowledge#recentEndVisit} – průchod
 * portálem se zapisuje do paměti, takže cooldown přežije i restart.</p>
 */
public final class EndTravelGoal extends AbstractGoal {

    private enum Phase { GO, FILL, ENTER, DONE }

    /** Jak dlouho se u portálu hledají portálové bloky, než to bot vzdá (ticky). */
    private static final int SEARCH_BUDGET_TICKS = 300;

    /** Sken je drahý (13×9×13 bloků) – hledá se jen jednou za tolik ticků. */
    private static final int SEARCH_INTERVAL_TICKS = 20;

    /** Rozpočet zaplňování rámu očima (docházka kolem prstence je pomalá). */
    private static final int FILL_BUDGET_TICKS = 1200;

    /** Kolik ticků se zkouší dojít k jednomu rámu, než se přeskočí. */
    private static final int FRAME_TICKS_LIMIT = 200;

    /** Pauza mezi dvěma vloženými oky (ruka i server potřebují chvilku). */
    private static final int FILL_CLICK_INTERVAL = 8;

    private Phase phase = Phase.GO;
    private BlockPos rememberedPortal;
    private PortalEntry entry;
    private int cooldownTicks;
    private int searchTicks;
    private int travelTicks;
    private ArrayDeque<BlockPos> frameTargets;
    private int fillTicks;
    private int frameTicks;
    private int clickPause;

    /** Vytvoří cíl. */
    public EndTravelGoal() {
        super("end-travel");
    }

    @Override
    public double utility(Bot bot) {
        BotContext ctx = ctx(bot);
        var cfg = ctx.config().end();
        if (!cfg.enabled() || ctx.clientState().dead()
                || ctx.dimension() != WorldDimension.OVERWORLD) {
            return 0;
        }
        // Past zavřeného Endu: bez dračího souboje (config/combat vypnuté)
        // výstupní portál nikdy nevznikne a End je jednosměrka. Vstup se
        // povolí, jen když má bot šanci si portál otevřít – nebo už ví,
        // že je otevřený (vlastní trofej z dřívějška).
        if ((!cfg.dragonFight() || !ctx.config().combat().enabled())
                && !EndKnowledge.dragonSlain(bot.memory())) {
            return 0;
        }
        // Na výpravu se nevyráží polomrtvý ani hladový.
        if (!expeditionFit(ctx)) {
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
        var portals = bot.memory().recall(MemoryKind.PORTAL);
        var nearest = EndKnowledge.nearestEndPortal(portals, world.worldName(),
                (int) pos.x(), (int) pos.z());
        if (nearest.isEmpty()) {
            return 0; // o žádném portálu neví
        }
        var snapshot = ctx.serverView().latest();
        // Známý nezaplněný rám: bez jediného oka by cesta skončila zase jen
        // pokrčením ramen před rámem – počká se, až CraftPlanner oči umele.
        if (EndKnowledge.frameAwaitsEyes(nearest.get())
                && InventoryHelper.countEstimate(snapshot,
                        m -> m == Material.ENDER_EYE) == 0) {
            return 0;
        }
        long cooldownMs = cfg.expeditionCooldownMinutes() * 60_000L;
        if (EndKnowledge.recentEndVisit(portals, System.currentTimeMillis(), cooldownMs)) {
            return 0; // z Endu se vrátil nedávno
        }
        EndReadiness readiness = EndReadiness.assess(snapshot);
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
        entry = null;
        searchTicks = 0;
        travelTicks = 0;
        frameTargets = null;
        fillTicks = 0;
        frameTicks = 0;
        clickPause = 0;
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
    public void resume(Bot bot) {
        // Návrat po přerušení reflexem (boj/hlad na cestě k portálu): stav
        // výpravy (phase, rememberedPortal, entry, frameTargets) přežil pause –
        // stop() ho nemaže, jen zastavil navigaci. tick() ji sám obnoví, takže
        // se NEvolá start() (ten by fázi resetoval na GO a zopakoval odchodovou
        // hlášku). Nic není potřeba, jen nespustit svěží start.
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
                    ctx.navigator().navigateTo(pos, PathGoal.near(rememberedPortal, 8));
                    return;
                }
                // U portálu: najít skutečné portálové bloky (paměť je ±pár
                // bloků). Sken jde po intervalech – bloky se samy neobjeví,
                // jen se čeká na dohřátí chunk cache.
                if (searchTicks % SEARCH_INTERVAL_TICKS == 0) {
                    world.prefetch(rememberedPortal, 1);
                    PortalScan scan = scanPortal(world, rememberedPortal,
                            pos.toBlockPos());
                    if (scan.portal() != null) {
                        rememberEyes(bot, world, null); // portál svítí
                        entry = new PortalEntry(world, scan.portal());
                        ctx.navigator().stop();
                        phase = Phase.ENTER;
                        return;
                    }
                    if (!scan.frames().isEmpty()) {
                        beginFillOrGiveUp(ctx, bot, world, scan, pos);
                        return;
                    }
                }
                if (++searchTicks > SEARCH_BUDGET_TICKS) {
                    // Portál tu není. Zapomenout jen s načtenou oblastí, ve
                    // které jdou číst materiály – studená cache ani degradovaný
                    // block-state mapper (packet režim s Via překladem, kdy
                    // materialAt vrací null) nesmí smazat skutečný portál.
                    if (world.isAvailable(rememberedPortal)
                            && world.materialAt(rememberedPortal) != null) {
                        BlockPos remembered = rememberedPortal;
                        bot.memory().forgetIf(MemoryKind.PORTAL, r ->
                                EndKnowledge.isEndPortal(r)
                                        && r.distanceSquared(remembered.x(), remembered.y(),
                                        remembered.z()) < 12 * 12);
                    }
                    giveUp(1200);
                }
            }
            case FILL -> {
                if (++fillTicks > FILL_BUDGET_TICKS) {
                    giveUp(1200);
                    return;
                }
                if (clickPause > 0) {
                    clickPause--;
                    return;
                }
                if (frameTargets.isEmpty()) {
                    // Všechny rámy prokliknuté (nebo došly oči) – rozhodne sken.
                    PortalScan scan = scanPortal(world, rememberedPortal,
                            pos.toBlockPos());
                    if (scan.portal() != null) {
                        rememberEyes(bot, world, null);
                        entry = new PortalEntry(world, scan.portal());
                        phase = Phase.ENTER;
                    } else {
                        // Očí bylo míň než prázdných slotů – rám dál čeká.
                        rememberEyes(bot, world, EndKnowledge.EYES_MISSING);
                        giveUp(2400);
                    }
                    return;
                }
                BlockPos frame = frameTargets.peek();
                if (++frameTicks > FRAME_TICKS_LIMIT) {
                    // Nedosažitelný rám (lávový příkop…) – zkusí se další.
                    frameTargets.poll();
                    frameTicks = 0;
                    return;
                }
                if (frame.center().distanceSquared(pos) > 3.2 * 3.2) {
                    ctx.navigator().navigateTo(pos, PathGoal.near(frame, 2));
                    return;
                }
                var snapshot = ctx.serverView().latest();
                if (snapshot == null
                        || !ctx.inventory().equipItem(snapshot, Material.ENDER_EYE)) {
                    // Oči došly v půlce – finální sken rozhodne, jak to dopadlo.
                    frameTargets.clear();
                    return;
                }
                ctx.navigator().stop();
                ctx.humanizer().lookAt(pos.add(0, 1.62, 0),
                        frame.center().add(0, 0.5, 0));
                // Vklad oka: u prázdného rámu ho server spotřebuje, vyplněný
                // rám klik ignoruje – stav oka v rámu se z paketů nepřečte,
                // proklikává se proto celý prstenec naslepo (jako hráč bez F3).
                ctx.actions().useItemOn(frame, Direction.UP);
                frameTargets.poll();
                frameTicks = 0;
                clickPause = FILL_CLICK_INTERVAL;
            }
            case ENTER -> {
                // Průchod pozná finished() podle změny dimenze.
                if (entry.tick(ctx, PortalEntry.DEFAULT_BUDGET_TICKS)
                        == PortalEntry.Result.GAVE_UP) {
                    giveUp(600);
                }
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
        return phase == Phase.DONE || ctx(bot).dimension() == WorldDimension.END;
    }

    /** U nezaplněného rámu: s očima začít vkládat, bez nich odejít s poznámkou. */
    private void beginFillOrGiveUp(BotContext ctx, Bot bot, WorldView world,
                                   PortalScan scan, Vec3 pos) {
        var snapshot = ctx.serverView().latest();
        if (InventoryHelper.countEstimate(snapshot, m -> m == Material.ENDER_EYE) == 0) {
            // Prázdný rám a prázdné ruce – poznamenat, ať se sem bez očí
            // příště vůbec nechodí (utility bránu drží frameAwaitsEyes).
            rememberEyes(bot, world, EndKnowledge.EYES_MISSING);
            giveUp(2400);
            return;
        }
        List<BlockPos> frames = new ArrayList<>(scan.frames());
        BlockPos feet = pos.toBlockPos();
        frames.sort(java.util.Comparator.comparingDouble(f ->
                f.center().distanceSquared(feet.center())));
        frameTargets = new ArrayDeque<>(frames);
        fillTicks = 0;
        frameTicks = 0;
        clickPause = 0;
        ctx.navigator().stop();
        phase = Phase.FILL;
        if (ctx.rng().chance(0.8)) {
            ctx.chat().sayFrom(PhraseCategory.END_EYES, null);
        }
    }

    /**
     * Přepíše poznámku o očích u okolních end-záznamů ({@code eyes=missing},
     * {@code null} = poznámku smazat). Oživení vzpomínky nahrazuje data celá,
     * proto se přenáší kompletní mapa záznamu.
     */
    private void rememberEyes(Bot bot, WorldView world, String value) {
        if (rememberedPortal == null) {
            return;
        }
        BlockPos remembered = rememberedPortal;
        for (MemoryRecord r : bot.memory().recall(MemoryKind.PORTAL)) {
            if (!EndKnowledge.isEndPortal(r) || !world.worldName().equals(r.world())
                    || r.distanceSquared(remembered.x(), remembered.y(),
                            remembered.z()) >= 12 * 12) {
                continue;
            }
            if (Objects.equals(r.data().get(EndKnowledge.DATA_EYES), value)) {
                continue; // beze změny – žádné zbytečné přepisy
            }
            Map<String, String> data = new HashMap<>(r.data());
            if (value == null) {
                data.remove(EndKnowledge.DATA_EYES);
            } else {
                data.put(EndKnowledge.DATA_EYES, value);
            }
            bot.memory().remember(MemoryKind.PORTAL, r.world(), r.x(), r.y(), r.z(),
                    null, data, r.importance());
        }
    }

    /** Výsledek skenu okolí portálu: aktivní blok (má přednost) a rámy. */
    private record PortalScan(BlockPos portal, List<BlockPos> frames) {
    }

    /**
     * Prohledá okolí zapamatované pozice: blok END_PORTAL (dá se do něj
     * vkročit) a rámy END_PORTAL_FRAME (kandidáti na vložení očí).
     */
    private static PortalScan scanPortal(WorldView world, BlockPos around, BlockPos feet) {
        BlockPos center = around.distanceSquared(feet) < 12 * 12 ? around : feet;
        BlockPos portal = null;
        List<BlockPos> frames = new ArrayList<>();
        for (int dx = -6; dx <= 6; dx++) {
            for (int dy = -4; dy <= 4; dy++) {
                for (int dz = -6; dz <= 6; dz++) {
                    BlockPos p = center.offset(dx, dy, dz);
                    Material material = world.materialAt(p);
                    if (material == Material.END_PORTAL) {
                        if (portal == null) {
                            portal = p;
                        }
                    } else if (material == Material.END_PORTAL_FRAME) {
                        frames.add(p);
                    }
                }
            }
        }
        return new PortalScan(portal, frames);
    }

    @Override
    public String explain(Bot bot) {
        return switch (phase) {
            case GO -> "mířím k portálu do Endu – jdu si to vyříkat s drakem";
            case FILL -> "vkládám oči Enderu do rámu portálu – velká chvíle";
            case ENTER -> "stojím před portálem do Endu... tak jo, jdu tam";
            case DONE -> null;
        };
    }
}
