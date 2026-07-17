package dev.botalive.core.personality;

import dev.botalive.api.personality.Personality;
import dev.botalive.api.personality.Trait;

import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.Map;

/**
 * Implementace osobnosti bota s pomalým vývojem podle prožitků.
 *
 * <p>Základ rysů je deterministický ze seedu; zkušenosti (krádež, pomoc,
 * smrt…) hodnoty postupně posouvají – viz {@link PersonalityEvolution}.
 * Drift je omezený vůči základu, takže se povaha vyvíjí, ale nepřepóluje
 * přes noc. Čtení je bezzámkové (copy-on-write snapshot), úpravy vzácné
 * a synchronizované.</p>
 *
 * <p>Archetyp se odvozuje z dominantního rysu <b>aktuálních</b> hodnot –
 * když se Asketovi zalíbí krást, může se časem stát Zlatokopem.</p>
 */
public final class PersonalityImpl implements Personality {

    private final long seed;
    private volatile Map<Trait, Double> traits;
    private volatile String archetype;
    /** Základ ze seedu pro omezení driftu – líně dopočítaný. */
    private volatile Map<Trait, Double> baseTraits;

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

    /**
     * @param trait rys
     * @return základní (vygenerovaná) hodnota rysu před vývojem
     */
    public double baseTrait(Trait trait) {
        Map<Trait, Double> base = baseTraits;
        if (base == null) {
            base = PersonalityGenerator.generate(seed).traits();
            baseTraits = base;
        }
        return base.get(trait);
    }

    /**
     * Posune rysy o dané delty (vývoj osobnosti).
     *
     * <p>Každý rys je oříznut do [0.02, 0.98] a jeho odchylka od základu
     * ze seedu nesmí překročit {@code maxDrift}. Archetyp se přepočítá.</p>
     *
     * @param deltas   posuny rysů
     * @param maxDrift maximální povolená odchylka od základu
     * @return pro každý skutečně změněný rys pole {@code [před, po]}
     */
    public synchronized Map<Trait, double[]> applyDeltas(Map<Trait, Double> deltas,
                                                         double maxDrift) {
        EnumMap<Trait, Double> next = new EnumMap<>(traits);
        EnumMap<Trait, double[]> changed = new EnumMap<>(Trait.class);
        for (Map.Entry<Trait, Double> entry : deltas.entrySet()) {
            Trait trait = entry.getKey();
            double before = next.get(trait);
            double base = baseTrait(trait);
            double target = before + entry.getValue();
            target = Math.max(base - maxDrift, Math.min(base + maxDrift, target));
            target = Math.max(0.02, Math.min(0.98, target));
            if (Math.abs(target - before) > 1.0E-9) {
                next.put(trait, target);
                changed.put(trait, new double[]{before, target});
            }
        }
        if (!changed.isEmpty()) {
            this.traits = Collections.unmodifiableMap(next);
            this.archetype = deriveArchetype(next);
        }
        return changed;
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
