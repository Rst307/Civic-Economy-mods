package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV61MigrationTest {
    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");
    private static final Instant EFFECTIVE_AT = NOW.plus(Duration.ofDays(1L));
    private static final NationId NATION =
            new NationId(UUID.fromString("11111111-2222-3333-4444-555555555555"));
    private static final UUID ACTOR =
            UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");

    @TempDir Path temporaryDirectory;

    @Test
    void v60PoliciesReceiveTheConservativeSevenDayLifetime() throws Exception {
        Path file = temporaryDirectory.resolve("v60-withdrawal-approval-policy.sqlite3");
        DatabaseIdentity identity = identity();
        try (CivicDatabase current = CivicDatabase.open(file, identity)) {
            current.registerNation(
                    NATION.value(),
                    "v61-migration",
                    "register-nation",
                    UUID.fromString("99999999-8888-7777-6666-555555555555"),
                    NOW.minusSeconds(60L).toEpochMilli());
            new WithdrawalApprovalPolicyRegistry(
                            current, Clock.fixed(NOW, ZoneOffset.UTC))
                    .schedule(new ScheduleWithdrawalApprovalPolicy(
                            new ServiceIdentity("v61-migration"),
                            "schedule-policy",
                            NATION,
                            ACTOR,
                            List.of(new WithdrawalApprovalTier(MoneyAmount.ZERO, 2)),
                            Duration.ofDays(3L),
                            EFFECTIVE_AT,
                            "Policy row that predates configurable expiry"));
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute(
                    "ALTER TABLE withdrawal_approval_policy DROP COLUMN approval_lifetime_millis");
            statement.execute("PRAGMA user_version = 60");
        }

        try (CivicDatabase migrated = CivicDatabase.open(file, identity)) {
            var policy = new WithdrawalApprovalPolicyRegistry(
                            migrated, Clock.fixed(EFFECTIVE_AT, ZoneOffset.UTC))
                    .current(NATION, EFFECTIVE_AT);

            assertEquals(91, migrated.schemaVersion());
            assertEquals(Duration.ofDays(7L), policy.approvalLifetime());
        }
    }

    private static DatabaseIdentity identity() {
        return new DatabaseIdentity(
                UUID.fromString("bbbbbbbb-cccc-dddd-eeee-ffffffffffff"),
                "0.1.0-probe",
                "1.21-2.3.0.5",
                "2101.1.10",
                "2101.1.20");
    }
}
