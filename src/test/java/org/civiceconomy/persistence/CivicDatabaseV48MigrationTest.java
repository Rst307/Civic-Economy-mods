package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV48MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v47DatabaseAddsMintRecipeAndFacilityFactsWithoutRewritingQuota() throws Exception {
        Path databaseFile = temporaryDirectory.resolve("schema-v47.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("78f98534-9278-4f54-98fa-d84f3f3bdbab"),
                "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        UUID nationId = UUID.fromString("029ef440-65fc-47db-833d-06080e6fea26");
        UUID periodId = UUID.fromString("b81dda1d-d217-484a-929a-99e937b02300");
        long start = Instant.parse("2026-08-01T00:00:00Z").toEpochMilli();
        try (CivicDatabase database = CivicDatabase.open(databaseFile, identity)) {
            database.registerNation(nationId, "test", "nation", UUID.randomUUID(), start - 1L);
            database.publishIssuanceQuotaPeriod(
                    periodId,
                    "issuance-controller",
                    "period",
                    start,
                    start + 604_800_000L,
                    1_000L,
                    400L,
                    List.of(new StoredNationalIssuanceQuotaAllocation(nationId, 400L)),
                    "Existing quota fact",
                    start - 1L);
            assertEquals(95, database.schemaVersion());
        }
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var statement = connection.createStatement()) {
            statement.execute("DROP TABLE registered_mint");
            statement.execute("DROP TABLE mint_recipe_ingredient");
            statement.execute("DROP TABLE mint_recipe_version");
            statement.execute("DROP TABLE monetary_stock_correction");
            statement.execute("PRAGMA user_version = 47");
        }

        try (CivicDatabase migrated = CivicDatabase.open(databaseFile, identity)) {
            assertEquals(95, migrated.schemaVersion());
            assertNotNull(migrated.nationalIssuanceQuota(periodId, nationId));
            try (var connection = DriverManager.getConnection(
                            "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                    var statement = connection.createStatement();
                    var result = statement.executeQuery("""
                            SELECT COUNT(*) AS object_count FROM sqlite_master
                            WHERE (type = 'table' AND name IN (
                                'mint_recipe_version',
                                'mint_recipe_ingredient',
                                'registered_mint'
                            )) OR (type = 'index' AND name = 'registered_mint_nation_state')
                            """)) {
                assertEquals(4, result.getInt("object_count"));
            }
        }
    }
}
