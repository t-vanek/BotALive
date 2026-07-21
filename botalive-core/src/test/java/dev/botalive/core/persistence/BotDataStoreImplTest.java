package dev.botalive.core.persistence;

import dev.botalive.core.config.BotAliveConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Integrační test {@link BotDataStoreImpl} nad reálnou SQLite databází
 * (dočasný soubor) – ověřuje migraci v9 ({@code ba_ext_data}), upsert, čtení
 * jmenného prostoru, mazání a izolaci mezi klíči/jmennými prostory/boty.
 */
class BotDataStoreImplTest {

    private static BotAliveConfig.Persistence sqlite() {
        return new BotAliveConfig.Persistence("sqlite", "test.db",
                "", 0, "", "", "", 0, 60);
    }

    @Test
    void putGetUpsertRemoveAndIsolation(@TempDir File dir) {
        Database db = new Database(sqlite(), dir);
        try {
            new SchemaMigrator(db).migrate();
            BotDataStoreImpl store = new BotDataStoreImpl(db);
            UUID bot = UUID.randomUUID();

            // Chybějící klíč = prázdno.
            assertEquals(Optional.empty(), store.get(bot, "myplugin", "home").join());

            // Zápis a čtení.
            store.put(bot, "myplugin", "home", "100,64,-200").join();
            assertEquals(Optional.of("100,64,-200"), store.get(bot, "myplugin", "home").join());

            // Upsert přepíše stejný klíč.
            store.put(bot, "myplugin", "home", "0,70,0").join();
            assertEquals(Optional.of("0,70,0"), store.get(bot, "myplugin", "home").join());

            // Druhý klíč + čtení celého jmenného prostoru.
            store.put(bot, "myplugin", "kills", "7").join();
            Map<String, String> ns = store.getNamespace(bot, "myplugin").join();
            assertEquals(2, ns.size());
            assertEquals("0,70,0", ns.get("home"));
            assertEquals("7", ns.get("kills"));

            // Izolace jmenných prostorů a botů.
            store.put(bot, "otherplugin", "home", "jine").join();
            assertEquals(1, store.getNamespace(bot, "otherplugin").join().size());
            assertEquals(2, store.getNamespace(bot, "myplugin").join().size());
            UUID otherBot = UUID.randomUUID();
            assertTrue(store.getNamespace(otherBot, "myplugin").join().isEmpty());

            // Mazání jednoho klíče.
            store.remove(bot, "myplugin", "home").join();
            assertEquals(Optional.empty(), store.get(bot, "myplugin", "home").join());
            assertEquals(1, store.getNamespace(bot, "myplugin").join().size());
        } finally {
            db.close();
        }
    }
}
