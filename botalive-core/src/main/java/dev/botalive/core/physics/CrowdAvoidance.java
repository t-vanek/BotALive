package dev.botalive.core.physics;

import dev.botalive.core.entity.TrackedEntity;
import dev.botalive.core.util.Vec3;

import java.util.List;

/**
 * Vyhýbání davu (separační steering) mezi boty a hráči.
 *
 * <p>Klientská fyzika bota ({@link BotPhysics}) řeší jen kolize s bloky – ostatní
 * boti pro ni neexistují, takže víc botů dojde na stejný cíl a slije se do jednoho
 * místa. Tahle třída přidá do žádaného směru chůze jemnou odpudivou složku od
 * blízkých hráčů/botů (boids „separation"): čím blíž soused, tím silněji.
 * Výsledkem je, že se boti drží v přirozeném odstupu a nestojí jeden v druhém,
 * aniž by ztratili směr k cíli.</p>
 *
 * <p>Vektory se počítají jen ve vodorovné rovině; svislé řešení nechává na
 * fyzice a serveru. Metoda je bezstavová a vlákno-bezpečná.</p>
 */
public final class CrowdAvoidance {

    /** Do jaké vzdálenosti (bloky) se boti navzájem odpuzují. */
    private static final double RADIUS = 1.5;
    private static final double RADIUS_SQ = RADIUS * RADIUS;
    /** Váha odpudivé složky vůči žádanému směru chůze. */
    private static final double SEPARATION_WEIGHT = 2.0;
    /** Zlatý úhel (rad) pro deterministické rozbití symetrie při přesném překryvu. */
    private static final double GOLDEN_ANGLE = 2.399963229728653;
    private static final double EPS = 1.0E-4;

    private CrowdAvoidance() {
    }

    /** @return poloměr (bloky), v němž se boti odpuzují – i dosah dotazu na sousedy */
    public static double radius() {
        return RADIUS;
    }

    /**
     * Upraví směr chůze tak, aby se bot odpuzoval od blízkých hráčů/botů.
     *
     * @param self      pozice bota (nohy)
     * @param selfId    entity id bota (rozbití symetrie při přesném překryvu)
     * @param neighbors okolní entity (volající je filtruje na hráče/boty)
     * @param desired   žádaný jednotkový vodorovný směr chůze, nebo {@link Vec3#ZERO}
     * @return upravený jednotkový vodorovný směr, nebo {@link Vec3#ZERO}
     */
    public static Vec3 steer(Vec3 self, int selfId, List<TrackedEntity> neighbors, Vec3 desired) {
        Vec3 push = Vec3.ZERO;
        int count = 0;
        for (TrackedEntity neighbor : neighbors) {
            Vec3 other = neighbor.position();
            if (other == null) {
                continue;
            }
            double dx = self.x() - other.x();
            double dz = self.z() - other.z();
            double distSq = dx * dx + dz * dz;
            if (distSq > RADIUS_SQ) {
                continue;
            }
            double dist = Math.sqrt(distSq);
            // Lineární útlum: 1 v těsném kontaktu, 0 na okraji poloměru.
            double strength = (RADIUS - dist) / RADIUS;
            Vec3 away;
            if (dist < 1.0E-3) {
                // Přesný překryv – rozraž symetrii deterministicky podle id,
                // ať dva boti na stejném místě neutečou na tutéž stranu.
                double angle = selfId * GOLDEN_ANGLE;
                away = new Vec3(Math.cos(angle), 0, Math.sin(angle));
            } else {
                away = new Vec3(dx / dist, 0, dz / dist);
            }
            push = push.add(away.mul(strength));
            count++;
        }
        if (count == 0 || push.horizontalLength() < EPS) {
            return desired;
        }
        // Čelní blok: odpuzování míří téměř proti směru chůze → prosté smíchání
        // by bota zastavilo a oba by se donekonečna přetlačovali. Místo toho
        // uhnout do strany (deterministicky podle id) a soupeře obejít.
        if (desired.horizontalLength() > EPS) {
            Vec3 d = desired.horizontal().normalized();
            Vec3 p = push.normalized();
            double dot = d.x() * p.x() + d.z() * p.z();
            if (dot < -0.75) {
                double side = (selfId & 1) == 0 ? 1 : -1;
                Vec3 slide = new Vec3(-d.z() * side, 0, d.x() * side);
                return d.mul(0.4).add(slide).horizontal().normalized();
            }
        }
        Vec3 separation = push.normalized().mul(SEPARATION_WEIGHT);
        Vec3 blended = desired.add(separation);
        return blended.horizontalLength() < EPS ? Vec3.ZERO : blended.horizontal().normalized();
    }
}
