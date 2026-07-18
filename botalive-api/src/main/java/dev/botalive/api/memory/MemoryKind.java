package dev.botalive.api.memory;

/**
 * Kategorie vzpomínek, které si bot ukládá do dlouhodobé paměti.
 *
 * <p>Paměť je persistentní – po restartu serveru bot pokračuje tam, kde skončil.</p>
 */
public enum MemoryKind {

    /** Navštívené místo (chunk/oblast) – podklad pro průzkum. */
    VISITED_PLACE,

    /** Nepřítel – hráč nebo entita, která botovi ublížila. */
    ENEMY,

    /** Přítel – hráč/bot se kterým má bot pozitivní historii. */
    FRIEND,

    /** Objevená vesnice. */
    VILLAGE,

    /** Truhla nebo jiný kontejner, o kterém bot ví. */
    CHEST,

    /** Farma (pole plodin, ohrada se zvířaty). */
    FARM,

    /** Nebezpečné místo – láva, propast, past, místo častých útoků. */
    DANGER,

    /** Důl / vytěžená šachta. */
    MINE,

    /** Portál (nether/end). */
    PORTAL,

    /** Nether pevnost (nether brick) – zdroj blaze rodů a nether wartu. */
    FORTRESS,

    /** Bastion (blackstone) – truhly s kovářskými šablonami a zlatem. */
    BASTION,

    /** Místo úmrtí bota. */
    DEATH,

    /** Místo, kde bot ztratil předměty (typicky po smrti). */
    LOST_ITEMS,

    /** Domov – místo, kam se bot vrací (úkryt, postel). */
    HOME,

    /** Pec / tavicí pec – kovář sem nosí rudy a jídlo. */
    FURNACE,

    /** Enchantovací stůl. */
    ENCHANTING_TABLE,

    /** Ochočené zvíře bota (subjekt = UUID zvířete). */
    PET,

    /** Životní ambice bota (dlouhodobý projekt; data {@code type}). */
    AMBITION
}
