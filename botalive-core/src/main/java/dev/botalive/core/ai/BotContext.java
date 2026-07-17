package dev.botalive.core.ai;

import dev.botalive.api.bot.Bot;
import dev.botalive.core.bot.BotStats;
import dev.botalive.core.bot.ServerSideView;
import dev.botalive.core.chat.ChatEngine;
import dev.botalive.core.combat.CombatController;
import dev.botalive.core.config.BotAliveConfig;
import dev.botalive.core.entity.EntityTracker;
import dev.botalive.core.human.Humanizer;
import dev.botalive.core.inventory.InventoryHelper;
import dev.botalive.core.network.BotActions;
import dev.botalive.core.network.BotClientState;
import dev.botalive.core.pathfinding.Navigator;
import dev.botalive.core.physics.MoveInput;
import dev.botalive.core.util.BotRandom;
import dev.botalive.core.util.Vec3;
import dev.botalive.core.world.WorldView;

/**
 * Interní kontext bota pro vestavěné AI cíle a tasky.
 *
 * <p>Veřejné API ({@link Bot}) vystavuje jen bezpečnou podmnožinu; vestavěné
 * cíle potřebují plný přístup k subsystémům (navigace, boj, inventář, svět).
 * Implementuje ho {@code BotImpl}; cíle si Bot přetypují přes {@link #of}.</p>
 */
public interface BotContext {

    /** @return API pohled na tohoto bota */
    Bot bot();

    /** @return protokolový stav (zdraví, jídlo, entity id) */
    BotClientState clientState();

    /** @return tracker entit viditelných botem */
    EntityTracker entities();

    /** @return navigace (A* + následování cesty) */
    Navigator navigator();

    /** @return akční primitivy (kopání, útok, použití itemu) */
    BotActions actions();

    /** @return humanizace pohledu a reakcí */
    Humanizer humanizer();

    /** @return server-side snapshot bota */
    ServerSideView serverView();

    /** @return pomocník výběru nástrojů */
    InventoryHelper inventory();

    /** @return klientský model inventáře (okno 0, synchronizovaný z paketů) */
    dev.botalive.core.inventory.ClientInventory clientInventory();

    /** @return sledování otevřených oken kontejnerů */
    dev.botalive.core.container.ContainerTracker containers();

    /** @return container kliky (paketové stanice) */
    dev.botalive.core.container.ContainerClicker clicker();

    /** @return překlad item ID → materiál, nebo {@code null} bez tabulky */
    dev.botalive.core.world.state.ItemMapper itemMapper();

    /** @return pohled na svět, ve kterém bot je (může být {@code null} před spawnem) */
    WorldView worldView();

    /** @return per-bot náhoda */
    BotRandom rng();

    /** @return konverzační engine bota */
    ChatEngine chat();

    /** @return bojový kontrolér */
    CombatController combat();

    /** @return statistiky bota */
    BotStats stats();

    /** @return konfigurace pluginu */
    BotAliveConfig config();

    /** @return most na herní vlákna (Folia-safe scheduler) */
    dev.botalive.core.scheduler.MainThreadBridge bridge();

    /** @return řízení vozidel (lodě, minecarty) */
    dev.botalive.core.vehicle.VehicleController vehicle();

    /** @return aktuální pozice bota (nohy) */
    Vec3 position();

    /** @return {@code true} pokud bot stojí na zemi */
    boolean onGround();

    /** @return aktuální herní čas světa (0–23999), nebo -1 pokud neznámý */
    long worldTime();

    /**
     * Vyžádá explicitní pohyb pro tento tick (přebije navigátor).
     * Používají cíle, které se pohybují bez pathfindingu (útěk, strafe).
     *
     * @param input pohybový vstup
     */
    void requestMove(MoveInput input);

    /**
     * Zaznamená prožitek formující osobnost (vývoj rysů + persistence
     * + případný komentář bota k vlastní proměně).
     *
     * @param experience prožitek
     */
    void gainExperience(dev.botalive.core.personality.PersonalityEvolution.BotExperience experience);

    /** @return sdílená kniha zločinů (krádeže mají oběti a následky) */
    dev.botalive.core.social.CrimeLog crimeLog();

    /**
     * Odhadne dobu těžby bloku v ticích – autoritativně přes server-side
     * {@code Block.getBreakSpeed(Player)} (zohledňuje nástroj, enchanty, efekty).
     *
     * @param pos pozice bloku
     * @return future s počtem ticků (fallback 40 při chybě)
     */
    java.util.concurrent.CompletableFuture<Integer> estimateBreakTicks(dev.botalive.core.util.BlockPos pos);

    /**
     * Pomocník pro vestavěné cíle – získá interní kontext z API bota.
     *
     * @param bot API bot
     * @return interní kontext
     */
    static BotContext of(Bot bot) {
        return (BotContext) bot;
    }
}
