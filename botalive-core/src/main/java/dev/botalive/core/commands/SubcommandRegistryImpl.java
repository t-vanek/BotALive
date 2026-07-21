package dev.botalive.core.commands;

import dev.botalive.api.command.BotSubcommand;
import dev.botalive.api.command.SubcommandRegistry;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thread-safe registr cizích podpříkazů {@code /botalive}.
 *
 * <p>Jména jsou case-insensitive. Vyhrazená jména (vestavěné podpříkazy) nelze
 * přebít – registrace takového jména selže, aby cizí plugin nedostal přednost
 * před jádrem. Registruje se z libovolného vlákna (typicky {@code onEnable}
 * cizího pluginu), čte se z hlavního vlákna při dispatchi příkazu.</p>
 */
public final class SubcommandRegistryImpl implements SubcommandRegistry {

    private final Map<String, BotSubcommand> byName = new ConcurrentHashMap<>();
    private final Set<String> reserved;

    /**
     * @param reservedNames jména vestavěných podpříkazů, která nelze přebít
     */
    public SubcommandRegistryImpl(Collection<String> reservedNames) {
        Set<String> lowered = new HashSet<>();
        for (String name : reservedNames) {
            if (name != null) {
                lowered.add(name.toLowerCase(Locale.ROOT));
            }
        }
        this.reserved = Set.copyOf(lowered);
    }

    @Override
    public void register(BotSubcommand subcommand) {
        Objects.requireNonNull(subcommand, "subcommand");
        String raw = subcommand.name();
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Podpříkaz musí mít neprázdné jméno");
        }
        String name = normalize(raw);
        if (name.contains(" ")) {
            throw new IllegalArgumentException("Jméno podpříkazu nesmí obsahovat mezery: '" + raw + "'");
        }
        if (reserved.contains(name)) {
            throw new IllegalArgumentException(
                    "Jméno '" + name + "' je vyhrazené vestavěnému podpříkazu");
        }
        if (byName.putIfAbsent(name, subcommand) != null) {
            throw new IllegalArgumentException("Podpříkaz '" + name + "' je již registrován");
        }
    }

    @Override
    public boolean unregister(String name) {
        return name != null && byName.remove(normalize(name)) != null;
    }

    @Override
    public List<String> registeredNames() {
        return List.copyOf(byName.keySet());
    }

    /**
     * @param name jméno podpříkazu (case-insensitive)
     * @return registrovaný podpříkaz, nebo {@code null}
     */
    public BotSubcommand byName(String name) {
        return name == null ? null : byName.get(normalize(name));
    }

    private static String normalize(String name) {
        return name.trim().toLowerCase(Locale.ROOT);
    }
}
