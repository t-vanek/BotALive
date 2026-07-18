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

    /**
     * Volání o pomoc – bot se rozhodne podle odvahy, ochoty a vztahu;
     * vyhoví-li, vyrazí za volajícím (a boj u něj řeší bojová AI).
     *
     * @param sender kdo volá o pomoc
     * @return {@code true} pokud bot vyráží na pomoc
     */
    boolean helpRequest(UUID sender);

    /**
     * Prosba o konkrétní itemy („dej mi dřevo") – bot se rozhodne podle
     * zásob a ochoty; vyhoví-li, dojde k prosícímu a itemy mu upustí.
     *
     * @param sender kdo prosí
     * @param wanted materiály, o které si říká (aliasy z jazyka banky)
     * @return {@code true} pokud bot vyhoví
     */
    boolean giveItemRequest(UUID sender, java.util.List<org.bukkit.Material> wanted);

    /**
     * Hráč reaguje na vyvolávanou tržní nabídku („beru!") – bot mu ji zamluví.
     *
     * <p>Jen pro skutečné hráče (boti kupují přes {@code MarketBoard} sami) a
     * jen s Vault ekonomikou – bez ní nejde ověřit příchozí platba.</p>
     *
     * @param sender     kdo kupuje
     * @param senderName jméno kupce (pro chat a evidenci obchodu)
     * @return {@code true} když nabídka platila a hráč ji získal
     */
    boolean marketBuyRequest(UUID sender, String senderName);

    /**
     * @return počet hráčů/botů v doslechu (tlumení chatu v davu)
     */
    int nearbyPlayerCount();
}
