package dev.botalive.core.memory;

import dev.botalive.api.memory.MemoryKind;
import dev.botalive.core.config.BotAliveConfig;

/**
 * Časový rozpad vztahů – přátelství i zášť bez oživování slábnou.
 *
 * <p>Vztahy dřív jen rostly (+0.05 za interakci) a nikdy neklesaly: po pár
 * týdnech provozu by byl každý kamarádem každého (všichni nad prahem aliance)
 * a stará zášť by blokovala vesnice navěky. Rozpad je čistá funkce – efektivní
 * důležitost se počítá při čtení z uložené hodnoty a času posledního oživení,
 * nic se nepřepisuje, takže se rozpad nikdy nesečte dvakrát (ani přes
 * restart). Oživení vztahu vychází z rozpadlé hodnoty a uloží ji s novým
 * časem – vztahy je potřeba udržovat, jinak vyhasnou k podlaze.</p>
 *
 * <p>Zášť se hojí rychleji než přátelství („čas rány hojí“): týden staré
 * bezpráví přestane blokovat vstup do vesnice, kdežto kamarádství z minulého
 * týdne pořád něco znamená.</p>
 */
public final class RelationDecay {

    /** Vypnutý rozpad (testy, konzervativní konfigurace). */
    public static final RelationDecay OFF = new RelationDecay(false, 0, 0, 0);

    private static final double DAY_MS = 24.0 * 60 * 60 * 1000;

    private final boolean enabled;
    private final double friendPerDay;
    private final double enemyPerDay;
    private final double floor;

    /**
     * @param enabled      zapnuto/vypnuto
     * @param friendPerDay o kolik denně slábne neoživované přátelství
     * @param enemyPerDay  o kolik denně slábne neoživovaná zášť
     * @param floor        podlaha – vztah rozpadem nikdy neklesne pod ni
     */
    public RelationDecay(boolean enabled, double friendPerDay, double enemyPerDay,
                         double floor) {
        this.enabled = enabled;
        this.friendPerDay = friendPerDay;
        this.enemyPerDay = enemyPerDay;
        this.floor = floor;
    }

    /**
     * @param config sekce {@code memory} konfigurace
     * @return rozpad podle konfigurace
     */
    public static RelationDecay fromConfig(BotAliveConfig.Memory config) {
        return new RelationDecay(config.relationDecayEnabled(),
                config.friendDecayPerDay(), config.enemyDecayPerDay(),
                config.relationFloor());
    }

    /**
     * Efektivní důležitost vzpomínky v čase {@code now}.
     *
     * <p>Jiné kategorie než FRIEND/ENEMY se nerozpadají; hodnota pod podlahou
     * se rozpadem dál nesnižuje (ale ani nezvedá – slabý vztah zůstává slabý).</p>
     *
     * @param kind       kategorie vzpomínky
     * @param importance uložená důležitost (platná k času {@code updatedAt})
     * @param updatedAt  čas posledního oživení vzpomínky (epoch ms)
     * @param now        aktuální čas (epoch ms)
     * @return důležitost po rozpadu
     */
    public double effective(MemoryKind kind, double importance, long updatedAt, long now) {
        double perDay = switch (kind) {
            case FRIEND -> friendPerDay;
            case ENEMY -> enemyPerDay;
            default -> 0;
        };
        if (!enabled || perDay <= 0 || now <= updatedAt) {
            return importance;
        }
        double decayed = importance - perDay * ((now - updatedAt) / DAY_MS);
        return Math.max(Math.min(importance, floor), decayed);
    }
}
