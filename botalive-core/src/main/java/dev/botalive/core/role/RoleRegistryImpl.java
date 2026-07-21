package dev.botalive.core.role;

import dev.botalive.api.role.BotRole;
import dev.botalive.api.role.RoleDefinition;
import dev.botalive.api.role.RoleRegistry;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registr profesí. Předregistruje vestavěné role (z {@link BotRole}
 * a {@link RoleProfiles}) jako {@link RoleDefinition} a přijímá cizí role
 * pluginů. Je jediným zdrojem pravdy o váhách cílů rolí za běhu.
 *
 * <p>Vestavěné role nelze přebít ani odebrat – registrace jejich id selže,
 * {@link #unregister(String)} je ignoruje.</p>
 */
public final class RoleRegistryImpl implements RoleRegistry {

    private final Map<String, RoleDefinition> byId = new ConcurrentHashMap<>();
    private final Set<String> builtIns;

    /** Seedne registr vestavěnými rolemi (mimo {@link BotRole#NONE}). */
    public RoleRegistryImpl() {
        Set<String> builtin = new HashSet<>();
        for (BotRole role : BotRole.values()) {
            if (role == BotRole.NONE) {
                continue;
            }
            String id = role.name().toLowerCase(Locale.ROOT);
            byId.put(id, new RoleDefinition(id, role.displayName(), RoleProfiles.weightsFor(role)));
            builtin.add(id);
        }
        this.builtIns = Set.copyOf(builtin);
    }

    @Override
    public void register(RoleDefinition role) {
        Objects.requireNonNull(role, "role");
        if (byId.putIfAbsent(role.id(), role) != null) {
            throw new IllegalArgumentException("Role '" + role.id() + "' je již registrována");
        }
    }

    @Override
    public boolean unregister(String id) {
        if (id == null) {
            return false;
        }
        String norm = normalize(id);
        if (builtIns.contains(norm)) {
            return false; // vestavěné role nelze odebrat
        }
        return byId.remove(norm) != null;
    }

    @Override
    public Optional<RoleDefinition> byId(String id) {
        return id == null ? Optional.empty() : Optional.ofNullable(byId.get(normalize(id)));
    }

    @Override
    public Collection<RoleDefinition> all() {
        return List.copyOf(byId.values());
    }

    @Override
    public double weight(String roleId, String goalId) {
        if (roleId == null) {
            return 1.0;
        }
        String norm = normalize(roleId);
        if (norm.isEmpty() || norm.equals("none")) {
            return 1.0;
        }
        RoleDefinition def = byId.get(norm);
        return def == null ? 1.0 : def.weight(goalId);
    }

    /**
     * @param id id role
     * @return {@code true} pokud jde o vestavěnou roli
     */
    public boolean isBuiltIn(String id) {
        return id != null && builtIns.contains(normalize(id));
    }

    private static String normalize(String id) {
        return id.trim().toLowerCase(Locale.ROOT);
    }
}
