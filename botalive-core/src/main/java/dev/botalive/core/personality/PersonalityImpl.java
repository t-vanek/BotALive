package dev.botalive.core.personality;

import dev.botalive.api.personality.Personality;
import dev.botalive.api.personality.Trait;

import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Map;

/**
 * Nemutabilní implementace osobnosti bota.
 *
 * <p>Archetyp se odvozuje z dominantního rysu – slouží hlavně administrátorům
 * pro rychlou orientaci v {@code /botalive personality}.</p>
 */
public final class PersonalityImpl implements Personality {

    private final long seed;
    private final Map<Trait, Double> traits;
    private final String archetype;

    /**
     * @param seed   seed generátoru
     * @param traits hodnoty rysů (kopíruje se)
     */
    public PersonalityImpl(long seed, Map<Trait, Double> traits) {
        this.seed = seed;
        EnumMap<Trait, Double> copy = new EnumMap<>(Trait.class);
        for (Trait trait : Trait.values()) {
            copy.put(trait, clamp(traits.getOrDefault(trait, 0.5)));
        }
        this.traits = Collections.unmodifiableMap(copy);
        this.archetype = deriveArchetype(copy);
    }

    private static double clamp(double v) {
        return Math.max(0.0, Math.min(1.0, v));
    }

    @Override
    public double trait(Trait trait) {
        return traits.get(trait);
    }

    @Override
    public Map<Trait, Double> traits() {
        return traits;
    }

    @Override
    public long seed() {
        return seed;
    }

    @Override
    public String archetype() {
        return archetype;
    }

    /** Archetyp podle nejvýraznějšího rysu (odchylka od průměru 0.5). */
    private static String deriveArchetype(Map<Trait, Double> traits) {
        Trait dominant = traits.entrySet().stream()
                .max(Comparator.comparingDouble(e -> Math.abs(e.getValue() - 0.5)))
                .map(Map.Entry::getKey)
                .orElse(Trait.CURIOSITY);
        boolean high = traits.get(dominant) >= 0.5;
        return switch (dominant) {
            case COURAGE -> high ? "Válečník" : "Ustrašenec";
            case CAUTION -> high ? "Opatrník" : "Hazardér";
            case AGGRESSION -> high ? "Rváč" : "Mírotvůrce";
            case CURIOSITY -> high ? "Průzkumník" : "Pecivál";
            case SOCIABILITY -> high ? "Bavič" : "Samotář";
            case LAZINESS -> high ? "Povaleč" : "Dříč";
            case INTELLIGENCE -> high ? "Stratég" : "Prosťáček";
            case HELPFULNESS -> high ? "Parťák" : "Sobec";
            case GREED -> high ? "Zlatokop" : "Asketa";
            case PATIENCE -> high ? "Trpělivec" : "Horlivec";
        };
    }
}
