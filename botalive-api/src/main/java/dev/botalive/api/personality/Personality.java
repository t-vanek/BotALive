package dev.botalive.api.personality;

import java.util.Map;

/**
 * Osobnost bota – sada rysů a odvozený komunikační styl.
 *
 * <p>Osobnost je nemutabilní: generuje se jednou (deterministicky ze seedu),
 * ukládá se do databáze a po restartu serveru se obnoví beze změny.</p>
 */
public interface Personality {

    /**
     * @param trait požadovaný rys
     * @return hodnota rysu v intervalu {@code [0.0, 1.0]}
     */
    double trait(Trait trait);

    /**
     * @return všechny rysy jako nemodifikovatelná mapa
     */
    Map<Trait, Double> traits();

    /**
     * @return seed, ze kterého byla osobnost deterministicky vygenerována
     */
    long seed();

    /**
     * Lidsky čitelný archetyp odvozený z dominantních rysů,
     * např. „Válečník“, „Průzkumník“, „Kutil“, „Povaleč“.
     *
     * @return název archetypu
     */
    String archetype();
}
