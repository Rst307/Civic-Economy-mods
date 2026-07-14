package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void firstOpenCreatesAWorldBoundWalDatabaseThatCanReopen() {
        Path databaseFile = temporaryDirectory.resolve("civic-economy.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("4b617458-7f03-4fd2-a94e-4dc37ecbd682"),
                "0.1.0",
                "1.21-2.3.0.5",
                "2101.1.10",
                "2101.1.20");

        try (CivicDatabase database = CivicDatabase.open(databaseFile, identity)) {
            assertEquals("wal", database.journalMode());
            assertEquals(14, database.schemaVersion());
            assertEquals(identity, database.identity());
        }

        try (CivicDatabase reopened = CivicDatabase.open(databaseFile, identity)) {
            assertEquals("wal", reopened.journalMode());
            assertEquals(14, reopened.schemaVersion());
            assertEquals(identity, reopened.identity());
        }
    }

    @Test
    void openingAnotherWorldsDatabaseFailsClosed() {
        Path databaseFile = temporaryDirectory.resolve("foreign-world.sqlite3");
        DatabaseIdentity original = identity("4b617458-7f03-4fd2-a94e-4dc37ecbd682");
        try (CivicDatabase ignored = CivicDatabase.open(databaseFile, original)) {}

        DatabaseIdentity anotherWorld = identity("05a95c0a-c0c2-4280-b453-427a62494a2d");

        assertThrows(DatabaseIdentityMismatchException.class, () -> CivicDatabase.open(databaseFile, anotherWorld));
    }

    @Test
    void openingAnUnknownSchemaVersionFailsClosed() throws Exception {
        Path databaseFile = temporaryDirectory.resolve("future-schema.sqlite3");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
                Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA user_version = 99");
        }

        assertThrows(
                UnsupportedDatabaseVersionException.class,
                () -> CivicDatabase.open(databaseFile, identity("4b617458-7f03-4fd2-a94e-4dc37ecbd682")));
    }

    @Test
    void schemaThreeDatabaseMigratesThroughCurrentSchemaWithoutChangingWorldIdentity() throws Exception {
        Path databaseFile = temporaryDirectory.resolve("schema-three.sqlite3");
        DatabaseIdentity identity = identity("178a3c85-36da-4f97-9a4d-34de15e7612d");
        try (CivicDatabase ignored = CivicDatabase.open(databaseFile, identity)) {}
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + databaseFile.toAbsolutePath());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE fiscal_ledger_entry");
            statement.execute("DROP TABLE fiscal_bill");
            statement.execute("DROP TABLE fiscal_budget");
            statement.execute("DROP TABLE escrow_expiry");
            statement.execute("DROP TABLE fiscal_escrow");
            statement.execute("DROP TABLE payment_recovery_audit");
            statement.execute("DROP TABLE payment_compensation");
            statement.execute("DROP TABLE reservation_release");
            statement.execute("DROP TABLE citizenship_period");
            statement.execute("DROP TABLE nation_registry");
            statement.execute("DROP TABLE online_time_interval");
            statement.execute("DROP INDEX payment_one_incomplete_refund");
            statement.execute("ALTER TABLE payment_transaction DROP COLUMN reason");
            statement.execute("ALTER TABLE payment_transaction DROP COLUMN refunded_minor_units");
            statement.execute("ALTER TABLE payment_transaction DROP COLUMN parent_transaction_id");
            statement.execute("ALTER TABLE payment_transaction DROP COLUMN kind");
            statement.execute("ALTER TABLE fiscal_reservation DROP COLUMN settled_minor_units");
            statement.execute("PRAGMA user_version = 3");
        }

        try (CivicDatabase migrated = CivicDatabase.open(databaseFile, identity)) {
            UUID nationId = UUID.fromString("39ca55f7-740d-4521-a1de-f9e5f8302a4c");
            UUID teamId = UUID.fromString("3fa1dc7d-76d0-4e61-b10f-bf7d1ad99776");

            assertEquals(14, migrated.schemaVersion());
            assertEquals(identity, migrated.identity());
            assertEquals(
                    nationId,
                    migrated.registerNation(nationId, "migration-test", "register", teamId, 1000L).nationId());
            UUID playerId = UUID.fromString("b48640d2-1752-49de-9a9b-0f3219dfca4c");
            assertEquals(
                    playerId,
                    migrated.joinCitizenship(
                                    UUID.fromString("9f88a122-c57e-48c6-b76a-ddc9f4696fc1"),
                                    "migration-test",
                                    "join",
                                    playerId,
                                    nationId,
                                    2000L)
                            .playerId());
        }
    }

    private static DatabaseIdentity identity(String worldId) {
        return new DatabaseIdentity(
                UUID.fromString(worldId),
                "0.1.0",
                "1.21-2.3.0.5",
                "2101.1.10",
                "2101.1.20");
    }
}
