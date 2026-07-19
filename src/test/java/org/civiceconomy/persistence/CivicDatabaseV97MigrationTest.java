package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.civiceconomy.territory.TerritoryMaintenancePolicyRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV97MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v96PolicyHistoryDoesNotInventForceLoadGrace() throws Exception {
        Path file = temporaryDirectory.resolve("schema-v96.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("b61dbe13-f51f-41d6-a090-914989cabf2a"),
                "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        UUID legacyPolicyId = UUID.fromString("454c2bfa-5502-4b16-baa9-516701ce51a8");
        try (CivicDatabase database = CivicDatabase.open(file, identity)) {
            database.scheduleTerritoryMaintenancePolicy(
                    legacyPolicyId,
                    "migration",
                    "legacy-policy",
                    "console",
                    Duration.ofDays(7).toMillis(),
                    75L,
                    15_000,
                    25L,
                    Duration.ofHours(6).toMillis(),
                    30L,
                    Duration.ofDays(14).toMillis(),
                    6_000,
                    2_000L,
                    "Legacy policy",
                    1_000L);
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute(
                    "ALTER TABLE territory_maintenance_policy DROP COLUMN force_load_grace_millis");
            statement.execute("PRAGMA user_version = 96");
        }

        try (CivicDatabase migrated = CivicDatabase.open(file, identity)) {
            assertEquals(97, migrated.schemaVersion());
            assertNull(migrated.territoryMaintenancePolicy(legacyPolicyId).forceLoadGraceMillis());
            assertNull(migrated.currentTerritoryMaintenancePolicy(Long.MAX_VALUE));
            TerritoryMaintenancePolicyRegistry policies = new TerritoryMaintenancePolicyRegistry(
                    migrated,
                    Clock.fixed(Instant.ofEpochMilli(10_000L), ZoneOffset.UTC));
            assertTrue(policies.current(Instant.ofEpochMilli(10_000L)).isEmpty());
            assertTrue(policies.find(legacyPolicyId).isEmpty());

            StoredTerritoryMaintenancePolicy governed = migrated.scheduleTerritoryMaintenancePolicy(
                    UUID.fromString("a4e18831-c02d-45df-ae47-089b1a3d8d70"),
                    "migration",
                    "governed-policy",
                    "console",
                    Duration.ofDays(7).toMillis(),
                    75L,
                    15_000,
                    25L,
                    Duration.ofHours(3).toMillis(),
                    30L,
                    Duration.ofDays(14).toMillis(),
                    6_000,
                    3_000L,
                    "Governed policy",
                    2_000L);
            assertEquals(
                    Duration.ofHours(3).toMillis(),
                    migrated.currentTerritoryMaintenancePolicy(Long.MAX_VALUE)
                            .forceLoadGraceMillis());
            assertEquals(governed.policyId(),
                    migrated.currentTerritoryMaintenancePolicy(Long.MAX_VALUE).policyId());
        }
    }
}
