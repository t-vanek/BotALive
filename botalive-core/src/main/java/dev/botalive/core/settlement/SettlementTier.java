package dev.botalive.core.settlement;

/**
 * Stupeň sídla – ODVOZENÝ z jeho skutečné substance, nikdy nedekretovaný.
 *
 * <p>Stejný princip jako „vesnice vzniká až s hotovým domem zakladatele":
 * osada se stává vesnicí, až když vesnicí fakticky je (dost dostavěných
 * domů), a městem, až když má městskou infrastrukturu (společné stavby,
 * fáze B+ růstové roadmapy). Stupeň se počítá za běhu z živého stavu –
 * persistuje se jen poslední OHLÁŠENÝ stupeň, aby se povýšení po restartu
 * neohlašovalo znovu. Ztráta domů stupeň tiše sníží (bez hlášky – zánik
 * se neslaví), další růst ho ohlásí zase.</p>
 */
public enum SettlementTier {

    OSADA("Osada"),
    VESNICE("Vesnice"),
    MESTO("Město");

    /** Od kolika dostavěných domů je sídlo vesnicí. */
    public static final int VILLAGE_HOUSES = 4;
    /** Od kolika dostavěných domů může být sídlo městem (s infrastrukturou). */
    public static final int TOWN_HOUSES = 8;

    private final String displayName;

    SettlementTier(String displayName) {
        this.displayName = displayName;
    }

    /** @return český název stupně pro výpisy */
    public String displayName() {
        return displayName;
    }

    /**
     * Odvodí stupeň ze substance sídla.
     *
     * @param houses   počet dostavěných domů členů
     * @param townInfra město má i společné stavby (sýpka, tržiště – fáze B+;
     *                  do té doby vždy {@code false}, samotný počet domů
     *                  z vesnice město nedělá)
     * @return stupeň sídla
     */
    public static SettlementTier of(int houses, boolean townInfra) {
        if (houses >= TOWN_HOUSES && townInfra) {
            return MESTO;
        }
        return houses >= VILLAGE_HOUSES ? VESNICE : OSADA;
    }
}
