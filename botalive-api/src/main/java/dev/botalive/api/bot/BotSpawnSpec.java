package dev.botalive.api.bot;

import org.bukkit.Location;

import java.util.Objects;

/**
 * Specifikace pro vytvoření nového bota.
 *
 * <p>Všechny hodnoty kromě jména jsou volitelné – neuvedené doplní implementace
 * z konfigurace (spawn pozice) nebo náhodně (seed osobnosti).</p>
 *
 * @param name            požadované herní jméno (3–16 znaků, [A-Za-z0-9_])
 * @param spawnLocation   místo prvního spawnu, nebo {@code null} pro výchozí z konfigurace
 * @param personalitySeed seed generátoru osobnosti; {@code null} znamená náhodný
 */
public record BotSpawnSpec(String name, Location spawnLocation, Long personalitySeed) {

    /** Validace jména podle pravidel Minecraft účtů. */
    public BotSpawnSpec {
        Objects.requireNonNull(name, "name");
        if (!name.matches("[A-Za-z0-9_]{3,16}")) {
            throw new IllegalArgumentException("Neplatné jméno bota: '" + name
                    + "' (povoleno 3-16 znaků [A-Za-z0-9_])");
        }
    }

    /**
     * Zkratka pro spec pouze se jménem.
     *
     * @param name herní jméno bota
     * @return specifikace s výchozími hodnotami
     */
    public static BotSpawnSpec named(String name) {
        return new BotSpawnSpec(name, null, null);
    }
}
