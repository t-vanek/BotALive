package dev.botalive.core.ai;

import dev.botalive.api.ai.Goal;
import dev.botalive.api.ai.GoalRegistry;
import dev.botalive.api.bot.Bot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Thread-safe registr továren AI cílů.
 *
 * <p>Vestavěné cíle registruje {@code CompositionRoot} při startu; cizí
 * pluginy mohou kdykoli přidat vlastní. Každý bot dostává čerstvé instance.</p>
 */
public final class GoalRegistryImpl implements GoalRegistry {

    private final Map<String, Function<Bot, Goal>> factories = new LinkedHashMap<>();

    @Override
    public synchronized void register(String goalId, Function<Bot, Goal> factory) {
        if (factories.containsKey(goalId)) {
            throw new IllegalArgumentException("Cíl s id '" + goalId + "' je již registrován");
        }
        factories.put(goalId, factory);
    }

    @Override
    public synchronized boolean unregister(String goalId) {
        return factories.remove(goalId) != null;
    }

    @Override
    public synchronized List<String> registeredIds() {
        return List.copyOf(factories.keySet());
    }

    @Override
    public synchronized List<Goal> instantiateAll(Bot bot) {
        List<Goal> goals = new ArrayList<>(factories.size());
        for (Function<Bot, Goal> factory : factories.values()) {
            goals.add(factory.apply(bot));
        }
        return goals;
    }
}
