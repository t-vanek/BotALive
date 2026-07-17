package dev.botalive.core.tasks;

/**
 * Úloha, která během své práce řídí vozidlo (nasedne, jede, vysedne).
 *
 * <p>Běžné {@link BotTask} se tickají jen v pohybové smyčce, kterou ale
 * nasednutí do vozidla obchází (pozici hráče pak odvozuje server z vozidla).
 * Tento marker říká {@code BotImpl}, že úlohu má tickovat i ve vozidle – jinak
 * by po nasednutí ztratila kontrolu a nikdy nevydala povel k vysednutí.</p>
 */
public interface VehicleTask extends BotTask {
}
