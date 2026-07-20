package dev.botalive.core.build.plan;

import dev.botalive.core.ai.BotContext;
import dev.botalive.core.tasks.BotTask;
import dev.botalive.core.tasks.MineBlockTask;
import dev.botalive.core.tasks.PlaceBlockTask;
import dev.botalive.core.util.BlockPos;

import java.util.ArrayDeque;
import java.util.Deque;

/**
 * Sdílený vykonavatel stavby: odbaví {@link BuildSchedule} po krocích místo
 * čtyř opsaných smyček v cílech. Fáze:
 *
 * <ol>
 *   <li><b>TERRAFORM</b> – vytěžit překážky, zasypat díry v podlaze
 *       (zaměnitelný blok; jako {@code planTerraform}).</li>
 *   <li><b>GOTO_STAND</b> – stoupnout si přesně na stanoviště (u studny do
 *       šachty); už-na-místě je no-op, takže cíl, který bota přivedl,
 *       navigaci znovu nespouští.</li>
 *   <li><b>PLACE</b> – položit stavbu v pořadí s oporou; už pevná pozice se
 *       přeskočí (resume world-diffem), došlý materiál vrátí
 *       {@link State#BLOCKED_MATERIAL}.</li>
 *   <li><b>FURNISH</b> – osadit dveře/světlo/postel/truhlu; co chybí v batohu
 *       nebo už stojí, přeskočí (vybavení je bonus).</li>
 * </ol>
 *
 * <p>Pokládku bloků i výkopy počítá {@code PlaceBlockTask}/{@code MineBlockTask}
 * samy (statistika se tím počítá jednou, ne jako dřív dvakrát). Session drží
 * per-cíl stav v paměti; po přerušení/restartu ji cíl sestaví znovu z plánu
 * a world-diff přeskočí hotové – fyzická stavba je autorita.</p>
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
        UNREACHABLE
    }

    private enum Phase { TERRAFORM, GOTO_STAND, PLACE, FURNISH, DONE }

    /** Tolerance dokročení u tolerantní stavby (dům): stačí okolí stanoviště. */
    private static final int STAND_TOLERANCE_SQ = 2;

    private final BlockPos stand;
    private final boolean standExact;
    private final Deque<BlockPos> mine;
    private final Deque<BlockPos> fill;
    private final Deque<PlacementCell> placements;
    private final Deque<FurnishCell> furnish;

    private Phase phase = Phase.TERRAFORM;
    private BotTask current;
    private boolean navigating;
    private PaletteRole missing;

    /**
     * @param schedule rozvrh sestavený {@link BuildPlanner}
     */
    public BuildSession(BuildSchedule schedule) {
        this.stand = schedule.stand();
        this.standExact = schedule.standExact();
        this.mine = new ArrayDeque<>(schedule.mine());
        this.fill = new ArrayDeque<>(schedule.fill());
        this.placements = new ArrayDeque<>(schedule.placements());
        this.furnish = new ArrayDeque<>(schedule.furnishing());
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
            case GOTO_STAND -> tickGoto(ctx);
            case PLACE -> tickPlace(ctx);
            case FURNISH -> tickFurnish(ctx);
            case DONE -> State.DONE;
        };
    }

    /** @return počet zbývajících bloků stavby (pro {@code explain}) */
    public int remaining() {
        return placements.size() + (phase == Phase.PLACE && current != null ? 1 : 0);
    }

    /** @return role materiálu, který došel (platné jen po {@link State#BLOCKED_MATERIAL}) */
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
            // Zásyp podlahy zaměnitelným blokem; jako dnešní tickTasks se
            // výsledek equip neřeší (zásypů je pár, materiál je zajištěn gate).
            ctx.inventory().equipBuildingBlock(ctx.serverView().latest());
            current = new PlaceBlockTask(hole);
            return State.RUNNING;
        }
        phase = Phase.GOTO_STAND;
        return State.RUNNING;
    }

    private State tickGoto(BotContext ctx) {
        BlockPos feet = ctx.position().toBlockPos();
        boolean arrived = standExact
                ? feet.equals(stand)                          // studna/sýpka – přesně dovnitř
                : feet.distanceSquared(stand) <= STAND_TOLERANCE_SQ; // dům – stačí okolí
        if (arrived) {
            if (navigating) {
                ctx.navigator().stop();
                navigating = false;
            }
            phase = Phase.PLACE;
            return State.RUNNING;
        }
        ctx.navigator().navigateTo(ctx.position(), stand);
        navigating = true;
        if (!ctx.navigator().navigating() && !ctx.navigator().hasPath()) {
            return State.UNREACHABLE;
        }
        return State.RUNNING;
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
            phase = Phase.FURNISH;
            return State.RUNNING;
        }
        // Už pevná pozice → hotová (návrat ke stavbě, world-diff).
        if (ctx.worldView() != null && ctx.worldView().traitsAt(cell.pos()).solid()) {
            placements.poll();
            return State.RUNNING;
        }
        if (!ctx.inventory().equipBuildingBlock(ctx.serverView().latest())) {
            missing = cell.spec().role();
            return State.BLOCKED_MATERIAL;
        }
        placements.poll();
        current = new PlaceBlockTask(cell.pos());
        return State.RUNNING;
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
        if (!ctx.inventory().equipMatching(ctx.serverView().latest(),
                Blueprints.itemFor(step.kind()))) {
            return State.RUNNING;
        }
        current = new PlaceBlockTask(step.pos());
        return State.RUNNING;
    }
}
