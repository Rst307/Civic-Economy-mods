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

class CivicDatabaseV51MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v50DatabaseAddsIssuanceIntentWithoutChangingProcessingBatch() throws Exception {
        Path databaseFile = temporaryDirectory.resolve("schema-v50.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("412c2dd8-f0f5-48d0-bfc0-64772e017464"),
                "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        UUID nationId = UUID.fromString("311b6263-e08a-4b66-bcc0-115fcb10c443");
        UUID periodId = UUID.fromString("4e2e6463-56ac-4015-8018-afb796e51d43");
        UUID recipeId = UUID.fromString("a6875390-381c-466e-b994-931cb16be73c");
        UUID mintId = UUID.fromString("5552d4ae-8431-43cb-90f7-fe489b055ae4");
        UUID batchId = UUID.fromString("25d29cee-15a8-4e5f-861f-86f5d832bf69");
        UUID actor = UUID.fromString("f6ca3eff-c369-456e-b3e5-77d77b6d3426");
        long now = Instant.parse("2026-08-03T00:00:00Z").toEpochMilli();
        try (CivicDatabase database = CivicDatabase.open(databaseFile, identity)) {
            database.registerNation(nationId, "test", "nation", UUID.randomUUID(), now - 10L);
            database.publishIssuanceQuotaPeriod(
                    periodId, "issuance", "period", now - 100L, now + 10_000L,
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
                    actor, "Prepared", now - 5L);
            database.confirmMintBatchCustody(
                    batchId, "mint", "custody", "inventory-move:migration", now - 4L);
        }
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var statement = connection.createStatement()) {
            statement.execute("DROP TABLE monetary_stock_correction");
            statement.execute("DROP TABLE mint_recovery_incident");
            statement.execute("DROP TABLE mint_issuance_operation");
            statement.execute("PRAGMA user_version = 50");
        }

        try (CivicDatabase migrated = CivicDatabase.open(databaseFile, identity)) {
            assertEquals(93, migrated.schemaVersion());
            StoredMintBatch batch = migrated.mintBatch(batchId);
            assertEquals("PROCESSING", batch.state());
            assertEquals("HELD", batch.custodyState());
            assertEquals(300L,
                    migrated.nationalIssuanceQuota(periodId, nationId).reservedMinorUnits());
            assertNull(migrated.mintBatchIssuanceOperation("mint", "issue"));

            StoredMintIssuanceOperation prepared = migrated.prepareMintBatchIssuance(
                    UUID.randomUUID(),
                    batchId,
                    "mint",
                    "issue",
                    "Complete migrated batch",
                    batch.processingCompletesAtEpochMillis());
            assertEquals("PREPARED", prepared.state());
            assertEquals("COMMITTING", migrated.mintBatch(batchId).state());
        }
    }
}
