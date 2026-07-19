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

class CivicDatabaseV55MigrationTest {
    private static final ServiceIdentity SERVICE =
            new ServiceIdentity("legacy-treasury-withdrawal-service");
    private static final ServiceIdentity ADMINISTRATOR =
            new ServiceIdentity("migration-test-admin");
    private static final UUID NATION_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final AccountId TREASURY =
            new AccountId("nation:" + NATION_ID + ":treasury");

    @TempDir Path temporaryDirectory;

    @Test
    void v54GrantsAndRevocationsSurviveWhileWithdrawalSupportIsAdded() throws Exception {
        Path databaseFile = temporaryDirectory.resolve("schema-v54.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("33333333-3333-3333-3333-333333333333"),
                "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        try (CivicDatabase database = CivicDatabase.open(databaseFile, identity)) {
            database.registerNation(
                    NATION_ID,
                    "migration-test",
                    "register-migration-nation",
                    UUID.fromString("44444444-4444-4444-4444-444444444444"),
                    1_000L);
            FiscalAuthorization authorization = new FiscalAuthorization(database);
            authorization.register(new RegisterFiscalService(
                    SERVICE, "migration-test", "Legacy fiscal service"));
            var legacyGrant = authorization.grant(new GrantFiscalCapability(
                    ADMINISTRATOR,
                    "grant-legacy-read",
                    SERVICE,
                    FiscalCapability.READ_ACCOUNT,
                    TREASURY,
                    "Existing v54 grant"));
            authorization.revoke(new RevokeFiscalCapability(
                    ADMINISTRATOR,
                    "revoke-legacy-read",
                    legacyGrant.grantId(),
                    "Existing v54 revocation"));
        }
        downgradeToV54(databaseFile);

        try (CivicDatabase migrated = CivicDatabase.open(databaseFile, identity)) {
            assertEquals(89, migrated.schemaVersion());
            FiscalAuthorization authorization = new FiscalAuthorization(migrated);
            var beforeNewGrant = authorization.describe(SERVICE);
            assertEquals(1, beforeNewGrant.grants().size());
            assertFalse(beforeNewGrant.grants().getFirst().active());

            authorization.grant(new GrantFiscalCapability(
                    ADMINISTRATOR,
                    "grant-withdraw-cash",
                    SERVICE,
                    FiscalCapability.WITHDRAW_CASH,
                    TREASURY,
                    "New v55 exact-account Treasury Withdrawal grant"));
            assertEquals(
                    FiscalCapability.WITHDRAW_CASH,
                    authorization.describe(SERVICE).grants().get(1).grant().capability());

            UUID withdrawalId =
                    UUID.fromString("55555555-5555-5555-5555-555555555555");
            migrated.prepareTreasuryWithdrawal(
                    withdrawalId,
                    SERVICE.value(),
                    "withdraw-after-migration",
                    NATION_ID,
                    TREASURY.value(),
                    UUID.fromString("22222222-2222-2222-2222-222222222222"),
                    250L,
                    "Migration withdrawal",
                    2_000L);
            assertEquals(
                    "COMMITTED",
                    migrated.commitTreasuryWithdrawal(withdrawalId, 3_000L).state());
        }
    }

    private static void downgradeToV54(Path databaseFile) throws Exception {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var statement = connection.createStatement()) {
            statement.execute("DROP INDEX fiscal_service_grant_active_scope");
            statement.execute("ALTER TABLE fiscal_service_grant_revocation RENAME TO fiscal_service_grant_revocation_v55");
            statement.execute("ALTER TABLE fiscal_service_grant RENAME TO fiscal_service_grant_v55");
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
                            'PERMANENT_DESTRUCTION', 'MANAGE_ISSUANCE'
                        )),
                        account_id TEXT NOT NULL,
                        reason TEXT NOT NULL,
                        granted_at_epoch_millis INTEGER NOT NULL CHECK (granted_at_epoch_millis >= 0),
                        UNIQUE (administrator_identity, request_id)
                    )
                    """);
            statement.execute("INSERT INTO fiscal_service_grant SELECT * FROM fiscal_service_grant_v55");
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
            statement.execute("INSERT INTO fiscal_service_grant_revocation SELECT * FROM fiscal_service_grant_revocation_v55");
            statement.execute("DROP TABLE fiscal_service_grant_revocation_v55");
            statement.execute("DROP TABLE fiscal_service_grant_v55");
            statement.execute("DROP INDEX treasury_withdrawal_pending_actor");
            statement.execute("DROP TABLE treasury_withdrawal_operation");
            statement.execute("PRAGMA user_version = 54");
        }
    }
}
