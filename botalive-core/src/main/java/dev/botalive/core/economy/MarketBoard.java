package dev.botalive.core.economy;

import dev.botalive.api.bot.Bot;
import dev.botalive.core.bot.BotManagerImpl;
import dev.botalive.core.util.BlockPos;
import org.bukkit.Material;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import java.util.function.Predicate;

/**
 * Tržiště botů – sdílená nástěnka nabídek a rozjednaných obchodů.
 *
 * <p>Chat je jen prezentace („prodávám chleba, kdo chce?"); pravda o
 * nabídkách žije tady, takže se boti nedomlouvají křehkým parsováním
 * vlastních zpráv. Prodejce vyvěsí nabídku ({@link #post}), zájemce si ji
 * zamluví ({@link #claim} – první bere) a dojde si pro zboží; peníze se
 * převádějí až při předávce ({@link #settle}), takže nikdo nepřijde o nic,
 * když druhá strana cestou umře nebo si to rozmyslí.</p>
 *
 * <p>Jeden prodejce má nejvýš jednu nabídku; staré nabídky i skomírající
 * obchody se čistí TTL. Mutace jsou {@code synchronized} (nízký provoz,
 * krátké operace) – stejný vzor jako {@code SettlementService}.</p>
 */
public final class MarketBoard {

    /** Jak dlouho visí nabídka bez zájemce. */
    private static final long OFFER_TTL_MS = 4 * 60_000L;
    /** Jak dlouho smí zájemci trvat cesta pro zboží. */
    private static final long DEAL_TTL_MS = 90_000L;

    /**
     * Nabídka na tržišti.
     *
     * @param id         id nabídky
     * @param seller     prodejce
     * @param sellerName jméno prodejce (pro chat kupce)
     * @param world      svět
     * @param pos        kde prodejce stojí (kupec jde sem)
     * @param material   zboží
     * @param count      počet kusů
     * @param price      plná cena (kamarádi dostanou slevu při předávce)
     * @param atMs       čas vyvěšení
     */
    public record Offer(long id, UUID seller, String sellerName, String world,
                        BlockPos pos, Material material, int count, double price,
                        long atMs) {
    }

    /**
     * Zamluvený obchod – kupec je na cestě.
     *
     * @param offer     nabídka
     * @param buyer     kupec
     * @param buyerName jméno kupce (pro chat prodejce)
     * @param claimedAt čas zamluvení
     */
    public record Deal(Offer offer, UUID buyer, String buyerName, long claimedAt) {
    }

    private final Map<UUID, Offer> offersBySeller = new HashMap<>();
    private final Map<UUID, Deal> dealsBySeller = new HashMap<>();
    private final AtomicLong nextId = new AtomicLong(1);
    private final LongSupplier clock;
    private volatile BotManagerImpl botManager;

    /** Vytvoří tržiště. */
    public MarketBoard() {
        this(System::currentTimeMillis);
    }

    MarketBoard(LongSupplier clock) {
        this.clock = clock;
    }

    /** Připojí manager botů (převody peněz při předávce). */
    public void attach(BotManagerImpl manager) {
        this.botManager = manager;
    }

    /**
     * Vyvěsí nabídku (nahrazuje předchozí nabídku téhož prodejce).
     *
     * @return vyvěšená nabídka
     */
    public synchronized Offer post(UUID seller, String sellerName, String world,
                                   BlockPos pos, Material material, int count,
                                   double price) {
        prune();
        Offer offer = new Offer(nextId.getAndIncrement(), seller, sellerName, world,
                pos, material, count, price, clock.getAsLong());
        offersBySeller.put(seller, offer);
        return offer;
    }

    /**
     * Najde nejbližší volnou nabídku, která zájemci dává smysl.
     *
     * @param world  svět zájemce
     * @param near   pozice zájemce
     * @param radius dosah (bloky)
     * @param buyer  zájemce (vlastní nabídky se přeskakují)
     * @param filter co zájemce chce
     * @return nejbližší vyhovující nabídka
     */
    public synchronized Optional<Offer> findNearby(String world, BlockPos near,
                                                   int radius, UUID buyer,
                                                   Predicate<Offer> filter) {
        prune();
        double bestSq = (double) radius * radius;
        Offer best = null;
        for (Offer offer : offersBySeller.values()) {
            if (offer.seller().equals(buyer) || !offer.world().equals(world)
                    || dealsBySeller.containsKey(offer.seller())
                    || !filter.test(offer)) {
                continue;
            }
            double distSq = offer.pos().distanceSquared(near);
            if (distSq <= bestSq) {
                bestSq = distSq;
                best = offer;
            }
        }
        return Optional.ofNullable(best);
    }

    /**
     * Zamluví nabídku (první bere, nabídka zmizí z nástěnky).
     *
     * @return {@code true} když nabídka ještě platila
     */
    public synchronized boolean claim(long offerId, UUID buyer, String buyerName) {
        prune();
        for (Offer offer : offersBySeller.values()) {
            if (offer.id() == offerId) {
                if (dealsBySeller.containsKey(offer.seller())) {
                    return false;
                }
                offersBySeller.remove(offer.seller());
                dealsBySeller.put(offer.seller(),
                        new Deal(offer, buyer, buyerName, clock.getAsLong()));
                return true;
            }
        }
        return false;
    }

    /** @return rozjednaný obchod prodejce (kupec je na cestě) */
    public synchronized Optional<Deal> pendingDeal(UUID seller) {
        prune();
        return Optional.ofNullable(dealsBySeller.get(seller));
    }

    /**
     * Zaplacení při předávce: kupec platí, prodejce inkasuje. Volá prodejce,
     * až kupec stojí vedle něj – při nedostatku peněz se nic nepřevede.
     *
     * @param deal  obchod
     * @param price finální cena (případně kamarádská)
     * @return {@code true} když peníze přetekly
     */
    public boolean settle(Deal deal, double price) {
        BotManagerImpl manager = botManager;
        if (manager == null) {
            return false;
        }
        Optional<Bot> buyer = manager.byId(deal.buyer());
        Optional<Bot> seller = manager.byId(deal.offer().seller());
        if (buyer.isEmpty() || seller.isEmpty()) {
            return false;
        }
        String what = deal.offer().count() + "x "
                + deal.offer().material().name().toLowerCase(java.util.Locale.ROOT);
        if (!buyer.get().wallet().withdraw(price, "trh: nákup " + what)) {
            return false;
        }
        seller.get().wallet().deposit(price, "trh: prodej " + what);
        return true;
    }

    /** Uzavře obchod (po předání zboží). */
    public synchronized void completeDeal(UUID seller) {
        dealsBySeller.remove(seller);
    }

    /** Stáhne nabídku i rozjednaný obchod prodejce. */
    public synchronized void withdraw(UUID seller) {
        offersBySeller.remove(seller);
        dealsBySeller.remove(seller);
    }

    /** @return má prodejce rozjednaný obchod? (pro kupcovo čekání) */
    public synchronized boolean hasDeal(UUID seller) {
        prune();
        return dealsBySeller.containsKey(seller);
    }

    private void prune() {
        long now = clock.getAsLong();
        offersBySeller.values().removeIf(o -> now - o.atMs() > OFFER_TTL_MS);
        dealsBySeller.values().removeIf(d -> now - d.claimedAt() > DEAL_TTL_MS);
    }
}
