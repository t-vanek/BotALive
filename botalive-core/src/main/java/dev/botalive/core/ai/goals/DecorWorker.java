package dev.botalive.core.ai.goals;

import dev.botalive.core.ai.BotContext;
import dev.botalive.core.build.VillageDecor;
import dev.botalive.core.tasks.BotTask;
import dev.botalive.core.tasks.PlaceBlockTask;
import org.geysermc.mcprotocollib.protocol.data.game.entity.object.Direction;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Vykonavatel kroků zvelebení ({@link VillageDecor}): dojde ke kroku,
 * udusá cestičku lopatou (use-item na trávu, jako hráč), nebo zapíchne
 * pochodeň. Sdílí ho prvotní stavba ({@code BuildHouseGoal}) i údržba
 * ({@code MaintainHomeGoal}) – jedna implementace chůze, equipů a pauz.
 */
final class DecorWorker {

    private final Deque<VillageDecor.Step> steps = new ArrayDeque<>();
    private BotTask current;
    private int waitTicks;

    DecorWorker(List<VillageDecor.Step> planned) {
        steps.addAll(planned);
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
        if (waitTicks > 0) {
            waitTicks--;
            return false;
        }
        if (current != null) {
            if (current.tick(ctx)) {
                current = null;
            }
            return false;
        }
        VillageDecor.Step step = steps.peek();
        if (step == null) {
            return true;
        }
        // Kroky jsou podél linie – dojít na dosah ruky.
        double distSq = ctx.position().toBlockPos().distanceSquared(step.target());
        if (distSq > 12) {
            ctx.navigator().navigateTo(ctx.position(), step.target());
            if (!ctx.navigator().navigating()) {
                steps.poll(); // nedostupný krok přeskočit
            }
            return false;
        }
        ctx.navigator().stop();
        steps.poll();
        var snapshot = ctx.serverView().latest();
        if (step.path()) {
            if (!ctx.inventory().equipMatching(snapshot,
                    m -> m.name().endsWith("_SHOVEL"))) {
                steps.removeIf(VillageDecor.Step::path); // bez lopaty už jen pochodně
                return false;
            }
            ctx.humanizer().lookAt(ctx.position().add(0, 1.62, 0),
                    step.target().center().add(0, 1, 0));
            ctx.actions().useItemOn(step.target(), Direction.UP);
            waitTicks = ctx.rng().rangeInt(8, 14);
        } else {
            if (!ctx.inventory().equipMatching(snapshot,
                    m -> m == org.bukkit.Material.TORCH)) {
                steps.removeIf(s -> !s.path()); // bez pochodní už jen cestička
                return false;
            }
            current = new PlaceBlockTask(step.target());
        }
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
}
