package dev.botalive.api.bot;

import java.util.UUID;

/**
 * Nemutabilní momentka stavu bota určená pro čtení z libovolného vlákna.
 *
 * <p>Živý stav bota se mění na jeho tick vlákně; snapshot je bezpečný způsob,
 * jak stav vystavit příkazům, placeholderům a cizím pluginům.</p>
 *
 * @param botId        UUID bota (offline-mode UUID odvozené ze jména)
 * @param name         herní jméno bota
 * @param state        aktuální fáze životního cyklu
 * @param worldName    název světa, ve kterém se bot nachází (může být {@code null} před spawnem)
 * @param x            souřadnice X
 * @param y            souřadnice Y
 * @param z            souřadnice Z
 * @param yaw          natočení těla (stupně)
 * @param pitch        náklon hlavy (stupně)
 * @param health       zdraví (0–20)
 * @param food         najedenost (0–20)
 * @param currentGoal  id právě vykonávaného AI cíle, nebo {@code null}
 * @param online       zda je bot právě připojen k serveru
 */
public record BotSnapshot(
        UUID botId,
        String name,
        BotLifecycleState state,
        String worldName,
        double x,
        double y,
        double z,
        float yaw,
        float pitch,
        double health,
        int food,
        String currentGoal,
        boolean online
) {
}
