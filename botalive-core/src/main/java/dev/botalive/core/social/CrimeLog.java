package dev.botalive.core.social;

import dev.botalive.core.util.BlockPos;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Kniha zločinů – krádeže mají oběti a následky.
 *
 * <p>Zloděj sem svou krádež „zapíše" (svět je malý, stopy zůstávají);
 * majitel truhly ji při další návštěvě odhalí, naštve se a zapamatuje si
 * pachatele jako nepřítele – pomsta pak žije v existujícím PvP feud
 * systému. Sdílená služba všech botů, in-memory, staré zápisy se čistí.</p>
 *
 * <p>Odhalená krádež navíc otevírá druhé dějství: pachatel o ní ví
 * ({@link #pendingAmends}) a slušný zloděj může oběti donést dar na
 * usmířenou ({@code ReconcileGoal}). Odhalené krádeže proto žijí déle než
 * neodhalené – na pokání musí být čas.</p>
 */
public final class CrimeLog {

    /** Jak dlouho po činu se dá krádež odhalit (ms). */
    private static final long MEMORY_MS = 60 * 60 * 1000;

    /** Jak dlouho po činu se dá odhalená krádež odčinit (ms). */
    private static final long AMENDS_MS = 4 * 60 * 60 * 1000;

    /**
     * Jedna krádež.
     *
     * @param world     svět truhly
     * @param chest     pozice truhly
     * @param thief     UUID pachatele
     * @param thiefName jméno pachatele (pro nadávky v chatu)
     * @param atMs      čas činu
     * @param seenBy    kdo už krádež odhalil (aby se nezuřilo opakovaně)
     * @param settledBy oběti, se kterými se pachatel pokusil vyrovnat
     *                  (dar předán – ať už přijat, nebo odmítnut)
     */
    public record Theft(String world, BlockPos chest, UUID thief, String thiefName,
                        long atMs, Set<UUID> seenBy, Set<UUID> settledBy) {
    }

    /**
     * Neurovnaná křivda: krádež a oběť, která o ní ví a zlobí se.
     *
     * @param theft  krádež
     * @param victim oběť, která krádež odhalila a dar ještě nedostala
     */
    public record Amends(Theft theft, UUID victim) {
    }

    private final List<Theft> thefts = new CopyOnWriteArrayList<>();

    /**
     * Zapíše krádež z truhly.
     *
     * @param world     svět
     * @param chest     pozice truhly
     * @param thief     pachatel
     * @param thiefName jméno pachatele
     */
    public void reportTheft(String world, BlockPos chest, UUID thief, String thiefName) {
        prune();
        thefts.add(new Theft(world, chest, thief, thiefName, System.currentTimeMillis(),
                ConcurrentHashMap.newKeySet(), ConcurrentHashMap.newKeySet()));
    }

    /**
     * Odhalí dosud neviděnou krádež z dané truhly (a označí ji za viděnou).
     *
     * @param world  svět truhly
     * @param chest  pozice truhly
     * @param victim kdo se dívá (vlastní krádeže se nehlásí)
     * @return krádež k odhalení, nebo empty
     */
    public Optional<Theft> discoverTheft(String world, BlockPos chest, UUID victim) {
        prune();
        for (Theft theft : thefts) {
            if (theft.world().equals(world) && theft.chest().equals(chest)
                    && !theft.thief().equals(victim) && theft.seenBy().add(victim)) {
                return Optional.of(theft);
            }
        }
        return Optional.empty();
    }

    /**
     * První neurovnaná křivda pachatele: krádež, kterou někdo odhalil a
     * pachatel se s obětí ještě nepokusil vyrovnat.
     *
     * @param thief pachatel
     * @return křivda k odčinění, nebo empty (svědomí má čisto)
     */
    public Optional<Amends> pendingAmends(UUID thief) {
        prune();
        for (Theft theft : thefts) {
            if (!theft.thief().equals(thief)) {
                continue;
            }
            for (UUID victim : theft.seenBy()) {
                if (!theft.settledBy().contains(victim)) {
                    return Optional.of(new Amends(theft, victim));
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Označí křivdu vůči oběti za vyrovnanou – dar byl předán. Platí i pro
     * odmítnutý dar: co bylo předáno, je předáno, druhý pokus nebude.
     *
     * @param amends křivda z {@link #pendingAmends}
     */
    public void settleAmends(Amends amends) {
        amends.theft().settledBy().add(amends.victim());
    }

    private void prune() {
        long now = System.currentTimeMillis();
        // Odhalené krádeže žijí déle – pachatel má čas na pokání.
        thefts.removeIf(theft -> theft.atMs()
                < now - (theft.seenBy().isEmpty() ? MEMORY_MS : AMENDS_MS));
    }
}
