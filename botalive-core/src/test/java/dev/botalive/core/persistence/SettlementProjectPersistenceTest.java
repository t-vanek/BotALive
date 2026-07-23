package dev.botalive.core.persistence;

import dev.botalive.core.config.BotAliveConfig;
import dev.botalive.core.persistence.BotRepository.SettlementProjectRow;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Round-trip společných staveb nad reálnou SQLite (dočasný soubor) – ověřuje
 * migraci v10 (sloupce {@code state/needed/contributed}) a v11 (velikost
 * {@code width/depth/wall_height}), upsert a čtení včetně životního cyklu,
 * rozpisu materiálu (BOM) a persistované velikosti (idempotentní resume + růst).
 */
class SettlementProjectPersistenceTest {

    private static BotAliveConfig.Persistence sqlite() {
        return new BotAliveConfig.Persistence("sqlite", "test.db",
                "", 0, "", "", "", 0, 60);
    }

    @Test
    void projektRoundTripVcetneStavuABOM(@TempDir File dir) {
        Database db = new Database(sqlite(), dir);
        try {
            new SchemaMigrator(db).migrate();
            BotRepository repo = new BotRepository(db);

            // Rozestavěná radnice velikosti 7×7×6 (město): fáze SUPPLY, BOM 71, 30.
            repo.upsertSettlementProject(42L, "TOWN_HALL", 5, 10, 64, -20,
                    "NORTH", false, "SUPPLY", 71, 30, 7, 7, 6).join();
            SettlementProjectRow row = one(repo);
            assertEquals("TOWN_HALL", row.kind());
            assertFalse(row.done(), "rozestavěná není hotová");
            assertEquals("SUPPLY", row.state(), "stav se uložil");
            assertEquals(71, row.needed(), "BOM se uložil");
            assertEquals(30, row.contributed(), "příspěvky se uložily");
            assertEquals(-20, row.z(), "origin sedí");
            // Velikost se uložila (idempotentní resume postaví TÝŽ tvar).
            assertEquals(7, row.width(), "šířka se uložila");
            assertEquals(7, row.depth(), "hloubka se uložila");
            assertEquals(6, row.wallHeight(), "výška se uložila");

            // Upsert téhož klíče přepíše: BUILD, doplněno na 71, velikost drží.
            repo.upsertSettlementProject(42L, "TOWN_HALL", 5, 10, 64, -20,
                    "NORTH", false, "BUILD", 71, 71, 7, 7, 6).join();
            row = one(repo);
            assertEquals("BUILD", row.state(), "upsert přepsal stav");
            assertEquals(71, row.contributed(), "upsert přepsal příspěvky");
            assertEquals(7, row.width(), "velikost přežila upsert");

            // Dokončení.
            repo.upsertSettlementProject(42L, "TOWN_HALL", 5, 10, 64, -20,
                    "NORTH", true, "DONE", 71, 71, 7, 7, 6).join();
            row = one(repo);
            assertEquals(true, row.done(), "dokončení se uložilo");
            assertEquals("DONE", row.state());
        } finally {
            db.close();
        }
    }

    @Test
    void legacyProjektBezVelikostiJe0(@TempDir File dir) {
        // Migrace v11 přidá sloupce s defaultem 0 = legacy pevná velikost;
        // starý projekt (bez velikosti) se tak načte jako legacy a drží svůj tvar.
        Database db = new Database(sqlite(), dir);
        try {
            new SchemaMigrator(db).migrate();
            BotRepository repo = new BotRepository(db);
            repo.upsertSettlementProject(1L, "WELL", 3, 0, 64, 0,
                    "NORTH", true, "DONE", 8, 8, 0, 0, 0).join();
            SettlementProjectRow row = one(repo);
            assertEquals(0, row.width(), "legacy = velikost 0");
            assertEquals(0, row.depth());
            assertEquals(0, row.wallHeight());
        } finally {
            db.close();
        }
    }

    private static SettlementProjectRow one(BotRepository repo) {
        List<SettlementProjectRow> rows = repo.loadSettlementProjects().join();
        assertEquals(1, rows.size(), "právě jeden projekt");
        return rows.getFirst();
    }
}
