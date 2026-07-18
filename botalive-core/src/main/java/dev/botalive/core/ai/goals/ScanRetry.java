package dev.botalive.core.ai.goals;

/**
 * Opakování skenu světa přes „studenou" chunk cache.
 *
 * <p>{@code WorldView.materialAt} vrací pro nenacachovaný chunk {@code null}
 * a data si teprve asynchronně vyžádá (chunk snapshoty s TTL). První sken po
 * teleportu nebo delším přesunu proto typicky nic nenajde, i když hledané
 * bloky existují – cíl by se hned vzdal (cooldown) a bota by převzal jiný
 * cíl, který ho odvede pryč. Místo toho cíl chvíli stojí a sken zopakuje;
 * mezitím se async snapshoty donačtou.</p>
 */
final class ScanRetry {

    private final int maxAttempts;
    private final int delayTicks;

    private int attempts;
    private int waitTicks;

    /**
     * @param maxAttempts kolik skenů celkem, než to cíl vzdá
     * @param delayTicks  čekání mezi skeny (ticky) – čas na async načtení
     */
    ScanRetry(int maxAttempts, int delayTicks) {
        this.maxAttempts = maxAttempts;
        this.delayTicks = delayTicks;
    }

    /**
     * Odpočítává čekání mezi pokusy – volat na začátku FIND fáze.
     *
     * @return {@code true}, dokud se čeká (sken tenhle tick neprovádět)
     */
    boolean waiting() {
        if (waitTicks > 0) {
            waitTicks--;
            return true;
        }
        return false;
    }

    /**
     * Zaznamená neúspěšný sken.
     *
     * @return {@code true} = počkat a zkusit znovu; {@code false} = pokusy
     *         vyčerpány, cíl to má vzdát
     */
    boolean shouldRetry() {
        attempts++;
        if (attempts >= maxAttempts) {
            return false;
        }
        waitTicks = delayTicks;
        return true;
    }

    /** @return {@code true} právě po prvním neúspěchu (vhodná chvíle na prefetch) */
    boolean firstFailure() {
        return attempts == 1;
    }

    /** Nový cyklus hledání (start cíle). */
    void reset() {
        attempts = 0;
        waitTicks = 0;
    }
}
