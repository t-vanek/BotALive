package dev.botalive.core.role;

import dev.botalive.api.role.BotRole;

import java.util.Map;

/**
 * Profily rolí – jak profese ovlivňuje utility AI.
 *
 * <p>Role je násobič užitečnosti souvisejících cílů: kovář taví 2.5× ochotněji,
 * ale pořád jí, spí a brání se. Cíle mimo profil mají váhu 1.0 – role chování
 * <b>vychyluje</b>, nikdy ho nevypíná. Kombinace s osobností (která vstupuje
 * do samotných utility funkcí) dává botům dvojí individualitu: dva kopáči
 * s jinou povahou kopou jinak často a jinak dlouho.</p>
 */
public final class RoleProfiles {

    private static final Map<BotRole, Map<String, Double>> WEIGHTS = Map.of(
            BotRole.BUILDER, Map.of(
                    "shelter", 2.5, "house", 3.0, "craft", 1.4, "mine", 1.3, "home", 1.3,
                    "maintain", 2.2),
            BotRole.MINER, Map.of(
                    "mine", 2.5, "collect", 1.3, "craft", 1.2, "smelt", 1.3),
            BotRole.LUMBERJACK, Map.of(
                    "mine", 2.2, "craft", 1.4, "shelter", 1.2, "house", 1.4),
            BotRole.HUNTER, Map.of(
                    "hunt", 2.5, "combat", 1.4, "collect", 1.2, "tame", 1.5,
                    "guard", 2.2),
            BotRole.BLACKSMITH, Map.of(
                    "smelt", 2.5, "craft", 1.6, "mine", 1.4, "repair", 2.2),
            BotRole.ENCHANTER, Map.of(
                    "enchant", 2.5, "mine", 1.2, "smelt", 1.1),
            BotRole.TRADER, Map.of(
                    "trade", 2.5, "farm", 1.4, "stash", 1.4, "sell", 2.2, "buy", 1.5),
            BotRole.FISHERMAN, Map.of(
                    "fish", 2.6, "boat", 1.4, "sell", 1.4),
            BotRole.FARMER, Map.of(
                    "farm", 2.5, "trade", 1.3, "craft", 1.1, "share", 1.5, "compost", 2.0,
                    "sell", 1.6)
    );

    private RoleProfiles() {
    }

    /**
     * Váha cíle pro danou roli.
     *
     * @param role   role bota
     * @param goalId id cíle
     * @return násobič užitečnosti (1.0 pro cíle mimo profil a roli NONE)
     */
    public static double weight(BotRole role, String goalId) {
        if (role == null || role == BotRole.NONE) {
            return 1.0;
        }
        Map<String, Double> weights = WEIGHTS.get(role);
        if (weights == null) {
            return 1.0;
        }
        return weights.getOrDefault(goalId, 1.0);
    }

    /**
     * @param role role bota
     * @return {@code true} pokud má bot při těžbě preferovat dřevo před rudami
     */
    public static boolean prefersLogs(BotRole role) {
        return role == BotRole.LUMBERJACK;
    }
}
