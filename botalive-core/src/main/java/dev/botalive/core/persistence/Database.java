package dev.botalive.core.persistence;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import dev.botalive.core.config.BotAliveConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

/**
 * Správa databázového připojení (HikariCP) a asynchronního přístupu.
 *
 * <p><b>SQLite</b> (výchozí): pool velikosti 1 – SQLite má jednoho zapisovatele
 * a serializace přes pool eliminuje {@code SQLITE_BUSY}; WAL režim drží čtení
 * rychlé. <b>PostgreSQL</b> (volitelné): plnohodnotný pool, vhodné pro velké
 * množství botů nebo sdílenou databázi.</p>
 *
 * <p>Všechny operace jdou přes {@link #async(Function)} na dedikovaném DB
 * vlákně – herní ani AI vlákna nikdy nečekají na disk.</p>
 */
public final class Database implements AutoCloseable {

    private static final Logger LOG = LoggerFactory.getLogger(Database.class);

    private final HikariDataSource dataSource;
    private final SqlDialect dialect;
    private final ExecutorService dbExecutor;

    /**
     * @param config     konfigurace persistence
     * @param dataFolder datová složka pluginu (pro SQLite soubor)
     */
    public Database(BotAliveConfig.Persistence config, File dataFolder) {
        HikariConfig hikari = new HikariConfig();
        hikari.setPoolName("BotAlive-DB");

        if ("postgresql".equals(config.type())) {
            this.dialect = SqlDialect.POSTGRESQL;
            hikari.setJdbcUrl("jdbc:postgresql://" + config.pgHost() + ":" + config.pgPort()
                    + "/" + config.pgDatabase());
            hikari.setUsername(config.pgUser());
            hikari.setPassword(config.pgPassword());
            hikari.setMaximumPoolSize(Math.max(2, config.pgPoolSize()));
        } else {
            this.dialect = SqlDialect.SQLITE;
            File file = new File(dataFolder, config.sqliteFile());
            //noinspection ResultOfMethodCallIgnored
            file.getParentFile().mkdirs();
            hikari.setJdbcUrl("jdbc:sqlite:" + file.getAbsolutePath());
            hikari.setMaximumPoolSize(1);
            // Pragmata přes driver properties – sqlite-jdbc je aplikuje při otevření spojení.
            hikari.addDataSourceProperty("journal_mode", "WAL");
            hikari.addDataSourceProperty("busy_timeout", "5000");
            hikari.addDataSourceProperty("synchronous", "NORMAL");
            hikari.addDataSourceProperty("foreign_keys", "true");
        }
        hikari.setConnectionTimeout(10_000);
        this.dataSource = new HikariDataSource(hikari);
        this.dbExecutor = Executors.newSingleThreadExecutor(r -> {
            Thread t = new Thread(r, "BotAlive-DB");
            t.setDaemon(true);
            return t;
        });
        LOG.info("Databáze připojena ({})", dialect);
    }

    /** @return aktivní SQL dialekt */
    public SqlDialect dialect() {
        return dialect;
    }

    /**
     * Provede databázovou operaci asynchronně na DB vlákně.
     *
     * @param operation operace nad připojením
     * @param <T>       typ výsledku
     * @return future s výsledkem; při SQL chybě selže a chyba se zaloguje
     */
    public <T> CompletableFuture<T> async(Function<Connection, T> operation) {
        return CompletableFuture.supplyAsync(() -> {
            try (Connection connection = dataSource.getConnection()) {
                return operation.apply(connection);
            } catch (SQLException e) {
                throw new IllegalStateException("Databázová operace selhala", e);
            }
        }, dbExecutor).whenComplete((r, t) -> {
            if (t != null) {
                LOG.error("Chyba databázové operace", t);
            }
        });
    }

    /**
     * Synchronní varianta pro bootstrap (migrace) – volat jen při startu/vypnutí.
     *
     * @param operation operace nad připojením
     * @param <T>       typ výsledku
     * @return výsledek operace
     */
    public <T> T sync(Function<Connection, T> operation) {
        try (Connection connection = dataSource.getConnection()) {
            return operation.apply(connection);
        } catch (SQLException e) {
            throw new IllegalStateException("Databázová operace selhala", e);
        }
    }

    @Override
    public void close() {
        dbExecutor.shutdown();
        try {
            if (!dbExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                LOG.warn("DB fronta se nestihla vyprázdnit, vynucené ukončení");
                dbExecutor.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        dataSource.close();
    }
}
