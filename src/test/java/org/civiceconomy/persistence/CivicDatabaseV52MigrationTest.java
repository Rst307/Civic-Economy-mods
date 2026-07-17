package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV52MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v51DatabaseAddsRecoveryIncidentsWithoutChangingPendingIssuance() throws Exception {
        Path databaseFile = temporaryDirectory.resolve("schema-v51.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("0d9b675d-0502-43ce-a990-b50398126236"),
                "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        UUID nationId = UUID.fromString("2abdcade-3500-408a-9924-7a623e10b16c");
        UUID periodId = UUID.fromString("b7c6c151-e86c-42ad-9cab-0fcc6216c21e");
        UUID recipeId = UUID.fromString("e0ca42ee-a925-449b-b2f0-eb7e09075c12");
        UUID mintId = UUID.fromString("abeb95de-31f1-47ce-a8bf-71d3be0ddb4c");
        UUID batchId = UUID.fromString("c187d8c2-6b8e-4115-a098-6a6b41b91f7d");
        UUID operationId = UUID.fromString("c0669158-46ac-4cf8-8acb-b71a00f0bb12");
        UUID actor = UUID.fromString("1d750810-bf7c-4bf0-a7cc-c6396375870d");
        long now = Instant.parse("2026-08-12T00:00:00Z").toEpochMilli();
        try (CivicDatabase database = CivicDatabase.open(databaseFile, identity)) {
            database.registerNation(nationId, "test", "nation", UUID.randomUUID(), now - 10L);
            database.publishIssuanceQuotaPeriod(
                    periodId, "issuance", "period", now, now + 10_000L,
                    1_000L, 500L,
                    List.of(new StoredNationalIssuanceQuotaAllocation(nationId, 500L)),
                    "Period", now - 9L);
            database.activateNationalIssuanceQuota(
                    UUID.randomUUID(), "governance", "activate", periodId, nationId,
                    actor, 500L, "Activate", now - 8L);
            database.publishMintRecipeVersion(
                    recipeId, "mint", "recipe", 1,
                    List.of(new StoredMintRecipeIngredient(
                            0, "EXACT_ITEM", "minecraft:diamond", 1L, 100L)),
                    1_000L, "Recipe", now - 7L);
            database.registerMint(
                    mintId, "mint", "register", nationId, "minecraft:overworld",
                    1, 64, 2, UUID.randomUUID(), UUID.randomUUID(), false,
                    recipeId, actor, "Mint", now - 6L);
            database.prepareMintBatch(
                    batchId, "mint", "prepare", mintId, periodId, nationId, recipeId,
                    300L,
                    List.of(new StoredMintMaterialStack(
                            0, "EXACT_ITEM", "minecraft:diamond", "minecraft:diamond", 3L)),
                    actor, "Prepared", now + 1L);
            database.confirmMintBatchCustody(
                    batchId, "mint", "custody", "inventory-move:migration", now + 2L);
            database.prepareMintBatchIssuance(
                    operationId,
                    batchId,
                    "mint",
                    "issue",
                    "Complete migrated batch",
                    now + 1_500L);
        }
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var statement = connection.createStatement()) {
            statement.execute("DROP TABLE monetary_stock_correction");
            statement.execute("DROP TABLE mint_recovery_incident");
            statement.execute("PRAGMA user_version = 51");
        }

        try (CivicDatabase migrated = CivicDatabase.open(databaseFile, identity)) {
            assertEquals(73, migrated.schemaVersion());
            assertEquals("PREPARED", migrated.mintBatchIssuanceOperation(operationId).state());
            assertEquals("COMMITTING", migrated.mintBatch(batchId).state());
            assertEquals(300L,
                    migrated.nationalIssuanceQuota(periodId, nationId).reservedMinorUnits());
            assertNull(migrated.mintRecoveryIncident(operationId, "TREASURY_CREDIT"));

            StoredMintRecoveryIncident incident = migrated.recordMintRecoveryIncident(
                    operationId,
                    "TREASURY_CREDIT",
                    "IllegalStateException",
                    "LC confirmation unavailable",
                    now + 2_000L);
            assertEquals(batchId, incident.batchId());
            assertEquals("OPEN", incident.state());
        }
    }
}
