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
    GENERIC(SubstitutionPolicy.FILL_GENERIC),
    /** Základová / spodní obruba stavby (kámen). */
    FOUNDATION(SubstitutionPolicy.FILL_GENERIC),
    /** Hlavní zdivo. */
    WALL(SubstitutionPolicy.FILL_GENERIC),
    /** Nároží a rámy (kontrastní, typicky kmeny). */
    WALL_ACCENT(SubstitutionPolicy.FILL_GENERIC),
    /** Okno (sklo) – bez skla se nechá otvor, nezazdívá se. */
    WINDOW(SubstitutionPolicy.LEAVE_EMPTY),
    /** Střešní krytina. */
    ROOF(SubstitutionPolicy.FILL_GENERIC);

    private final SubstitutionPolicy substitution;

    PaletteRole(SubstitutionPolicy substitution) {
        this.substitution = substitution;
    }

    /**
     * @return co dělat, když stavba nemá materiál této role po ruce
     *         (náhrada generikem / otvor / tier-0 s dluhem na upgrade)
     */
    public SubstitutionPolicy substitution() {
        return substitution;
    }
}
