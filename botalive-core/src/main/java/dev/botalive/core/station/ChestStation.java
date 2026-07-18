package dev.botalive.core.station;

import dev.botalive.core.ai.BotContext;
import dev.botalive.core.util.BlockPos;

import java.util.concurrent.CompletableFuture;

/**
 * Stanice truhel – kontrakt mezi {@code StashGoal} a implementací
 * (server-side {@code ContainerService} / paketová {@code PacketChestStation}).
 * Cíl truhlu otevírá sám (reálný interact paket); stanice řeší jen přesun.
 */
public interface ChestStation {

    /**
     * Přesune přebytky z inventáře bota do otevřeného kontejneru.
     *
     * @param ctx       kontext bota (stojí u otevřené truhly)
     * @param worldName svět kontejneru
     * @param chestPos  pozice kontejneru
     * @return future s počtem přesunutých kusů (0 = nic/chyba)
     */
    CompletableFuture<Integer> depositJunk(BotContext ctx, String worldName, BlockPos chestPos);

    /**
     * Vybere z kontejneru nouzové zásoby (krádež z beznadějе): jídlo a
     * volitelně základní vybavení. Bot musí stát u kontejneru.
     *
     * @param ctx         kontext bota (stojí u otevřené truhly)
     * @param worldName   svět kontejneru
     * @param chestPos    pozice kontejneru
     * @param includeGear vzít i nástroj a trochu materiálu (nejen jídlo)
     * @return future s počtem vzatých kusů (0 = nic/chyba)
     */
    CompletableFuture<Integer> withdrawSupplies(BotContext ctx, String worldName,
                                                BlockPos chestPos, boolean includeGear);

    /**
     * Vyloupí z kontejneru cennosti (kořist strukturálních truhel – kovářské
     * šablony, zlato, diamanty, obsidián…). Používá výprava v Netheru na
     * truhly pevností a bastionů; klasifikaci sdílí obě implementace
     * ({@code ContainerService.isValuableLoot}). Bot musí stát u kontejneru.
     *
     * @param ctx       kontext bota (stojí u otevřené truhly)
     * @param worldName svět kontejneru
     * @param chestPos  pozice kontejneru
     * @return future s počtem vzatých kusů (0 = nic/chyba)
     */
    CompletableFuture<Integer> lootValuables(BotContext ctx, String worldName, BlockPos chestPos);
}
