package dev.botalive.core.ai.goals;

import dev.botalive.core.ai.BotContext;
import dev.botalive.core.build.Enclosure;
import dev.botalive.core.tasks.BotTask;
import dev.botalive.core.tasks.PlaceBlockTask;
import dev.botalive.core.util.BlockPos;

import org.bukkit.Material;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Vykonavatel ohradní bariéry ({@link Enclosure}) – sestra {@link DecorWorker}:
 * projde naplánované buňky obvodu, vezme materiál a položí plaňku/branku. Sdílí
 * ho stavba plotu (plaňky z dřeva) i hradby (běžné stavební bloky, {@code post}
 * = {@code null}); jedna implementace chůze, equipů a pauz.
 *
 * <p>Idempotentní jako plán sám: buňka, kde už bariéra stojí, se přeskočí
 * (kolize ve světě – world-diff jako {@code BuildSession}). Když dojde materiál
 * daného druhu, seance skončí – zbytek se dodělá příště (cíl mezitím dorobí).</p>
 *
 * <p><b>Branka</b> se klade z <b>vnitřní strany</b> ohrady: směr branky se
 * odvozuje z pohledu bota při pokládce, a když bot stojí uvnitř a klade ven,
 * yaw míří kolmo na hranu → branka se napojí na plot místo aby stála napříč
 * (viz {@code AbstractGoal.placeOwnStation} – server klik s nesedící rotací
 * zahodí, proto stanoviště, ne jen pohled).</p>
 */
final class BarrierWorker {

    /** Kolik ticků nejvýš zkoušet dojít na stanoviště, než se krok přeskočí. */
    private static final int NAV_BUDGET = 200;
    /** Tolerance dokročení k plaňce (stačí okolí – plot je nízký, dosáhne se). */
    private static final int POST_REACH_SQ = 9;
    /** Branka se klade přesně z vnitřního stanoviště (kvůli orientaci). */
    private static final int GATE_REACH_SQ = 2;

    private final Deque<Enclosure.Placement> steps = new ArrayDeque<>();
    private final BlockPos center;
    private final Material post;
    private final Material gate;
    private BotTask current;
    private int navTicks;

    /**
     * @param planned buňky bariéry (z {@link Enclosure#plan} + {@link Enclosure#column})
     * @param center  střed ohrady (pro vnitřní stanoviště branky)
     * @param post    materiál sloupku ({@code *_FENCE} u plotu); {@code null} =
     *                jakýkoli stavební blok (hradba – ty boti sbírají)
     * @param gate    materiál branky ({@code *_FENCE_GATE}); {@code null} = bez
     *                branek (hradba s otevřenými průchody, brány cíl vynechá)
     */
    BarrierWorker(List<Enclosure.Placement> planned, BlockPos center, Material post, Material gate) {
        this.steps.addAll(planned);
        this.center = center;
        this.post = post;
        this.gate = gate;
    }

    /** @return {@code true} když je co dělat */
    boolean hasWork() {
        return !steps.isEmpty() || current != null;
    }

    /**
     * Jeden tick práce.
     *
     * @param ctx kontext bota
     * @return {@code true} když je hotovo
     */
    boolean tick(BotContext ctx) {
        if (current != null) {
            if (current.tick(ctx)) {
                current = null;
            }
            return false;
        }
        Enclosure.Placement step = steps.peek();
        if (step == null) {
            return true;
        }
        // Idempotence: cíl už pevný (bariéra stojí) → přeskočit.
        if (ctx.worldView() != null && !ctx.worldView().traitsAt(step.pos()).noCollision()) {
            steps.poll();
            return false;
        }
        boolean isGate = step.kind() == Enclosure.Cell.GATE;
        BlockPos stand = isGate ? innerStand(step.pos()) : step.pos();
        int reachSq = isGate ? GATE_REACH_SQ : POST_REACH_SQ;
        if (ctx.position().toBlockPos().distanceSquared(stand) > reachSq) {
            ctx.navigator().navigateTo(ctx.position(), stand);
            if (!ctx.navigator().navigating() || ++navTicks > NAV_BUDGET) {
                steps.poll(); // nedostupný krok přeskočit
                navTicks = 0;
            }
            return false;
        }
        ctx.navigator().stop();
        navTicks = 0;
        var snapshot = ctx.serverView().latest();
        boolean equipped = isGate
                ? (gate != null && ctx.inventory().equipItem(snapshot, gate))
                : (post != null
                        ? ctx.inventory().equipItem(snapshot, post)
                        : ctx.inventory().equipBuildingBlock(snapshot)); // hradba: běžný blok
        if (!equipped) {
            steps.clear(); // došel materiál – zbytek příště (cíl dorobí, plán je idempotentní)
            return false;
        }
        steps.poll();
        current = new PlaceBlockTask(step.pos());
        return false;
    }

    /** Zruší rozdělanou práci (přerušení cíle). */
    void cancel(BotContext ctx) {
        if (current != null) {
            current.cancel(ctx);
            current = null;
        }
        steps.clear();
    }

    /** Vnitřní stanoviště branky: o blok od ní ke středu (odtud yaw míří ven). */
    private BlockPos innerStand(BlockPos gatePos) {
        return gatePos.offset(Integer.signum(center.x() - gatePos.x()), 0,
                Integer.signum(center.z() - gatePos.z()));
    }
}
