package dev.botalive.api.personality;

import java.util.Map;

/**
 * Osobnost bota – sada rysů a odvozený komunikační styl.
 *
 * <p>Základ osobnosti se generuje deterministicky ze seedu, ale povaha se
 * <b>pomalu vyvíjí podle prožitků</b>: komu projde krádež, tomu roste
 * chamtivost; kdo pomáhá, tomu roste ochota; smrt učí opatrnosti. Drift je
 * omezený, jádro povahy zůstává. Aktuální hodnoty rysů se persistují –
 * po restartu bot pokračuje s povahou, kterou si vypěstoval. Čtení hodnot
 * je thread-safe (snapshot).</p>
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
