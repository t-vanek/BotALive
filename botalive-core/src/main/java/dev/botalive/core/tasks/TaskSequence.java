package dev.botalive.core.tasks;

import dev.botalive.core.ai.BotContext;
import dev.botalive.core.physics.MoveInput;

import java.util.List;

/**
 * Sekvence tasků vykonávaná popořadě – např. vykopání bloků jednoho zásahu
 * z plánu cesty ({@code TerrainAction}).
 *
 * <p>Úspěch a neúspěch dítěte se nerozlišuje: po doběhnutí posledního kroku
 * je sekvence hotová a skutečný stav světa ověří navazující logika (validace
 * cesty, detekce zaseknutí) – tasky samy hlásí dokončení i při selhání
 * a případný nezdar se projeví právě tam.</p>
 */
public final class TaskSequence implements BotTask {

    private final List<BotTask> steps;
    private int index;

    /**
     * @param steps kroky v pořadí vykonání (aspoň jeden)
     */
    public TaskSequence(List<BotTask> steps) {
        this.steps = List.copyOf(steps);
    }

    @Override
    public boolean tick(BotContext ctx) {
        while (index < steps.size()) {
            if (!steps.get(index).tick(ctx)) {
                return false;
            }
            index++;
        }
        return true;
    }

    @Override
    public void cancel(BotContext ctx) {
        if (index < steps.size()) {
            steps.get(index).cancel(ctx);
        }
    }

    @Override
    public MoveInput move() {
        return index < steps.size() ? steps.get(index).move() : MoveInput.IDLE;
    }
}
