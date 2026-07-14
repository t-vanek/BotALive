package dev.botalive.core.entity;

import dev.botalive.core.util.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

/**
 * Registr entit viditelných jedním botem (klientský pohled na svět entit).
 *
 * <p>Plní ho síťová vrstva z Add/Remove/Move/Teleport paketů; čte ho AI
 * (výběr cílů boje, sběr itemů, sociální chování). Thread-safe.</p>
 */
public final class EntityTracker {

    private final Map<Integer, TrackedEntity> byId = new ConcurrentHashMap<>();

    /**
     * Přidá/nahradí entitu.
     *
     * @param entity nová entita
     */
    public void add(TrackedEntity entity) {
        byId.put(entity.entityId(), entity);
    }

    /**
     * Odebere entity podle síťových id.
     *
     * @param entityIds id odebraných entit
     */
    public void remove(int[] entityIds) {
        for (int id : entityIds) {
            byId.remove(id);
        }
    }

    /** Vyprázdní tracker (respawn/změna světa). */
    public void clear() {
        byId.clear();
    }

    /**
     * @param entityId síťové id
     * @return entita, pokud je sledovaná
     */
    public Optional<TrackedEntity> byId(int entityId) {
        return Optional.ofNullable(byId.get(entityId));
    }

    /**
     * @param uuid UUID entity
     * @return entita, pokud je sledovaná
     */
    public Optional<TrackedEntity> byUuid(UUID uuid) {
        return byId.values().stream().filter(e -> uuid.equals(e.uuid())).findFirst();
    }

    /**
     * Najde nejbližší entitu splňující filtr.
     *
     * @param from    referenční bod
     * @param maxDist maximální vzdálenost
     * @param filter  filtr entit
     * @return nejbližší vyhovující entita
     */
    public Optional<TrackedEntity> nearest(Vec3 from, double maxDist, Predicate<TrackedEntity> filter) {
        double maxSq = maxDist * maxDist;
        return byId.values().stream()
                .filter(filter)
                .filter(e -> e.position().distanceSquared(from) <= maxSq)
                .min(Comparator.comparingDouble(e -> e.position().distanceSquared(from)));
    }

    /**
     * Vrátí všechny entity splňující filtr do dané vzdálenosti, seřazené od nejbližší.
     *
     * @param from    referenční bod
     * @param maxDist maximální vzdálenost
     * @param filter  filtr entit
     * @return seznam entit
     */
    public List<TrackedEntity> nearby(Vec3 from, double maxDist, Predicate<TrackedEntity> filter) {
        double maxSq = maxDist * maxDist;
        return byId.values().stream()
                .filter(filter)
                .filter(e -> e.position().distanceSquared(from) <= maxSq)
                .sorted(Comparator.comparingDouble(e -> e.position().distanceSquared(from)))
                .toList();
    }

    /** @return počet sledovaných entit */
    public int size() {
        return byId.size();
    }
}
