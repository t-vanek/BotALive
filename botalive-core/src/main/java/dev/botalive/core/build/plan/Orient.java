package dev.botalive.core.build.plan;

/**
 * Orientace položeného bloku – kam „míří" (schody, dveře, srub).
 *
 * <p>Ve fázi V2a je jen {@link #NONE}: legacy stavby kladou plné kostky bez
 * orientace. Směrové varianty (a jejich rotace s natočením stavby) přijdou
 * s rozmanitými domy ve V2b, spolu s kurzorovým {@code useItemOn}.</p>
 */
public enum Orient {

    /** Plná kostka bez orientace. */
    NONE
}
