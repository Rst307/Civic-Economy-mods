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
            assertEquals(2, database.schemaVersion());
            assertEquals(identity, database.identity());
        }

        try (CivicDatabase reopened = CivicDatabase.open(databaseFile, identity)) {
            assertEquals("wal", reopened.journalMode());
            assertEquals(2, reopened.schemaVersion());
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

    private static DatabaseIdentity identity(String worldId) {
        return new DatabaseIdentity(
                UUID.fromString(worldId),
                "0.1.0",
                "1.21-2.3.0.5",
                "2101.1.10",
                "2101.1.20");
    }
}
