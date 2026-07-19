package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.UUID;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.FiscalAuthorization;
import org.civiceconomy.fiscal.FiscalCapability;
import org.civiceconomy.fiscal.GrantFiscalCapability;
import org.civiceconomy.fiscal.RegisterFiscalService;
import org.civiceconomy.fiscal.RevokeFiscalCapability;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV67MigrationTest {
    private static final ServiceIdentity SERVICE = new ServiceIdentity("activity-migration");
    private static final ServiceIdentity ADMIN = new ServiceIdentity("activity-admin");
    private static final AccountId TREASURY = new AccountId(
            "nation:11111111-1111-1111-1111-111111111111:treasury");

    @TempDir Path temporaryDirectory;

    @Test
    void v66GrantHistorySurvivesWhileEconomicActivityCapabilityIsAdded() throws Exception {
        Path file = temporaryDirectory.resolve("schema-v66.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        try (CivicDatabase database = CivicDatabase.open(file, identity)) {
            FiscalAuthorization authorization = new FiscalAuthorization(database);
            authorization.register(new RegisterFiscalService(
                    SERVICE, "civiceconomy-tests", "v66 activity migration fixture"));
            var grant = authorization.grant(new GrantFiscalCapability(
                    ADMIN,
                    "legacy-read",
                    SERVICE,
                    FiscalCapability.READ_ACCOUNT,
                    TREASURY,
                    "Existing v66 grant"));
            authorization.revoke(new RevokeFiscalCapability(
                    ADMIN,
                    "legacy-revoke",
                    grant.grantId(),
                    "Existing v66 revocation"));
        }
        downgradeToV66(file);

        try (CivicDatabase migrated = CivicDatabase.open(file, identity)) {
            assertEquals(84, migrated.schemaVersion());
            FiscalAuthorization authorization = new FiscalAuthorization(migrated);
            assertEquals(1, authorization.describe(SERVICE).grants().size());
            assertFalse(authorization.describe(SERVICE).grants().getFirst().active());

            authorization.grant(new GrantFiscalCapability(
                    ADMIN,
                    "record-activity",
                    SERVICE,
                    FiscalCapability.RECORD_ECONOMIC_ACTIVITY,
                    TREASURY,
                    "New v67 activity capability"));
            assertEquals(
                    FiscalCapability.RECORD_ECONOMIC_ACTIVITY,
                    authorization.describe(SERVICE).grants().get(1).grant().capability());
        }
    }

    private static void downgradeToV66(Path file) throws Exception {
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute("DROP INDEX auditable_economic_activity_nation_time");
            statement.execute("DROP TABLE auditable_economic_activity_evidence");
            statement.execute("DROP INDEX fiscal_service_grant_active_scope");
            statement.execute("ALTER TABLE fiscal_service_grant_revocation RENAME TO fiscal_service_grant_revocation_v67");
            statement.execute("ALTER TABLE fiscal_service_grant RENAME TO fiscal_service_grant_v67");
            statement.execute("""
                    CREATE TABLE fiscal_service_grant (
                        grant_id TEXT PRIMARY KEY,
                        administrator_identity TEXT NOT NULL,
                        request_id TEXT NOT NULL,
                        service_identity TEXT NOT NULL REFERENCES fiscal_service(service_identity),
                        capability TEXT NOT NULL CHECK (capability IN (
                            'READ_ACCOUNT', 'RESERVE_FUNDS', 'MANAGE_ESCROW',
                            'MANAGE_BUDGET', 'ISSUE_BILL', 'FUND_BILL',
                            'SETTLE_PAYMENT', 'REFUND_PAYMENT', 'COMPENSATE_PAYMENT',
                            'PERMANENT_DESTRUCTION', 'WITHDRAW_CASH', 'MANAGE_ISSUANCE'
                        )),
                        account_id TEXT NOT NULL,
                        reason TEXT NOT NULL,
                        granted_at_epoch_millis INTEGER NOT NULL CHECK (granted_at_epoch_millis >= 0),
                        UNIQUE (administrator_identity, request_id)
                    )
                    """);
            statement.execute("INSERT INTO fiscal_service_grant SELECT * FROM fiscal_service_grant_v67");
            statement.execute("""
                    CREATE INDEX fiscal_service_grant_active_scope
                    ON fiscal_service_grant (service_identity, capability, account_id)
                    """);
            statement.execute("""
                    CREATE TABLE fiscal_service_grant_revocation (
                        revocation_id TEXT PRIMARY KEY,
                        grant_id TEXT NOT NULL UNIQUE REFERENCES fiscal_service_grant(grant_id),
                        administrator_identity TEXT NOT NULL,
                        request_id TEXT NOT NULL,
                        reason TEXT NOT NULL,
                        revoked_at_epoch_millis INTEGER NOT NULL CHECK (revoked_at_epoch_millis >= 0),
                        UNIQUE (administrator_identity, request_id)
                    )
                    """);
            statement.execute("INSERT INTO fiscal_service_grant_revocation SELECT * FROM fiscal_service_grant_revocation_v67");
            statement.execute("DROP TABLE fiscal_service_grant_revocation_v67");
            statement.execute("DROP TABLE fiscal_service_grant_v67");
            statement.execute("PRAGMA user_version = 66");
        }
    }
}
