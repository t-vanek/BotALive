package dev.botalive.core.build.plan;

/**
 * Role bloku ve stavbě – „co ten blok je", ne „z čeho je".
 *
 * <p>Paleta (fáze V2b) mapuje roli na konkrétní materiál podle biomu, profese
 * a dostupnosti; jedno místo pravdy pro world-diff, náhrady i vybavení. Ve
 * fázi V2a existuje jen {@link #GENERIC} – legacy stavby (dům, studna, sýpka)
 * kladou zaměnitelný stavební blok, přesně jako dnes, takže se paleta ještě
 * nezapojuje.</p>
 */
public enum PaletteRole {

    /** Zaměnitelný stavební blok (dnešní {@code equipBuildingBlock}). */
    GENERIC
}
