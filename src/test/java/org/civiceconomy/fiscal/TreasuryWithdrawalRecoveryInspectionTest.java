package org.civiceconomy.fiscal;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TreasuryWithdrawalRecoveryInspectionTest {
    private static final Instant NOW = Instant.parse("2026-07-16T12:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final ServiceIdentity SERVICE =
            new ServiceIdentity("civiceconomy-treasury-withdrawal");
    private static final NationId NATION_ID =
            new NationId(UUID.fromString("11111111-1111-1111-1111-111111111111"));
    private static final UUID ACTOR =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    @TempDir Path temporaryDirectory;

    @Test
    void statusListsBothRecoveryWindowsWithoutAdvancingEitherOne() {
        try (CivicDatabase database = database()) {
            database.registerNation(
                    NATION_ID.value(),
                    "withdrawal-recovery-inspection",
                    "register-nation",
                    UUID.fromString("33333333-3333-3333-3333-333333333333"),
                    NOW.minusSeconds(60L).toEpochMilli());
            TreasuryWithdrawalApprovalRegistry approvals =
                    new TreasuryWithdrawalApprovalRegistry(database, CLOCK);
            TreasuryWithdrawalApproval awaitingPreparation = approvals.initiate(
                    request("awaiting-preparation", 400L));
            TreasuryWithdrawalApproval preparedApproval = approvals.initiate(
                    request("prepared-operation", 500L));
            UUID withdrawalId = UUID.fromString(
                    "44444444-4444-4444-4444-444444444444");
            database.prepareTreasuryWithdrawal(
                    withdrawalId,
                    SERVICE.value(),
                    preparedApproval.requestId(),
                    preparedApproval.approvalRequestId(),
                    NATION_ID.value(),
                    preparedApproval.sourceAccount().value(),
                    ACTOR,
                    preparedApproval.amount().minorUnits(),
                    preparedApproval.reason(),
                    NOW.toEpochMilli());

            TreasuryWithdrawalRecoveryStatus status =
                    new TreasuryWithdrawalRecoveryInspection(database, CLOCK)
                            .status(SERVICE);

            assertEquals(
                    java.util.List.of(awaitingPreparation),
                    status.approvedWithoutOperation());
            assertEquals(1, status.preparedOperations().size());
            assertEquals(withdrawalId, status.preparedOperations().getFirst().withdrawalId());
            assertEquals("PREPARED", status.preparedOperations().getFirst().state());
            assertEquals(1, database.pendingTreasuryWithdrawalOperations(SERVICE.value()).size());
            assertEquals(
                    1,
                    database.approvedTreasuryWithdrawalApprovalsWithoutOperation(
                            SERVICE.value()).size());
        }
    }

    private static ConfirmTreasuryWithdrawal request(String requestId, long amount) {
        return new ConfirmTreasuryWithdrawal(
                SERVICE,
                requestId,
                NATION_ID,
                new AccountId("nation:" + NATION_ID.value() + ":treasury"),
                ACTOR,
                MoneyAmount.ofMinorUnits(amount),
                "Recovery inspection " + requestId);
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("treasury-withdrawal-recovery-inspection.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("55555555-5555-5555-5555-555555555555"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
