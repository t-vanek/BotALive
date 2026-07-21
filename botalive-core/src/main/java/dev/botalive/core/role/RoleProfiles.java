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

    // Map.ofEntries, ne Map.of – to má strop 10 dvojic a rolí je víc.
    private static final Map<BotRole, Map<String, Double>> WEIGHTS = Map.ofEntries(
            Map.entry(BotRole.BUILDER, Map.of(
                    "shelter", 2.5, "house", 3.0, "communal-build", 2.5,
                    "craft", 1.4, "mine", 1.3, "home", 1.3,
                    "maintain", 2.2)),
            Map.entry(BotRole.MINER, Map.of(
                    "mine", 2.5, "collect", 1.3, "craft", 1.2, "smelt", 1.3,
                    "nether", 1.6, "smith", 1.4, "end-harvest", 1.4)),
            Map.entry(BotRole.LUMBERJACK, Map.of(
                    "mine", 2.2, "craft", 1.4, "shelter", 1.2, "house", 1.4)),
            Map.entry(BotRole.HUNTER, Map.of(
                    "hunt", 2.5, "combat", 1.4, "collect", 1.2, "tame", 1.5,
                    "guard", 2.2, "nether", 1.3, "end-travel", 1.4,
                    "dragon-fight", 1.5, "end-harvest", 1.3)),
            Map.entry(BotRole.BLACKSMITH, Map.of(
                    "smelt", 2.5, "craft", 1.6, "mine", 1.4, "repair", 2.2,
                    "smith", 2.2, "nether", 1.2)),
            Map.entry(BotRole.ENCHANTER, Map.of(
                    "enchant", 2.5, "mine", 1.2, "smelt", 1.1)),
            Map.entry(BotRole.TRADER, Map.of(
                    "trade", 2.5, "farm", 1.4, "stash", 1.4, "sell", 2.2, "buy", 1.5)),
            Map.entry(BotRole.FISHERMAN, Map.of(
                    "fish", 2.6, "boat", 1.4, "sell", 1.4)),
            Map.entry(BotRole.FARMER, Map.of(
                    "farm", 2.5, "trade", 1.3, "craft", 1.1, "share", 1.5, "compost", 2.0,
                    "sell", 1.6)),

            // ---- profese doplněné, aby hotové cíle měly svého "majitele"
            // (brew, tame, reconcile, deliver-work, camp, minecart, rob…
            // dosud nikdo netáhl a v histogramu se neobjevovaly).
            Map.entry(BotRole.ALCHEMIST, Map.of(
                    "brew", 2.5, "drink", 2.0, "nether", 1.4, "smelt", 1.2,
                    "enchant", 1.2)),
            // Pozn.: váhy jsou vždy >= 1.0 – role chování vychyluje, nikdy ho
            // netlumí (viz RoleProfilesTest). Základní potřeby (eat, survive,
            // socialize) zůstávají všem stejné.
            Map.entry(BotRole.GUARDIAN, Map.of(
                    "guard", 2.6, "bodyguard", 2.4, "combat", 1.6, "pvp", 1.4,
                    "war-raid", 1.5, "home", 1.2)),
            Map.entry(BotRole.SCOUT, Map.of(
                    "explore", 2.5, "boat", 1.6, "minecart", 1.6, "camp", 1.8,
                    "end-travel", 1.3)),
            Map.entry(BotRole.BEASTMASTER, Map.of(
                    "tame", 2.6, "hunt", 1.4, "farm", 1.2, "share", 1.3)),
            Map.entry(BotRole.THIEF, Map.of(
                    "steal", 2.5, "rob", 2.2, "escape", 1.6, "stash", 1.4)),
            Map.entry(BotRole.DIPLOMAT, Map.of(
                    "share", 2.2, "reconcile", 2.5, "trade", 1.3)),
            Map.entry(BotRole.ADVENTURER, Map.of(
                    "nether", 2.2, "end-travel", 2.0, "dragon-fight", 1.8,
                    "wither-fight", 1.8, "recover", 1.5, "explore", 1.4,
                    "end-outer", 1.5, "end-harvest", 1.4)),
            Map.entry(BotRole.COURIER, Map.of(
                    "deliver-work", 2.5, "stash", 1.8, "collect", 1.4, "trade", 1.3,
                    "minecart", 1.3)),
            Map.entry(BotRole.COOK, Map.of(
                    "smelt", 2.2, "farm", 1.4, "share", 1.8,
                    "compost", 1.5, "fish", 1.3))
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
     * Profil vah vestavěné role – zdroj pro seedování {@code RoleRegistryImpl}.
     *
     * @param role vestavěná role
     * @return mapa {@code id cíle → násobič} (prázdná pro roli bez profilu)
     */
    public static Map<String, Double> weightsFor(BotRole role) {
        Map<String, Double> weights = WEIGHTS.get(role);
        return weights == null ? Map.of() : weights;
    }

    /**
     * @param role role bota
     * @return {@code true} pokud má bot při těžbě preferovat dřevo před rudami
     */
    /**
     * Cíle, které daná profese zesiluje.
     *
     * <p>Slouží k ověření, že profil neukazuje na neexistující cíl – překlep
     * v id ({@code "deliver_work"} místo {@code "deliver-work"}) by jinak roli
     * tiše proměnil v pouhý popisek.</p>
     *
     * @param role profese
     * @return množina id cílů z profilu (prázdná pro roli bez profilu)
     */
    public static java.util.Set<String> profileGoals(BotRole role) {
        Map<String, Double> weights = role == null ? null : WEIGHTS.get(role);
        return weights == null ? java.util.Set.of() : weights.keySet();
    }

    public static boolean prefersLogs(BotRole role) {
        return role == BotRole.LUMBERJACK;
    }
}
