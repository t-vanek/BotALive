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
 * migraci v10 (sloupce {@code state/needed/contributed}), upsert a čtení
 * včetně životního cyklu a rozpisu materiálu (BOM).
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

            // Rozestavěná radnice: fáze SUPPLY, BOM 71, nasbíráno 30.
            repo.upsertSettlementProject(42L, "TOWN_HALL", 5, 10, 64, -20,
                    "NORTH", false, "SUPPLY", 71, 30).join();
            SettlementProjectRow row = one(repo);
            assertEquals("TOWN_HALL", row.kind());
            assertFalse(row.done(), "rozestavěná není hotová");
            assertEquals("SUPPLY", row.state(), "stav se uložil");
            assertEquals(71, row.needed(), "BOM se uložil");
            assertEquals(30, row.contributed(), "příspěvky se uložily");
            assertEquals(-20, row.z(), "origin sedí");

            // Upsert téhož klíče přepíše: BUILD, doplněno na 71.
            repo.upsertSettlementProject(42L, "TOWN_HALL", 5, 10, 64, -20,
                    "NORTH", false, "BUILD", 71, 71).join();
            row = one(repo);
            assertEquals("BUILD", row.state(), "upsert přepsal stav");
            assertEquals(71, row.contributed(), "upsert přepsal příspěvky");

            // Dokončení.
            repo.upsertSettlementProject(42L, "TOWN_HALL", 5, 10, 64, -20,
                    "NORTH", true, "DONE", 71, 71).join();
            row = one(repo);
            assertEquals(true, row.done(), "dokončení se uložilo");
            assertEquals("DONE", row.state());
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
