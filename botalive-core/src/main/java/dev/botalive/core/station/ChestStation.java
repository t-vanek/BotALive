package dev.botalive.core.station;

import dev.botalive.core.ai.BotContext;
import dev.botalive.core.util.BlockPos;

import java.util.concurrent.CompletableFuture;

/**
 * Stanice truhel – kontrakt mezi {@code StashGoal} a server-side implementací
 * ({@code ContainerService}). Cíl truhlu otevírá sám (reálný interact paket);
 * stanice řeší jen přesun.
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

    /**
     * Uloží do kontejneru nasbíranou kořist (opak {@link #lootValuables}) –
     * plní shulker box na výpravě, aby se uvolnil batoh. Nechává si perly
     * a rakety (spotřebák cesty); klasifikaci sdílí obě implementace
     * ({@code ContainerService.isHaul}). Bot musí stát u kontejneru.
     *
     * @param ctx       kontext bota (stojí u otevřeného kontejneru)
     * @param worldName svět kontejneru
     * @param chestPos  pozice kontejneru
     * @return future s počtem uložených kusů (0 = nic/chyba)
     */
    CompletableFuture<Integer> depositLoot(BotContext ctx, String worldName, BlockPos chestPos);

    /**
     * Uloží do kontejneru přebytek jídla (opak {@link #withdrawSupplies}) –
     * člen doplňuje společnou sýpku. Nechá si zásobu na cestu.
     *
     * @param ctx       kontext bota (stojí u otevřeného kontejneru)
     * @param worldName svět kontejneru
     * @param chestPos  pozice kontejneru
     * @param keepFood  kolik kusů jídla si nechat v inventáři
     * @return future s počtem uložených kusů (0 = nic/chyba)
     */
    CompletableFuture<Integer> depositFood(BotContext ctx, String worldName, BlockPos chestPos,
                                           int keepFood);
}
