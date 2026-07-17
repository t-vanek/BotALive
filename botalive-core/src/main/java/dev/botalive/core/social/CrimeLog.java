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
 */
public final class CrimeLog {

    /** Jak dlouho po činu se dá krádež odhalit (ms). */
    private static final long MEMORY_MS = 60 * 60 * 1000;

    /**
     * Jedna krádež.
     *
     * @param world     svět truhly
     * @param chest     pozice truhly
     * @param thief     UUID pachatele
     * @param thiefName jméno pachatele (pro nadávky v chatu)
     * @param atMs      čas činu
     * @param seenBy    kdo už krádež odhalil (aby se nezuřilo opakovaně)
     */
    public record Theft(String world, BlockPos chest, UUID thief, String thiefName,
                        long atMs, Set<UUID> seenBy) {
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
        thefts.add(new Theft(world, chest, thief, thiefName,
                System.currentTimeMillis(), ConcurrentHashMap.newKeySet()));
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

    private void prune() {
        long cutoff = System.currentTimeMillis() - MEMORY_MS;
        thefts.removeIf(theft -> theft.atMs() < cutoff);
    }
}
