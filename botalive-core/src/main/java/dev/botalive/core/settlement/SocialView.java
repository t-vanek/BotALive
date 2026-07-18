package dev.botalive.core.settlement;

import dev.botalive.core.util.BlockPos;

import java.util.Map;
import java.util.UUID;

/**
 * Sociální snímek bota pro rozhodování o vesnicích – co služba potřebuje
 * vědět, aniž by sahala do živého stavu bota.
 *
 * <p>Snímek si bot sestavuje na vlastním tick vlákně z paměti (FRIEND/ENEMY
 * vzpomínky) a osobnosti; {@link SettlementService} pak rozhoduje jen nad
 * těmito daty. Díky tomu je rozhodovací logika čistá a testovatelná a služba
 * nikdy nedrží svůj zámek přes cizí zámky.</p>
 *
 * @param botId          UUID bota
 * @param botName        jméno bota
 * @param world          svět, kde bot je
 * @param position       pozice bota (bloky)
 * @param sociability    rys SOCIABILITY (0–1)
 * @param patience       rys PATIENCE (0–1)
 * @param housePos       pozice vlastního domu ({@code HOME} typu house),
 *                       nebo {@code null} pokud dům nemá
 * @param friends        důležitost FRIEND vzpomínek podle subjektu (0–1)
 * @param enemies        důležitost ENEMY vzpomínek podle subjektu (0–1)
 * @param enemyUpdatedAt čas posledního oživení ENEMY vzpomínky (epoch ms)
 */
public record SocialView(
        UUID botId,
        String botName,
        String world,
        BlockPos position,
        double sociability,
        double patience,
        BlockPos housePos,
        Map<UUID, Double> friends,
        Map<UUID, Double> enemies,
        Map<UUID, Long> enemyUpdatedAt
) {

    /** @return důležitost přátelství k subjektu (0 pokud žádné) */
    public double friend(UUID subject) {
        return friends.getOrDefault(subject, 0.0);
    }

    /** @return důležitost nepřátelství k subjektu (0 pokud žádné) */
    public double enemy(UUID subject) {
        return enemies.getOrDefault(subject, 0.0);
    }
}
