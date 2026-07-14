package dev.botalive.core.persistence;

/**
 * Rozdíly SQL dialektů podporovaných databází.
 *
 * <p>Schéma je psané v průniku obou dialektů; liší se jen autoinkrement a
 * upsert syntaxe (obojí zde).</p>
 */
public enum SqlDialect {

    /** Výchozí embedded databáze – nulová konfigurace. */
    SQLITE,

    /** Produkční volba pro více serverů / velké množství botů. */
    POSTGRESQL;

    /** @return DDL fragment autoinkrementního primárního klíče */
    public String autoIncrementPk() {
        return this == SQLITE
                ? "INTEGER PRIMARY KEY AUTOINCREMENT"
                : "BIGSERIAL PRIMARY KEY";
    }

    /**
     * Upsert – oba dialekty podporují {@code ON CONFLICT}, PostgreSQL vyžaduje
     * explicitní sloupce v {@code DO UPDATE SET}.
     *
     * @param conflictColumn sloupec s unikátním omezením
     * @param updateSet      část {@code SET a=excluded.a, ...}
     * @return SQL fragment za {@code INSERT ... VALUES (...)}
     */
    public String upsertSuffix(String conflictColumn, String updateSet) {
        return " ON CONFLICT(" + conflictColumn + ") DO UPDATE SET " + updateSet;
    }
}
