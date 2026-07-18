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
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.BudgetDisbursementApproval;
import org.civiceconomy.fiscal.BudgetDisbursementApprovalPolicyRegistry;
import org.civiceconomy.fiscal.BudgetDisbursementApprovalRegistry;
import org.civiceconomy.fiscal.BudgetDisbursementApprovalTier;
import org.civiceconomy.fiscal.BudgetFiscalServiceProvisioner;
import org.civiceconomy.fiscal.CancelBudgetDisbursementApproval;
import org.civiceconomy.fiscal.InitiateBudgetDisbursementApproval;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.ScheduleBudgetDisbursementApprovalPolicy;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV65MigrationTest {
    private static final Instant NOW = Instant.parse("2026-07-17T08:00:00Z");
    private static final NationId NATION_ID =
            new NationId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final UUID ACTOR =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final ServiceIdentity SERVICE =
            new ServiceIdentity("civiceconomy-budget-disbursement");

    @TempDir Path temporaryDirectory;

    @Test
    void v64PendingApprovalCanBeCancelledWithImmutableAudit() throws Exception {
        Path file = temporaryDirectory.resolve("v64-disbursement-cancellation.sqlite3");
        DatabaseIdentity identity = identity();
        UUID approvalRequestId;
        try (CivicDatabase current = CivicDatabase.open(file, identity)) {
            current.registerNation(
                    NATION_ID.value(),
                    "v65-migration",
                    "register-v65-migration-nation",
                    UUID.fromString("33333333-3333-3333-3333-333333333333"),
                    NOW.minusSeconds(120L).toEpochMilli());
            UUID budgetId = UUID.randomUUID();
            current.createBudget(
                    budgetId,
                    BudgetFiscalServiceProvisioner.SERVICE_IDENTITY.value(),
                    "create-v65-migration-budget",
                    "nation:" + NATION_ID.value() + ":treasury",
                    300L,
                    "PUBLIC_WORKS",
                    "v65 migration Budget",
                    NOW.plus(Duration.ofDays(30L)).toEpochMilli());
            current.approveBudget(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    BudgetFiscalServiceProvisioner.SERVICE_IDENTITY.value(),
                    "approve-v65-migration-budget",
                    budgetId,
                    ACTOR,
                    "Approve v65 migration Budget",
                    NOW.minusSeconds(60L).toEpochMilli());
            new BudgetDisbursementApprovalPolicyRegistry(
                            current,
                            Clock.fixed(NOW.minusSeconds(60L), ZoneOffset.UTC))
                    .schedule(new ScheduleBudgetDisbursementApprovalPolicy(
                            new ServiceIdentity("civiceconomy-budget-governance"),
                            "schedule-v65-migration-policy",
                            NATION_ID,
                            ACTOR,
                            List.of(new BudgetDisbursementApprovalTier(
                                    MoneyAmount.ZERO, 2)),
                            Duration.ofDays(7L),
                            NOW.minusSeconds(1L),
                            "Require two Citizens for migrated cancellation"));
            approvalRequestId = new BudgetDisbursementApprovalRegistry(
                            current, Clock.fixed(NOW, ZoneOffset.UTC))
                    .initiate(new InitiateBudgetDisbursementApproval(
                            SERVICE,
                            "v65-migrated-pending-approval",
                            NATION_ID,
                            budgetId,
                            new AccountId(
                                    "player:44444444-4444-4444-4444-444444444444"),
                            MoneyAmount.ofMinorUnits(200L),
                            ACTOR,
                            "Pending migrated approval"))
                    .approvalRequestId();
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute("DROP TABLE budget_disbursement_approval_cancellation");
            statement.execute("PRAGMA user_version = 64");
        }

        try (CivicDatabase migrated = CivicDatabase.open(file, identity)) {
            BudgetDisbursementApproval cancelled =
                    new BudgetDisbursementApprovalRegistry(
                                    migrated, Clock.fixed(NOW, ZoneOffset.UTC))
                            .cancel(new CancelBudgetDisbursementApproval(
                                    SERVICE,
                                    "cancel-v65-migrated-approval",
                                    approvalRequestId,
                                    ACTOR,
                                    "Cancel migrated procurement"));

            assertEquals(81, migrated.schemaVersion());
            assertEquals("CANCELLED", cancelled.state());
            assertEquals(ACTOR, cancelled.cancelledByPlayerId());
            assertEquals("Cancel migrated procurement", cancelled.cancellationReason());
            assertEquals(NOW, cancelled.cancelledAt());
        }
    }

    private static DatabaseIdentity identity() {
        return new DatabaseIdentity(
                UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff"),
                "0.1.0-probe",
                "1.21-2.3.0.5",
                "2101.1.10",
                "2101.1.20");
    }
}
