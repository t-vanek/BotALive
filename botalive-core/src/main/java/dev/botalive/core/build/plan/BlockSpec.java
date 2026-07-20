package dev.botalive.core.build.plan;

/**
 * Předpis jednoho bloku stavby: jeho {@link PaletteRole role} a
 * {@link Orient orientace}. Odděluje „co se klade" od „z čeho" (paleta) a
 * „kam míří" (kurzor) – obojí se plně zapojuje až ve fázi V2b.
 *
 * @param role   role bloku (zeď, střecha…, zatím jen GENERIC)
 * @param orient orientace (zatím jen NONE)
 */
public record BlockSpec(PaletteRole role, Orient orient) {

    /** Zaměnitelný stavební blok bez orientace – jediná varianta fáze V2a. */
    public static final BlockSpec GENERIC = new BlockSpec(PaletteRole.GENERIC, Orient.NONE);
}
