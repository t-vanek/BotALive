package dev.botalive.core.memory;

import dev.botalive.api.memory.MemoryKind;
import dev.botalive.api.memory.MemoryKindDefinition;
import dev.botalive.api.memory.MemoryKindRegistry;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Thread-safe registr cizích kategorií vzpomínek. Vyhrazená jsou id vestavěných
 * {@link MemoryKind} (case-insensitive) – ta nelze přebít. Klíčem je id kategorie
 * v původním tvaru (case-sensitive), aby {@code "myplugin:Shrine"} a
 * {@code "myplugin:shrine"} nekolidovaly s pluginovým záměrem.
 */
public final class MemoryKindRegistryImpl implements MemoryKindRegistry {

    private final ConcurrentHashMap<String, MemoryKindDefinition> byId = new ConcurrentHashMap<>();
    private final Set<String> reserved;

    /** Seedne vyhrazená jména z vestavěných kategorií. */
    public MemoryKindRegistryImpl() {
        this.reserved = java.util.Arrays.stream(MemoryKind.values())
                .map(k -> k.name().toLowerCase(Locale.ROOT))
                .collect(Collectors.toUnmodifiableSet());
    }

    @Override
    public void register(MemoryKindDefinition definition) {
        Objects.requireNonNull(definition, "definition");
        if (reserved.contains(definition.id().toLowerCase(Locale.ROOT))) {
            throw new IllegalArgumentException(
                    "Id '" + definition.id() + "' je vyhrazené vestavěné kategorii");
        }
        if (byId.putIfAbsent(definition.id(), definition) != null) {
            throw new IllegalArgumentException(
                    "Kategorie '" + definition.id() + "' je již registrována");
        }
    }

    @Override
    public boolean unregister(String id) {
        return id != null && byId.remove(id) != null;
    }

    @Override
    public Optional<MemoryKindDefinition> byId(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(id));
    }

    @Override
    public Collection<MemoryKindDefinition> all() {
        return List.copyOf(byId.values());
    }
}
