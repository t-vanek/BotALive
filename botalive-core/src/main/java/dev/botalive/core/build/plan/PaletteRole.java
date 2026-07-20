package dev.botalive.core.build.plan;

/**
 * Role bloku ve stavbě – „co ten blok je", ne „z čeho je".
 *
 * <p>{@link Palette} mapuje roli na konkrétní materiál podle místního dřeva
 * a seedu; jedno místo pravdy pro vybavení, rozpis materiálu ({@code BOM})
 * i přijatelnost při opravě ({@code AcceptancePolicy}). Legacy stavby (dům
 * 4×4, studna, sýpka) používají jen {@link #GENERIC} – zaměnitelný stavební
 * blok jako dnes.</p>
 */
public enum PaletteRole {

    /** Zaměnitelný stavební blok (dnešní {@code equipBuildingBlock}). */
    GENERIC,
    /** Základová / spodní obruba stavby (kámen). */
    FOUNDATION,
    /** Hlavní zdivo. */
    WALL,
    /** Nároží a rámy (kontrastní, typicky kmeny). */
    WALL_ACCENT,
    /** Okno (sklo). */
    WINDOW,
    /** Střešní krytina. */
    ROOF
}
