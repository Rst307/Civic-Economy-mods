package org.civiceconomy.persistence;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.BudgetDisbursementApproval;
import org.civiceconomy.fiscal.BudgetDisbursementApprovalRegistry;
import org.civiceconomy.fiscal.BudgetFiscalServiceProvisioner;
import org.civiceconomy.fiscal.InitiateBudgetDisbursementApproval;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CivicDatabaseV64MigrationTest {
    private static final Instant NOW = Instant.parse("2026-07-17T03:00:00Z");
    private static final NationId NATION_ID =
            new NationId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final UUID ACTOR =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    @TempDir Path temporaryDirectory;

    @Test
    void v63DatabaseCanCreateDefaultGovernedBudgetDisbursementApproval() throws Exception {
        Path file = temporaryDirectory.resolve("v63-budget-disbursement.sqlite3");
        DatabaseIdentity identity = identity();
        UUID budgetId;
        try (CivicDatabase current = CivicDatabase.open(file, identity)) {
            current.registerNation(
                    NATION_ID.value(),
                    "v64-migration",
                    "register-v64-migration-nation",
                    UUID.fromString("33333333-3333-3333-3333-333333333333"),
                    NOW.minusSeconds(120L).toEpochMilli());
            budgetId = UUID.randomUUID();
            current.createBudget(
                    budgetId,
                    BudgetFiscalServiceProvisioner.SERVICE_IDENTITY.value(),
                    "create-v64-migration-budget",
                    "nation:" + NATION_ID.value() + ":treasury",
                    300L,
                    "PUBLIC_WORKS",
                    "v64 migration Budget",
                    NOW.plus(Duration.ofDays(30L)).toEpochMilli());
            current.approveBudget(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    BudgetFiscalServiceProvisioner.SERVICE_IDENTITY.value(),
                    "approve-v64-migration-budget",
                    budgetId,
                    ACTOR,
                    "Approve v64 migration Budget",
                    NOW.minusSeconds(60L).toEpochMilli());
        }
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + file);
                var statement = connection.createStatement()) {
            statement.execute("DROP TABLE budget_disbursement_approval_vote");
            statement.execute("DROP TABLE budget_disbursement_approval_request");
            statement.execute("DROP TABLE budget_disbursement_approval_policy_tier");
            statement.execute("DROP TABLE budget_disbursement_approval_policy");
            statement.execute("PRAGMA user_version = 63");
        }

        try (CivicDatabase migrated = CivicDatabase.open(file, identity)) {
            BudgetDisbursementApproval approval =
                    new BudgetDisbursementApprovalRegistry(
                                    migrated, Clock.fixed(NOW, ZoneOffset.UTC))
                            .initiate(new InitiateBudgetDisbursementApproval(
                                    new ServiceIdentity("civiceconomy-budget-disbursement"),
                                    "migrated-default-approval",
                                    NATION_ID,
                                    budgetId,
                                    new AccountId(
                                            "player:44444444-4444-4444-4444-444444444444"),
                                    MoneyAmount.ofMinorUnits(100L),
                                    ACTOR,
                                    "Default single-Citizen migrated approval"));
            StoredBudget budget = migrated.budget(budgetId);

            assertEquals(94, migrated.schemaVersion());
            assertEquals("APPROVED", approval.state());
            assertEquals(1, approval.requiredApprovals());
            assertEquals("APPROVED", budget.state());
            assertEquals(0L, budget.settledMinorUnits());
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
