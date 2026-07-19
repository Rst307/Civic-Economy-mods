package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.CancelTreasuryWithdrawalApproval;
import org.civiceconomy.fiscal.ConfirmTreasuryWithdrawal;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.fiscal.TreasuryWithdrawalApprovalRegistry;
import org.civiceconomy.nation.NationId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV58MigrationTest {
    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");
    private static final ServiceIdentity SERVICE =
            new ServiceIdentity("civiceconomy-treasury-withdrawal");
    private static final NationId NATION_ID =
            new NationId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final UUID ACTOR =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    @TempDir Path temporaryDirectory;

    @Test
    void v57DatabaseAddsAuditedApprovalCancellation() throws Exception {
        Path file = temporaryDirectory.resolve("v57-withdrawal-cancellation.sqlite3");
        DatabaseIdentity identity = identity();
        try (CivicDatabase current = CivicDatabase.open(file, identity)) {
            current.registerNation(
                    NATION_ID.value(), "v58-migration", "register-nation",
                    UUID.fromString("33333333-3333-3333-3333-333333333333"),
                    NOW.minusSeconds(60L).toEpochMilli());
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute("DROP TABLE treasury_withdrawal_approval_cancellation");
            statement.execute("PRAGMA user_version = 57");
        }

        try (CivicDatabase migrated = CivicDatabase.open(file, identity)) {
            TreasuryWithdrawalApprovalRegistry approvals =
                    new TreasuryWithdrawalApprovalRegistry(
                            migrated, Clock.fixed(NOW, ZoneOffset.UTC));
            var approval = approvals.initiate(new ConfirmTreasuryWithdrawal(
                    SERVICE,
                    "v58-approval",
                    NATION_ID,
                    new AccountId("nation:" + NATION_ID.value() + ":treasury"),
                    ACTOR,
                    MoneyAmount.ofMinorUnits(400L),
                    "V58 cancellation migration"));

            var cancelled = approvals.cancel(new CancelTreasuryWithdrawalApproval(
                    SERVICE,
                    "v58-cancel",
                    approval.approvalRequestId(),
                    ACTOR,
                    "Migration cancellation audit"));

            assertEquals(86, migrated.schemaVersion());
            assertEquals("CANCELLED", cancelled.state());
            assertEquals("Migration cancellation audit", cancelled.cancellationReason());
        }
    }

    private static DatabaseIdentity identity() {
        return new DatabaseIdentity(
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                "0.1.0-probe",
                "1.21-2.3.0.5",
                "2101.1.10",
                "2101.1.20");
    }
}
