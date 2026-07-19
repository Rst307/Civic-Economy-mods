package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.ScheduleWithdrawalApprovalPolicy;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.fiscal.WithdrawalApprovalPolicyRegistry;
import org.civiceconomy.fiscal.WithdrawalApprovalTier;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.nation.NationFiscalPermission;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV56MigrationTest {
    private static final UUID NATION_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ACTOR_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID GRANT_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");
    private static final Instant NOW = Instant.parse("2026-07-16T06:00:00Z");

    @TempDir Path temporaryDirectory;

    @Test
    void v55NationPermissionHistorySurvivesApprovalPolicyMigration() throws Exception {
        Path databaseFile = temporaryDirectory.resolve("schema-v55.sqlite3");
        DatabaseIdentity identity = new DatabaseIdentity(
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                "0.1.0-probe", "1.21-2.3.0.5", "2101.1.10", "2101.1.20");
        try (CivicDatabase database = CivicDatabase.open(databaseFile, identity)) {
            database.registerNation(
                    NATION_ID,
                    "migration-test",
                    "register-nation",
                    UUID.fromString("55555555-5555-5555-5555-555555555555"),
                    NOW.minusSeconds(60L).toEpochMilli());
            database.grantNationFiscalPermission(
                    GRANT_ID,
                    "migration-test",
                    "grant-withdrawal",
                    NATION_ID,
                    ACTOR_ID,
                    ACTOR_ID,
                    NationFiscalPermission.MANAGE_WITHDRAWAL.name(),
                    "Existing v55 Withdrawal permission",
                    NOW.toEpochMilli());
            database.revokeNationFiscalPermission(
                    UUID.fromString("66666666-6666-6666-6666-666666666666"),
                    GRANT_ID,
                    "migration-test",
                    "revoke-withdrawal",
                    NATION_ID,
                    ACTOR_ID,
                    "Existing v55 revocation",
                    NOW.plusSeconds(1L).toEpochMilli());
        }
        downgradeToV55(databaseFile);

        try (CivicDatabase migrated = CivicDatabase.open(databaseFile, identity)) {
            assertEquals(86, migrated.schemaVersion());
            assertNotNull(migrated.nationFiscalPermissionGrant(GRANT_ID));
            assertNotNull(migrated.nationFiscalPermissionRevocation(GRANT_ID));
            migrated.grantNationFiscalPermission(
                    UUID.fromString("77777777-7777-7777-7777-777777777777"),
                    "migration-test",
                    "grant-approval-policy",
                    NATION_ID,
                    ACTOR_ID,
                    ACTOR_ID,
                    NationFiscalPermission.MANAGE_APPROVAL_POLICY.name(),
                    "New v56 approval-policy permission",
                    NOW.plusSeconds(2L).toEpochMilli());
            var policy = new WithdrawalApprovalPolicyRegistry(
                            migrated, Clock.fixed(NOW, ZoneOffset.UTC))
                    .schedule(new ScheduleWithdrawalApprovalPolicy(
                            new ServiceIdentity("migration-test"),
                            "schedule-policy",
                            new NationId(NATION_ID),
                            ACTOR_ID,
                            List.of(
                                    new WithdrawalApprovalTier(MoneyAmount.ZERO, 1),
                                    new WithdrawalApprovalTier(
                                            MoneyAmount.ofMinorUnits(500L), 2)),
                            Duration.ofDays(7L),
                            NOW.plusSeconds(86_400L),
                            "New v56 Withdrawal Approval Policy"));
            assertEquals(2, policy.requiredApprovals(MoneyAmount.ofMinorUnits(500L)));
        }
    }

    private static void downgradeToV55(Path databaseFile) throws Exception {
        try (var connection = DriverManager.getConnection(
                        "jdbc:sqlite:" + databaseFile.toAbsolutePath());
                var statement = connection.createStatement()) {
            statement.execute("PRAGMA foreign_keys = OFF");
            statement.execute("DROP INDEX treasury_withdrawal_approval_execution");
            statement.execute("ALTER TABLE treasury_withdrawal_operation DROP COLUMN approval_request_id");
            statement.execute("DROP TABLE treasury_withdrawal_approval_vote");
            statement.execute("DROP TABLE treasury_withdrawal_approval_request");
            statement.execute("DROP TABLE withdrawal_approval_policy_tier");
            statement.execute("DROP TABLE withdrawal_approval_policy");
            statement.execute("DROP INDEX nation_fiscal_permission_grant_player");
            statement.execute("ALTER TABLE nation_fiscal_permission_revocation RENAME TO nation_fiscal_permission_revocation_v56");
            statement.execute("ALTER TABLE nation_fiscal_permission_grant RENAME TO nation_fiscal_permission_grant_v56");
            statement.execute("""
                    CREATE TABLE nation_fiscal_permission_grant (
                        grant_id TEXT PRIMARY KEY,
                        service_identity TEXT NOT NULL,
                        request_id TEXT NOT NULL,
                        nation_id TEXT NOT NULL REFERENCES nation_registry(nation_id),
                        actor_player_id TEXT NOT NULL,
                        player_id TEXT NOT NULL,
                        permission TEXT NOT NULL CHECK (permission IN (
                            'VIEW_ACCOUNT', 'VIEW_LEDGER', 'DRAFT_BUDGET',
                            'APPROVE_BUDGET', 'INITIATE_PAYMENT', 'APPROVE_PAYMENT',
                            'MANAGE_WITHDRAWAL', 'MANAGE_TERRITORY_FINANCE',
                            'MANAGE_ISSUANCE', 'MANAGE_FISCAL_ROLES',
                            'MANAGE_PUBLIC_POLICY', 'MANAGE_RECOVERY'
                        )),
                        reason TEXT NOT NULL CHECK (length(trim(reason)) > 0),
                        granted_at_epoch_millis INTEGER NOT NULL
                            CHECK (granted_at_epoch_millis >= 0),
                        UNIQUE (service_identity, request_id)
                    )
                    """);
            statement.execute("INSERT INTO nation_fiscal_permission_grant SELECT * FROM nation_fiscal_permission_grant_v56");
            statement.execute("""
                    CREATE INDEX nation_fiscal_permission_grant_player
                    ON nation_fiscal_permission_grant (nation_id, player_id)
                    """);
            statement.execute("""
                    CREATE TABLE nation_fiscal_permission_revocation (
                        revocation_id TEXT PRIMARY KEY,
                        grant_id TEXT NOT NULL UNIQUE
                            REFERENCES nation_fiscal_permission_grant(grant_id),
                        service_identity TEXT NOT NULL,
                        request_id TEXT NOT NULL,
                        nation_id TEXT NOT NULL REFERENCES nation_registry(nation_id),
                        actor_player_id TEXT NOT NULL,
                        reason TEXT NOT NULL CHECK (length(trim(reason)) > 0),
                        revoked_at_epoch_millis INTEGER NOT NULL
                            CHECK (revoked_at_epoch_millis >= 0),
                        UNIQUE (service_identity, request_id)
                    )
                    """);
            statement.execute("INSERT INTO nation_fiscal_permission_revocation SELECT * FROM nation_fiscal_permission_revocation_v56");
            statement.execute("DROP TABLE nation_fiscal_permission_revocation_v56");
            statement.execute("DROP TABLE nation_fiscal_permission_grant_v56");
            statement.execute("PRAGMA user_version = 55");
        }
    }
}
