package dev.botalive.core.build.plan;

/**
 * Co udělat, když stavba nemá materiál role ({@link PaletteRole}) po ruce.
 * Přiřazuje se <b>po rolích</b>: zeď se radši dozdí náhradou, okno se nechá
 * otvorem, dokud nebude sklo. Jedno místo pravdy pro {@code BuildSession}
 * (pokládka) i {@code MaintainHomeGoal} (oprava/údržba), aby se obě chovaly
 * stejně.
 */
public enum SubstitutionPolicy {

    /**
     * Chybí-li cílový materiál role, polož jakýkoli zaměnitelný stavební blok
     * (dnešní chování). Nosné a plné role – radši náhradní odstín než díra.
     */
    FILL_GENERIC,

    /**
     * Chybí-li cílový materiál, <b>nech otvor</b> (vzduch) a pokračuj – doplní
     * se, až materiál bude (okno bez skla je otvor, ne zazděná stěna). Cela se
     * nepovažuje za nepoloženou: stavba kvůli ní neskončí jako torzo a údržba
     * ji nezazdí generikem.
     */
    LEAVE_EMPTY,

    /**
     * Chybí-li cílový materiál vyššího stupně, polož materiál nižšího stupně
     * (tier-0) a zaznamenej „dluh na upgrade" – vylepší se, až materiál bude.
     * Vyžaduje žebřík tierů; zapojuje se ve fázi 2 (tiery palety). Do té doby
     * se chová jako {@link #FILL_GENERIC}.
     */
    TIER0_MATERIAL
}
