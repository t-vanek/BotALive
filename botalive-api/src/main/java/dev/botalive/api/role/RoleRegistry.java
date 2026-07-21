package dev.botalive.api.role;

import java.util.Collection;
import java.util.Optional;

/**
 * Registr profesí (rolí) botů.
 *
 * <p>Vestavěné role (viz {@link BotRole}) jsou předregistrované; cizí plugin
 * může přidat vlastní přes {@link #register(RoleDefinition)}. Roli pak přiřadí
 * botovi přes {@link dev.botalive.api.bot.Bot#assignRole(String)} a mozek
 * automaticky zohlední její váhy cílů.</p>
 *
 * <p>Automatický výběr role při spawnu ({@code bots.random-roles}) vybírá
 * zatím jen z vestavěných rolí – cizí role se přiřazují explicitně.</p>
 */
public interface RoleRegistry {

    /**
     * Zaregistruje profesi.
     *
     * @param role definice role
     * @throws IllegalArgumentException pokud je {@code id} již registrováno
     *         (včetně vestavěných rolí)
     */
    void register(RoleDefinition role);

    /**
     * Odregistruje cizí profesi. Vestavěné role odebrat nelze.
     *
     * @param id id role (case-insensitive)
     * @return {@code true} pokud byla odebrána
     */
    boolean unregister(String id);

    /**
     * @param id id role (case-insensitive)
     * @return definice role, pokud existuje
     */
    Optional<RoleDefinition> byId(String id);

    /**
     * @return všechny registrované role (vestavěné i cizí)
     */
    Collection<RoleDefinition> all();

    /**
     * Váha cíle pro danou roli.
     *
     * @param roleId id role ({@code null}, prázdné nebo {@code "none"} = univerzál)
     * @param goalId id cíle
     * @return násobič užitečnosti ({@code 1.0} pro neznámou roli/cíl mimo profil)
     */
    double weight(String roleId, String goalId);
}
