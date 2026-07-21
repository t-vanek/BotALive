package dev.botalive.core.tasks;

import dev.botalive.api.task.BotTask;
import dev.botalive.api.task.TaskRegistry;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Thread-safe registr pojmenovaných taktických tasků cizích pluginů.
 * Jména jsou case-insensitive; drží se továrny, aby každá {@link #create}
 * vrátila čerstvou (stavovou) instanci.
 */
public final class TaskRegistryImpl implements TaskRegistry {

    private final Map<String, Supplier<BotTask>> factories = new ConcurrentHashMap<>();

    @Override
    public void register(String taskId, Supplier<BotTask> factory) {
        Objects.requireNonNull(factory, "factory");
        if (taskId == null || taskId.isBlank()) {
            throw new IllegalArgumentException("Task musí mít neprázdné id");
        }
        String id = normalize(taskId);
        if (factories.putIfAbsent(id, factory) != null) {
            throw new IllegalArgumentException("Task '" + id + "' je již registrován");
        }
    }

    @Override
    public boolean unregister(String taskId) {
        return taskId != null && factories.remove(normalize(taskId)) != null;
    }

    @Override
    public Optional<BotTask> create(String taskId) {
        if (taskId == null) {
            return Optional.empty();
        }
        Supplier<BotTask> factory = factories.get(normalize(taskId));
        return factory == null ? Optional.empty() : Optional.ofNullable(factory.get());
    }

    @Override
    public List<String> registeredIds() {
        return List.copyOf(factories.keySet());
    }

    private static String normalize(String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }
}
