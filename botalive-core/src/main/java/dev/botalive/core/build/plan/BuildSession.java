package dev.botalive.core.build.plan;

import dev.botalive.core.ai.BotContext;
import dev.botalive.core.tasks.BotTask;
import dev.botalive.core.tasks.MineBlockTask;
import dev.botalive.core.tasks.PlaceBlockTask;
import dev.botalive.core.util.BlockPos;

import org.bukkit.Material;

import dev.botalive.core.world.WorldView;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * Sdílený vykonavatel stavby: odbaví {@link BuildSchedule} po krocích místo
 * opsaných smyček v cílech. Fáze:
 *
 * <ol>
 *   <li><b>TERRAFORM</b> – vytěžit překážky, zasypat díry v podlaze.</li>
 *   <li><b>UNIT</b> – pro každou {@link WorkUnit dávku}: dojít na stanoviště
 *       (u studny/sýpky/generovaného domu přesně, u legacy 4×4 stačí okolí)
 *       a položit její bloky. Velká stavba má víc stanovišť – bot přechází po
 *       vnitřní podlaze, aby na každý blok pohodlně dosáhl.</li>
 *   <li><b>FURNISH</b> – z vnitřního stanoviště osadit dveře/světlo/postel/
 *       truhlu; co chybí v batohu nebo už stojí, přeskočí (bonus).</li>
 * </ol>
 *
 * <p>Po odbavení všech dávek se stavba <b>ověří proti světu</b>: pokládka je
 * fire-and-forget ({@code PlaceBlockTask} hlásí „hotovo" i když blok nepoložil –
 * nebylo se o co opřít, server pokládku odmítl), takže bez kontroly by plán
 * ve vzduchu proběhl jako řada no-opů a skončil stavem {@link State#DONE} nad
 * prázdným staveništěm. Chybějící bloky dostanou ještě jeden průchod, pak je
 * to {@link State#INCOMPLETE} – torzo, ne hotová stavba.</p>
 *
 * <p>Pořadí bloků napříč jednotkami drží oporu; už pevná pozice se přeskočí
 * (resume world-diffem), došlý materiál vrátí {@link State#BLOCKED_MATERIAL}.
 * Pokládku i výkopy počítá {@code PlaceBlockTask}/{@code MineBlockTask} samy.
 * Session drží per-cíl stav v paměti; po přerušení ji cíl sestaví znovu
 * z plánu a world-diff přeskočí hotové – fyzická stavba je autorita.</p>
 */
public final class BuildSession {

    /** Výsledek ticku – řídí jím cíl (přepnutí fáze, cooldown, uvolnění claimu). */
    public enum State {
        /** Pracuje se dál. */
        RUNNING,
        /** Stavba i vybavení hotové. */
        DONE,
        /** Došel stavební materiál (viz {@link #missing()}). */
        BLOCKED_MATERIAL,
        /** Na stanoviště se nedá dojít. */
        UNREACHABLE,
        /**
         * Bloky se nepodařilo položit ani na druhý pokus – staveniště zůstalo
         * prázdné nebo torzo. Cíl to <b>nesmí</b> brát jako hotovo: fyzická
         * stavba je autorita a sídlo si nemá připsat stavbu, která nestojí.
         */
        INCOMPLETE
    }

    private enum Phase { TERRAFORM, UNIT_GOTO, UNIT_PLACE, CLEANUP, FURNISH_GOTO, FURNISH, DONE }

    /** Tolerance dokročení u tolerantní stavby (dům): stačí okolí stanoviště. */
    private static final int STAND_TOLERANCE_SQ = 2;
    /** Kolik ticků nejvýš zkoušet dojít na stanoviště, než to bot vzdá. */
    private static final int NAV_BUDGET = 300;
    /** Kolikrát projet chybějící bloky znovu, než se stavba prohlásí za torzo. */
    private static final int MAX_VERIFY_PASSES = 2;

    private final BlockPos furnishStand;
    private final boolean standExact;
    private final Palette palette;
    private final Deque<BlockPos> mine;
    private final Deque<BlockPos> fill;
    private final Deque<WorkUnit> units;
    private final Deque<PlacementCell> placements = new ArrayDeque<>();
    private final Deque<FurnishCell> furnish;
    /** Dočasné lešení k úklidu po pokládce (shora dolů, ať bot bezpečně slézá). */
    private final Deque<BlockPos> scaffold;
    /** Odbavené dávky – po vyprázdnění fronty se ověří proti světu. */
    private final List<WorkUnit> attempted = new ArrayList<>();

    private Phase phase = Phase.TERRAFORM;
    private BotTask current;
    private boolean navigating;
    private BlockPos target;
    private int navTicks;
    private int verifyPasses;
    private PaletteRole missing;

    /**
     * Legacy stavba: zaměnitelný stavební blok pro všechny bloky.
     *
     * @param schedule rozvrh sestavený {@link BuildPlanner}
     */
    public BuildSession(BuildSchedule schedule) {
        this(schedule, Palette.GENERIC);
    }

    /**
     * @param schedule rozvrh sestavený {@link BuildPlanner}
     * @param palette  materiály podle rolí (GENERIC = zaměnitelný blok)
     */
    public BuildSession(BuildSchedule schedule, Palette palette) {
        this.furnishStand = schedule.furnishStand();
        this.standExact = schedule.standExact();
        this.palette = palette;
        this.mine = new ArrayDeque<>(schedule.mine());
        this.fill = new ArrayDeque<>(schedule.fill());
        this.units = new ArrayDeque<>(schedule.units());
        this.furnish = new ArrayDeque<>(schedule.furnishing());
        // Lešení se odklízí shora dolů: bot slézá po vytěžených blocích a
        // neuvázne nad vzniklou dírou.
        List<BlockPos> topDown = new ArrayList<>(schedule.scaffold());
        topDown.sort((a, b) -> Integer.compare(b.y(), a.y()));
        this.scaffold = new ArrayDeque<>(topDown);
        this.target = furnishStand;
    }

    /**
     * Posune stavbu o jeden tick.
     *
     * @param ctx kontext bota
     * @return stav (RUNNING / DONE / BLOCKED_MATERIAL / UNREACHABLE)
     */
    public State tick(BotContext ctx) {
        return switch (phase) {
            case TERRAFORM -> tickTerraform(ctx);
            case UNIT_GOTO -> tickUnitGoto(ctx);
            case UNIT_PLACE -> tickPlace(ctx);
            case CLEANUP -> tickCleanup(ctx);
            case FURNISH_GOTO -> tickFurnishGoto(ctx);
            case FURNISH -> tickFurnish(ctx);
            case DONE -> State.DONE;
        };
    }

    /** @return stanoviště, kam session právě míří (pro simulaci/diagnostiku). */
    public BlockPos currentStand() {
        return target;
    }

    /** @return počet zbývajících bloků stavby (pro {@code explain}). */
    public int remaining() {
        int left = placements.size() + (current instanceof PlaceBlockTask ? 1 : 0);
        for (WorkUnit unit : units) {
            left += unit.placements().size();
        }
        return left;
    }

    /** @return role materiálu, který došel (platné jen po {@link State#BLOCKED_MATERIAL}). */
    public PaletteRole missing() {
        return missing;
    }

    /** Přeruší rozdělanou stavbu (cíl přepíná pryč). */
    public void cancel(BotContext ctx) {
        if (current != null) {
            current.cancel(ctx);
            current = null;
        }
    }

    // ------------------------------------------------------------------ fáze

    private State tickTerraform(BotContext ctx) {
        if (current != null) {
            if (current.tick(ctx)) {
                current = null;
            }
            return State.RUNNING;
        }
        BlockPos dig = mine.poll();
        if (dig != null) {
            current = new MineBlockTask(dig);
            return State.RUNNING;
        }
        BlockPos hole = fill.poll();
        if (hole != null) {
            // Zásyp podlahy zaměnitelným blokem (zásypů je pár, materiál je zajištěn gate).
            ctx.inventory().equipBuildingBlock(ctx.serverView().latest());
            current = new PlaceBlockTask(hole);
            return State.RUNNING;
        }
        phase = Phase.UNIT_GOTO;
        return State.RUNNING;
    }

    private State tickUnitGoto(BotContext ctx) {
        if (placements.isEmpty()) {
            WorkUnit unit = units.poll();
            if (unit == null) {
                return finishUnits(ctx);
            }
            placements.addAll(unit.placements());
            attempted.add(unit);
            target = unit.stand();
            navTicks = 0;
            // Nové stanoviště – příchod se ověří příští tick (po přesunu).
            return State.RUNNING;
        }
        if (arrived(ctx, target)) {
            phase = Phase.UNIT_PLACE;
            return State.RUNNING;
        }
        return navigate(ctx, target);
    }

    private State tickPlace(BotContext ctx) {
        if (current != null) {
            if (current.tick(ctx)) {
                current = null;
            }
            return State.RUNNING;
        }
        PlacementCell cell = placements.peek();
        if (cell == null) {
            phase = Phase.UNIT_GOTO; // další jednotka (nebo vybavení)
            return State.RUNNING;
        }
        // Už pevná pozice → hotová (návrat ke stavbě, world-diff).
        if (ctx.worldView() != null && ctx.worldView().traitsAt(cell.pos()).solid()) {
            placements.poll();
            return State.RUNNING;
        }
        if (!equipFor(ctx, cell.spec().role())) {
            missing = cell.spec().role();
            return State.BLOCKED_MATERIAL;
        }
        placements.poll();
        current = new PlaceBlockTask(cell.pos());
        return State.RUNNING;
    }

    /**
     * Fronta dávek je prázdná – než se přejde k vybavení, ověřit stavbu proti
     * světu. Ověření není přepych: {@code PlaceBlockTask} hlásí „hotovo" i když
     * blok vůbec nepoložil (nebylo se o co opřít, server pokládku odmítl,
     * bot mezitím odešel ze stanoviště), a session cely z fronty odebírá
     * dopředu. Bez kontroly by celý plán ve vzduchu proběhl jako řada
     * neškodných no-opů a skončil stavem DONE nad prázdným staveništěm.
     *
     * <p>Co chybí, se ještě jednou zařadí ze stejného stanoviště (typicky se
     * mezitím doplnila opora nebo bot dokročil). Co nestojí ani pak, je
     * {@link State#INCOMPLETE} – torzo, ne hotová stavba.</p>
     */
    private State finishUnits(BotContext ctx) {
        List<WorkUnit> missingUnits = missingUnits(ctx);
        if (!missingUnits.isEmpty()) {
            if (verifyPasses >= MAX_VERIFY_PASSES) {
                return State.INCOMPLETE;
            }
            verifyPasses++;
            attempted.clear();
            units.addAll(missingUnits);
            return State.RUNNING;
        }
        // Stavba stojí – odklidit lešení (dřív než vybavení: pilíř bývá ve
        // sloupci vnitřního stanoviště, odkud se osazuje).
        phase = Phase.CLEANUP;
        return State.RUNNING;
    }

    /**
     * Odklidí dočasné lešení (pilíře pod vyvýšenými stanovišti) – vytěží ho
     * shora dolů, takže stavitel po blocích slézá zpět na podlahu a zůstane
     * jen stavba. Co ve světě nestojí (nízká stavba bez lešení, nebo blok
     * mezitím zmizel), přeskočí. Prázdné lešení projde rovnou na vybavení.
     */
    private State tickCleanup(BotContext ctx) {
        if (current != null) {
            if (current.tick(ctx)) {
                current = null;
            }
            return State.RUNNING;
        }
        BlockPos block = scaffold.poll();
        if (block == null) {
            target = furnishStand;
            phase = Phase.FURNISH_GOTO;
            return State.RUNNING;
        }
        if (ctx.worldView() != null && !ctx.worldView().traitsAt(block).solid()) {
            return State.RUNNING; // není co těžit (nepostavené lešení / už pryč)
        }
        current = new MineBlockTask(block);
        return State.RUNNING;
    }

    /** Dávky s bloky, které po odbavení ve světě nestojí (po stanovištích). */
    private List<WorkUnit> missingUnits(BotContext ctx) {
        WorldView world = ctx.worldView();
        if (world == null) {
            return List.of(); // bez pohledu na svět se ověřit nedá
        }
        List<WorkUnit> result = new ArrayList<>();
        for (WorkUnit unit : attempted) {
            List<PlacementCell> missingCells = new ArrayList<>();
            for (PlacementCell cell : unit.placements()) {
                if (!world.traitsAt(cell.pos()).solid()) {
                    missingCells.add(cell);
                }
            }
            if (!missingCells.isEmpty()) {
                result.add(new WorkUnit(unit.stand(), missingCells));
            }
        }
        return result;
    }

    private State tickFurnishGoto(BotContext ctx) {
        if (furnish.isEmpty() || arrived(ctx, furnishStand)) {
            phase = Phase.FURNISH;
            return State.RUNNING;
        }
        return navigate(ctx, furnishStand);
    }

    private State tickFurnish(BotContext ctx) {
        if (current != null) {
            if (current.tick(ctx)) {
                current = null;
            }
            return State.RUNNING;
        }
        FurnishCell step = furnish.poll();
        if (step == null) {
            phase = Phase.DONE;
            return State.DONE;
        }
        // Už osazeno (resume) nebo item chybí → přeskočit; vybavení je bonus.
        if (ctx.worldView() != null && ctx.worldView().traitsAt(step.pos()).solid()) {
            return State.RUNNING;
        }
        java.util.function.Predicate<org.bukkit.Material> want =
                step.kind() == FurnishKind.STATION
                        ? m -> m == step.material()
                        : Blueprints.itemFor(step.kind());
        if (!ctx.inventory().equipMatching(ctx.serverView().latest(), want)) {
            return State.RUNNING;
        }
        current = new PlaceBlockTask(step.pos());
        return State.RUNNING;
    }

    // ---------------------------------------------------------------- pomocné

    /** Dorazil bot na stanoviště? (generované/studna přesně, legacy dům okolí). */
    private boolean arrived(BotContext ctx, BlockPos t) {
        BlockPos feet = ctx.position().toBlockPos();
        boolean here = standExact
                ? feet.equals(t)
                : feet.distanceSquared(t) <= STAND_TOLERANCE_SQ;
        if (here && navigating) {
            ctx.navigator().stop();
            navigating = false;
        }
        if (here) {
            navTicks = 0;
        }
        return here;
    }

    private State navigate(BotContext ctx, BlockPos t) {
        ctx.navigator().navigateTo(ctx.position(), t);
        navigating = true;
        // Nedosažitelné (backoff) nebo příliš dlouho bez příchodu → vzdát se;
        // cíl to ošetří cooldownem a případně stavbu dokončí jindy (resume).
        if ((!ctx.navigator().navigating() && !ctx.navigator().hasPath())
                || ++navTicks > NAV_BUDGET) {
            return State.UNREACHABLE;
        }
        return State.RUNNING;
    }

    /**
     * Vezme do ruky materiál role: zamýšlený z palety, a když došel, jakýkoli
     * zaměnitelný stavební blok (náhrada – stavba se nezasekne). U
     * {@link Palette#GENERIC} rovnou zaměnitelný blok jako dnes.
     *
     * @return {@code false} když nezbývá vůbec žádný stavební blok
     */
    private boolean equipFor(BotContext ctx, PaletteRole role) {
        var snapshot = ctx.serverView().latest();
        Material intended = palette.intended(role).orElse(null);
        if (intended != null && ctx.inventory().equipItem(snapshot, intended)) {
            return true;
        }
        return ctx.inventory().equipBuildingBlock(snapshot);
    }
}
