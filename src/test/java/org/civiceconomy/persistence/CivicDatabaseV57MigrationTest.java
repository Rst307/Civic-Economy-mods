package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.ConfirmTreasuryWithdrawal;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.fiscal.TreasuryWithdrawalApprovalRegistry;
import org.civiceconomy.nation.NationId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV57MigrationTest {
    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");
    private static final NationId NATION_ID =
            new NationId(UUID.fromString("11111111-1111-1111-1111-111111111111"));

    @TempDir Path temporaryDirectory;

    @Test
    void v56ApprovalReceivesConservativeSevenDayExpiryWithoutChangingState()
            throws Exception {
        Path file = temporaryDirectory.resolve("v56-withdrawal-approval.sqlite3");
        DatabaseIdentity identity = identity();
        UUID approvalId;
        try (CivicDatabase current = CivicDatabase.open(file, identity)) {
            current.registerNation(
                    NATION_ID.value(),
                    "v57-migration",
                    "register-nation",
                    UUID.fromString("22222222-2222-2222-2222-222222222222"),
                    NOW.minusSeconds(60L).toEpochMilli());
            approvalId = new TreasuryWithdrawalApprovalRegistry(
                            current, Clock.fixed(NOW, ZoneOffset.UTC))
                    .initiate(new ConfirmTreasuryWithdrawal(
                            new ServiceIdentity("civiceconomy-treasury-withdrawal"),
                            "v56-approval",
                            NATION_ID,
                            new AccountId("nation:" + NATION_ID.value() + ":treasury"),
                            UUID.fromString("33333333-3333-3333-3333-333333333333"),
                            MoneyAmount.ofMinorUnits(400L),
                            "V56 approval migration"))
                    .approvalRequestId();
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute("DROP TABLE treasury_withdrawal_approval_expiry");
            statement.execute("PRAGMA user_version = 56");
        }

        try (CivicDatabase migrated = CivicDatabase.open(file, identity)) {
            StoredTreasuryWithdrawalApproval approval =
                    migrated.treasuryWithdrawalApproval(approvalId);

            assertEquals(68, migrated.schemaVersion());
            assertEquals("APPROVED", approval.state());
            assertEquals(
                    NOW.plusSeconds(7L * 24L * 60L * 60L).toEpochMilli(),
                    approval.expiresAtEpochMillis());
            assertNull(approval.expiredAtEpochMillis());
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
