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

class CivicDatabaseV50MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v49DatabaseAddsCancellationRecoveryWithoutChangingPreparedBatch() throws Exception {
        Path databaseFile = temporaryDirectory.resolve("schema-v49.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("d0a50103-ed53-4c86-8fe8-df69040e267b"),
                "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        UUID nationId = UUID.fromString("d3583272-86d4-4760-a642-adb6b7e86bd7");
        UUID periodId = UUID.fromString("46917873-0786-47d2-881f-5694b0377bc6");
        UUID recipeId = UUID.fromString("c75474c0-5cd8-4e8b-b99a-ce0656bbe932");
        UUID mintId = UUID.fromString("9de1e82e-d86d-49d7-86c5-34364dffbf30");
        UUID batchId = UUID.fromString("20ed6d6d-725b-4757-a9a6-a15838691363");
        UUID actor = UUID.fromString("3331c4a2-8a4d-46bf-b84b-f4a6d404ec6b");
        long now = Instant.parse("2026-08-02T00:00:00Z").toEpochMilli();
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
        }
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var statement = connection.createStatement()) {
            statement.execute("DROP INDEX mint_batch_cancellation_request");
            statement.execute("DROP INDEX mint_batch_return_request");
            statement.execute("DROP INDEX mint_batch_return_reference");
            statement.execute("ALTER TABLE mint_batch DROP COLUMN cancellation_service_identity");
            statement.execute("ALTER TABLE mint_batch DROP COLUMN cancellation_request_id");
            statement.execute("ALTER TABLE mint_batch DROP COLUMN cancellation_actor_player_id");
            statement.execute("ALTER TABLE mint_batch DROP COLUMN cancellation_reason");
            statement.execute("ALTER TABLE mint_batch DROP COLUMN cancellation_prepared_at_epoch_millis");
            statement.execute("ALTER TABLE mint_batch DROP COLUMN return_service_identity");
            statement.execute("ALTER TABLE mint_batch DROP COLUMN return_request_id");
            statement.execute("ALTER TABLE mint_batch DROP COLUMN return_external_reference");
            statement.execute("ALTER TABLE mint_batch DROP COLUMN cancelled_at_epoch_millis");
            statement.execute("DROP TABLE monetary_stock_correction");
            statement.execute("PRAGMA user_version = 49");
        }

        try (CivicDatabase migrated = CivicDatabase.open(databaseFile, identity)) {
            assertEquals(96, migrated.schemaVersion());
            StoredMintBatch batch = migrated.mintBatch(batchId);
            assertEquals("PREPARING", batch.state());
            assertEquals("EXTERNAL_PENDING", batch.custodyState());
            assertEquals(300L, batch.issuedMinorUnits());
            assertNull(batch.cancellationRequestId());
            assertEquals(300L,
                    migrated.nationalIssuanceQuota(periodId, nationId).reservedMinorUnits());
        }
    }
}
