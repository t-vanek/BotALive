package dev.botalive.api.personality;

/**
 * Osobnostní rysy bota.
 *
 * <p>Každý rys má hodnotu v intervalu {@code [0.0, 1.0]}; 0.5 je populační průměr.
 * Rysy jsou generovány z gaussovského rozdělení, takže extrémní povahy jsou vzácné
 * a žádní dva boti nejsou stejní. Rysy ovlivňují váhy AI cílů, styl chatu, bojové
 * rozhodování i drobné návyky (jak často se bot rozhlíží, jak dlouho otálí, ...).</p>
 */
public enum Trait {

    /** Odvaha – ochota riskovat, bojovat se silnějšími nepřáteli, slézat do jeskyní. */
    COURAGE,

    /** Opatrnost – vyhýbání se nebezpečí, stavění úkrytů, ústup při zranění. */
    CAUTION,

    /** Agresivita – tendence útočit první, pronásledovat, oplácet. */
    AGGRESSION,

    /** Zvědavost – touha objevovat nové oblasti, zkoumat struktury a hráče. */
    CURIOSITY,

    /** Společenskost – vyhledávání hráčů a botů, četnost chatování. */
    SOCIABILITY,

    /** Lenost – delší prostoje, pomalejší reakce, neochota k dlouhým cestám. */
    LAZINESS,

    /** Inteligence – kvalita plánování, výběr nástrojů, méně chyb. */
    INTELLIGENCE,

    /** Ochota pomoci – následování hráčů, sdílení předmětů, obrana ostatních. */
    HELPFULNESS,

    /** Chamtivost – priorita těžby, sbírání předmětů, hromadění bohatství. */
    GREED,

    /** Trpělivost – výdrž u dlouhých činností (těžba, stavba, čekání). */
    PATIENCE
}
