package dev.botalive.core.bot;

import dev.botalive.core.persistence.BotRepository;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Živé statistiky bota s periodickým flushem do databáze.
 *
 * <p>Čítače rostou z různých vláken (tick, síť), proto jsou atomické. Flush
 * odesílá <i>delty</i> od posledního flushe, takže se statistiky správně
 * sčítají napříč restarty.</p>
 */
public final class BotStats {

    private final UUID botId;
    private final BotRepository repository;

    private final AtomicLong blocksMined = new AtomicLong();
    private final AtomicLong blocksPlaced = new AtomicLong();
    private final AtomicLong deaths = new AtomicLong();
    private final AtomicLong kills = new AtomicLong();
    private final AtomicLong messagesSent = new AtomicLong();
    private final AtomicLong distanceCm = new AtomicLong();
    private final AtomicLong playtimeSeconds = new AtomicLong();

    /**
     * @param botId      UUID bota
     * @param repository repozitář pro flush
     */
    public BotStats(UUID botId, BotRepository repository) {
        this.botId = botId;
        this.repository = repository;
    }

    /** Přičte vytěžený blok. */
    public void addMined() {
        blocksMined.incrementAndGet();
    }

    /** Přičte položený blok. */
    public void addPlaced() {
        blocksPlaced.incrementAndGet();
    }

    /** Přičte smrt. */
    public void addDeath() {
        deaths.incrementAndGet();
    }

    /** Přičte zabití. */
    public void addKill() {
        kills.incrementAndGet();
    }

    /** Přičte odeslanou zprávu. */
    public void addMessage() {
        messagesSent.incrementAndGet();
    }

    /**
     * Přičte uraženou vzdálenost.
     *
     * @param blocks vzdálenost v blocích
     */
    public void addDistance(double blocks) {
        distanceCm.addAndGet((long) (blocks * 100));
    }

    /**
     * Přičte odehraný čas.
     *
     * @param seconds sekundy
     */
    public void addPlaytime(long seconds) {
        playtimeSeconds.addAndGet(seconds);
    }

    /**
     * Odešle nasbírané delty do databáze a vynuluje čítače.
     */
    public void flush() {
        long mined = blocksMined.getAndSet(0);
        long placed = blocksPlaced.getAndSet(0);
        long died = deaths.getAndSet(0);
        long killed = kills.getAndSet(0);
        long messages = messagesSent.getAndSet(0);
        long distance = distanceCm.getAndSet(0);
        long playtime = playtimeSeconds.getAndSet(0);
        if ((mined | placed | died | killed | messages | distance | playtime) != 0) {
            repository.addStats(botId, mined, placed, died, killed, messages, distance, playtime);
        }
    }
}
