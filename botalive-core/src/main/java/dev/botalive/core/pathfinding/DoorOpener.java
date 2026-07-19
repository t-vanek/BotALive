package dev.botalive.core.pathfinding;

import dev.botalive.core.util.BlockPos;

/**
 * Úzké rozhraní navigace k akcím klienta – otevření dveří/branky v cestě.
 *
 * <p>Navigátor ze sítě potřebuje jedinou akci (klik na zavřené dveře),
 * a závislost na celém {@code BotActions} by ho vázala na připojeného
 * klienta. Přes toto rozhraní dostane produkce paketový klik
 * ({@code BotActions.useItemOn}) a simulační testy lambdu, která dveře
 * přepne přímo ve světě – průchod dveřmi je tak krytý stejným
 * end-to-end kontraktem jako zbytek repertoáru.</p>
 */
@FunctionalInterface
public interface DoorOpener {

    /**
     * Otevře zavřené dveře/branku interakcí.
     *
     * @param door pozice bloku dveří
     */
    void open(BlockPos door);
}
