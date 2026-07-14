package dev.botalive.core.di;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Testy minimalistického DI kontejneru.
 */
class ServiceContainerTest {

    @Test
    void registraceAVyhledani() {
        ServiceContainer container = new ServiceContainer();
        StringBuilder service = new StringBuilder("test");

        container.register(StringBuilder.class, service);

        assertSame(service, container.get(StringBuilder.class));
    }

    @Test
    void dvojitaRegistraceSelze() {
        ServiceContainer container = new ServiceContainer();
        container.register(String.class, "první");

        assertThrows(IllegalStateException.class,
                () -> container.register(String.class, "druhá"));
    }

    @Test
    void chybejiciSluzbaSelze() {
        ServiceContainer container = new ServiceContainer();

        assertThrows(IllegalStateException.class, () -> container.get(Integer.class));
    }

    @Test
    void poradiRegistraceSeZachovava() {
        ServiceContainer container = new ServiceContainer();
        container.register(String.class, "a");
        container.register(Integer.class, 1);
        container.register(Double.class, 2.0);

        StringBuilder order = new StringBuilder();
        container.allInRegistrationOrder().forEach(s -> order.append(s).append(","));

        assertEquals("a,1,2.0,", order.toString());
    }
}
