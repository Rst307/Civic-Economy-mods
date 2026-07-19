package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV54MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v53PermanentDestructionBackfillsTheOwningServiceAsOperator() throws Exception {
        Path databaseFile = temporaryDirectory.resolve("schema-v53.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("a0aa9af7-ebaf-48d4-ae88-8040ea407e55"),
                "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        UUID operationId = UUID.fromString("19848591-818a-43db-aa7e-8912d024e61b");
        try (CivicDatabase database = CivicDatabase.open(databaseFile, identity)) {
            database.confirmMonetarySupplyChange(
                    UUID.randomUUID(),
                    "legacy-destruction-service",
                    "seed-500",
                    "ISSUANCE",
                    500L,
                    "seed:500",
                    "Existing issuance",
                    1_000L,
                    1_000L);
            database.preparePermanentDestruction(
                    operationId,
                    "legacy-destruction-service",
                    "destroy-200",
                    "nation:legacy:treasury",
                    200L,
                    "Existing prepared destruction",
                    2_000L);
        }
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var statement = connection.createStatement()) {
            statement.execute(
                    "ALTER TABLE permanent_destruction_operation DROP COLUMN operator_identity");
            statement.execute("PRAGMA user_version = 53");
        }

        try (CivicDatabase migrated = CivicDatabase.open(databaseFile, identity)) {
            assertEquals(86, migrated.schemaVersion());
            StoredPermanentDestructionOperation operation =
                    migrated.permanentDestructionOperation(
                            "legacy-destruction-service", "destroy-200");
            assertEquals(operationId, operation.operationId());
            assertEquals(
                    "service:legacy-destruction-service", operation.operatorIdentity());
            migrated.markPermanentDestructionExternalApplied(operationId, 3_000L);
            assertEquals(
                    "PERMANENT_DESTRUCTION",
                    migrated.commitPermanentDestruction(operationId, 3_000L).changeKind());
            assertEquals(300L, migrated.cumulativeNetIssuanceMinorUnits());
        }
    }
}
