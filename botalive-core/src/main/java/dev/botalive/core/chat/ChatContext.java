package dev.botalive.core.chat;

import java.util.UUID;

/**
 * Napojení chatu na skutečný stav bota – odpovědi místo frází.
 *
 * <p>Chat s ním odpovídá na věcné otázky („kde jsi?", „co máš?", „kde je
 * vesnice?") daty z fyziky, inventáře a paměti a vykonává jednoduché prosby
 * („pojď za mnou", „dej mi jídlo"). Implementuje {@code BotImpl}.</p>
 */
public interface ChatContext {

    /** @return popis aktuální činnosti (intent vrstva), nebo {@code null} */
    String describeActivity();

    /** @return lidský popis polohy bota, nebo {@code null} */
    String describeLocation();

    /** @return krátký souhrn inventáře, nebo {@code null} */
    String describeInventory();

    /** @return kde je nejbližší známá vesnice (z paměti), nebo {@code null} */
    String describeVillage();

    /**
     * Prosba „pojď za mnou" – bot se rozhodne podle povahy a vztahu.
     *
     * @param sender kdo prosí
     * @return {@code true} pokud bot vyhoví (přepne na follow)
     */
    boolean followRequest(UUID sender);

    /**
     * Prosba „dej mi jídlo" – bot se rozhodne podle zásob a ochoty.
     *
     * @param sender kdo prosí
     * @return {@code true} pokud bot vyhoví (rozdá jídlo)
     */
    boolean giveFoodRequest(UUID sender);
}
