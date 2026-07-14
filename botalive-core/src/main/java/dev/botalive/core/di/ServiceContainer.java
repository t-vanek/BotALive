package dev.botalive.core.di;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Minimalistický DI kontejner (service locator s explicitní registrací).
 *
 * <p>Záměrně nepoužíváme reflexní framework (Guice/Spring): kompoziční kořen
 * pluginu je jeden ({@code CompositionRoot}), explicitní konstrukce je čitelnější,
 * rychlejší při startu, funguje bez problémů se shadow relokací a chyby zapojení
 * se projeví okamžitě při startu, ne líně za běhu.</p>
 *
 * <p>Kontejner je zamýšlen jen pro fázi bootstrapu a příkazy; runtime komponenty
 * dostávají závislosti konstruktorem (constructor injection).</p>
 */
public final class ServiceContainer {

    private final Map<Class<?>, Object> services = new LinkedHashMap<>();

    /**
     * Zaregistruje singleton službu.
     *
     * @param type     typ, pod kterým se služba vyhledává
     * @param instance instance služby
     * @param <T>      typ služby
     * @return tatáž instance (pro plynulé řetězení)
     * @throws IllegalStateException při dvojité registraci stejného typu
     */
    public <T> T register(Class<T> type, T instance) {
        Objects.requireNonNull(instance, "instance");
        if (services.putIfAbsent(type, instance) != null) {
            throw new IllegalStateException("Služba už je registrována: " + type.getName());
        }
        return instance;
    }

    /**
     * @param type typ služby
     * @param <T>  typ služby
     * @return registrovaná instance
     * @throws IllegalStateException pokud služba není registrována
     */
    public <T> T get(Class<T> type) {
        Object service = services.get(type);
        if (service == null) {
            throw new IllegalStateException("Služba není registrována: " + type.getName());
        }
        return type.cast(service);
    }

    /**
     * @return všechny registrované služby v pořadí registrace (pro řízené vypnutí)
     */
    public Iterable<Object> allInRegistrationOrder() {
        return services.values();
    }
}
