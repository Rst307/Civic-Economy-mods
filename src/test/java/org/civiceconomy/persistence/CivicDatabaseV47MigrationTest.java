package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.FiscalAuthorization;
import org.civiceconomy.fiscal.FiscalCapability;
import org.civiceconomy.fiscal.GrantFiscalCapability;
import org.civiceconomy.fiscal.RegisterFiscalService;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV47MigrationTest {
    @TempDir Path temporaryDirectory;

    @Test
    void v46DatabaseAddsIssuanceQuotaFactsAndScopedServiceCapability() throws Exception {
        Path databaseFile = temporaryDirectory.resolve("schema-v46.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("8456f0a5-b0a8-41bd-b745-6af738b12400"),
                "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        try (CivicDatabase ignored = CivicDatabase.open(databaseFile, identity)) {
            assertEquals(63, ignored.schemaVersion());
        }
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var statement = connection.createStatement()) {
            statement.execute("DROP TABLE national_issuance_quota_activation");
            statement.execute("DROP TABLE national_issuance_quota");
            statement.execute("DROP TABLE issuance_quota_period");
            statement.execute("DROP TABLE monetary_stock_correction");
            statement.execute("PRAGMA user_version = 46");
        }

        try (CivicDatabase migrated = CivicDatabase.open(databaseFile, identity)) {
            assertEquals(63, migrated.schemaVersion());
            try (var connection = DriverManager.getConnection(
                            "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                    var statement = connection.createStatement();
                    var result = statement.executeQuery("""
                            SELECT COUNT(*) AS object_count FROM sqlite_master
                            WHERE (type = 'table' AND name IN (
                                'issuance_quota_period',
                                'national_issuance_quota',
                                'national_issuance_quota_activation'
                            )) OR (type = 'index' AND name = 'issuance_quota_period_time')
                            """)) {
                assertEquals(4, result.getInt("object_count"));
            }
            FiscalAuthorization authorization = new FiscalAuthorization(migrated);
            ServiceIdentity service = new ServiceIdentity("migration-issuance-service");
            ServiceIdentity administrator = new ServiceIdentity("migration-admin");
            authorization.register(new RegisterFiscalService(
                    service, "civiceconomy", "Migration issuance service",
                    administrator, "register", "Verify schema v47 capability"));
            authorization.grant(new GrantFiscalCapability(
                    administrator, "grant", service, FiscalCapability.MANAGE_ISSUANCE,
                    new AccountId("monetary-supply:global"), "Verify new scoped capability"));
            assertEquals(
                    FiscalCapability.MANAGE_ISSUANCE,
                    authorization.describe(service).grants().getFirst().grant().capability());
        }
    }
}
