package dev.botalive.api.role;

import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * Definice profese (role) jako data – umožňuje cizím pluginům přidat vlastní
 * povolání bez uzavřeného enumu {@link BotRole}.
 *
 * <p>Role je <b>zaměření, ne klec</b>: {@link #goalWeights()} násobí užitečnost
 * souvisejících AI cílů (podle jejich {@code id}), takže se jim bot věnuje
 * častěji, ale pořád jí, spí, bojuje a socializuje se. Konvence je držet váhy
 * {@code >= 1.0} (role chování vychyluje, netlumí), ale striktně se to
 * nevynucuje. Váhy smí mířit i na cíle cizího pluginu registrované přes
 * {@link dev.botalive.api.ai.GoalRegistry}.</p>
 *
 * @param id          stabilní identifikátor (např. {@code "necromancer"});
 *                    normalizuje se na malá písmena, nesmí být prázdný
 * @param displayName lidsky čitelný název pro výpisy
 * @param goalWeights mapa {@code id cíle → násobič užitečnosti}; nemodifikuje se
 */
public record RoleDefinition(String id, String displayName, Map<String, Double> goalWeights) {

    /** Normalizace a obranná kopie. */
    public RoleDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(displayName, "displayName");
        Objects.requireNonNull(goalWeights, "goalWeights");
        id = id.trim().toLowerCase(Locale.ROOT);
        if (id.isBlank()) {
            throw new IllegalArgumentException("id role nesmí být prázdné");
        }
        goalWeights = Map.copyOf(goalWeights);
    }

    /**
     * @param goalId id cíle
     * @return násobič užitečnosti pro daný cíl ({@code 1.0} pro cíle mimo profil)
     */
    public double weight(String goalId) {
        return goalWeights.getOrDefault(goalId, 1.0);
    }
}
