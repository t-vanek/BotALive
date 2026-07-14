package dev.botalive.core.network;

import dev.botalive.core.util.Vec3;

import java.util.UUID;

/**
 * Callback rozhraní síťové vrstvy směrem k jádru bota.
 *
 * <p>Odděluje parsování paketů ({@link BotSessionListener}) od reakcí bota
 * ({@code BotImpl}); síťová vrstva nezná AI a naopak. Všechny metody se volají
 * ze síťového (paket) vlákna bota – implementace musí být rychlé a neblokovat,
 * typicky jen zapíší do {@link BotClientState} nebo front.</p>
 */
public interface NetworkEvents {

    /**
     * Bot prošel loginem do stavu PLAY.
     *
     * @param entityId síťové id hráče-bota
     * @param worldKey protokolový klíč světa
     */
    void onLogin(int entityId, String worldKey);

    /**
     * Server poslal autoritativní pozici (teleport). Accept už byl odeslán.
     *
     * @param teleport data teleportu k aplikaci v ticku
     */
    void onTeleport(TeleportSync teleport);

    /**
     * Bot zemřel.
     *
     * @param deathMessage textová zpráva o smrti (plain text)
     */
    void onDeath(String deathMessage);

    /**
     * Server provedl respawn bota (po PERFORM_RESPAWN).
     *
     * @param worldKey protokolový klíč světa po respawnu
     */
    void onRespawn(String worldKey);

    /**
     * Server udělil botovi rychlost (typicky knockback).
     *
     * @param impulse nová rychlost (bloky/tick)
     */
    void onKnockback(Vec3 impulse);

    /**
     * Bot dostal chat zprávu od hráče.
     *
     * @param sender     UUID odesílatele
     * @param senderName jméno odesílatele (plain text)
     * @param content    obsah zprávy (plain text)
     */
    void onPlayerChat(UUID sender, String senderName, String content);

    /**
     * Server poslal autoritativní pozici vozidla, ve kterém bot sedí
     * (korekce klientské simulace lodi).
     *
     * @param position pozice vozidla
     * @param yaw      natočení vozidla
     */
    void onVehicleMove(Vec3 position, float yaw);

    /**
     * Spojení bylo ukončeno.
     *
     * @param reason lidsky čitelný důvod
     */
    void onDisconnected(String reason);
}
