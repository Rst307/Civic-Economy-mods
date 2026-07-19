package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.sql.Statement;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV30MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v29GrantAndRevocationHistorySurvivesCapabilityTableRebuild() throws Exception {
        Path databaseFile = temporaryDirectory.resolve("schema-v29.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("6182f431-bf70-41be-bd90-0a1398a7c26e"),
                "0.1.0-probe",
                "1.21-2.3.0.5",
                "2101.1.10",
                "2101.1.20");
        UUID grantId = UUID.fromString("15985335-d5cd-4ff2-a99f-d53b896acb24");
        UUID revocationId = UUID.fromString("a16711e6-eac7-4e22-be96-60431e31c75a");
        try (CivicDatabase database = CivicDatabase.open(databaseFile, identity)) {
            database.registerFiscalService(
                    "migration-service",
                    "migration-mod",
                    "Migration service",
                    "migration-admin",
                    "register-migration-service",
                    "Migration fixture",
                    1_000L);
            database.grantFiscalCapability(
                    grantId,
                    "migration-admin",
                    "grant-read",
                    "migration-service",
                    "READ_ACCOUNT",
                    "nation:aurora:treasury",
                    "Migration grant fixture",
                    2_000L);
            database.revokeFiscalCapability(
                    revocationId,
                    grantId,
                    "migration-admin",
                    "revoke-read",
                    "Migration revocation fixture",
                    3_000L);
        }
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                Statement statement = connection.createStatement()) {
            statement.execute("DROP TABLE territory_force_load_enforcement");
            statement.execute("DROP TABLE territory_maintenance_policy");
            statement.execute("DROP TABLE territory_maintenance_assessment_claim");
            statement.execute("DROP TABLE territory_maintenance_assessment_batch");
            statement.execute("DROP TABLE territory_maintenance_settlement_assessment");
            statement.execute("DROP TABLE territory_maintenance_settlement");
            statement.execute("DROP TABLE permanent_destruction_operation");
            statement.execute("DROP TABLE monetary_stock_correction");
            statement.execute("PRAGMA user_version = 29");
        }

        try (CivicDatabase migrated = CivicDatabase.open(databaseFile, identity)) {
            assertEquals(88, migrated.schemaVersion());
            assertNotNull(migrated.fiscalCapabilityGrant(grantId));
            assertNotNull(migrated.fiscalCapabilityRevocation(grantId));
            assertNotNull(migrated.grantFiscalCapability(
                    UUID.fromString("016aa966-272a-45de-ae20-7ad1865bb19c"),
                    "migration-admin",
                    "grant-destruction",
                    "migration-service",
                    "PERMANENT_DESTRUCTION",
                    "nation:aurora:treasury",
                    "New v30 capability",
                    4_000L));
        }
    }
}
