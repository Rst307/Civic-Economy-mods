package org.civiceconomy.fiscal;

import java.time.Clock;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredTreasuryWithdrawalOperation;

public final class TreasuryWithdrawalRecoveryInspection {
    private final CivicDatabase database;
    private final TreasuryWithdrawalApprovalRegistry approvals;

    public TreasuryWithdrawalRecoveryInspection(CivicDatabase database, Clock clock) {
        if (database == null || clock == null) {
            throw new IllegalArgumentException(
                    "Treasury Withdrawal recovery inspection dependencies cannot be null");
        }
        this.database = database;
        this.approvals = new TreasuryWithdrawalApprovalRegistry(database, clock);
    }

    public TreasuryWithdrawalRecoveryStatus status(ServiceIdentity serviceIdentity) {
        if (serviceIdentity == null) {
            throw new IllegalArgumentException(
                    "Treasury Withdrawal recovery Service Identity cannot be null");
        }
        return new TreasuryWithdrawalRecoveryStatus(
                approvals.approvedWithoutOperation(serviceIdentity),
                database.pendingTreasuryWithdrawalOperations(serviceIdentity.value()).stream()
                        .map(TreasuryWithdrawalRecoveryInspection::toWithdrawal)
                        .toList());
    }

    private static TreasuryWithdrawal toWithdrawal(
            StoredTreasuryWithdrawalOperation operation) {
        return new TreasuryWithdrawal(
                operation.withdrawalId(),
                new ServiceIdentity(operation.serviceIdentity()),
                operation.requestId(),
                operation.approvalRequestId(),
                new NationId(operation.nationId()),
                new AccountId(operation.sourceAccount()),
                operation.actorPlayerId(),
                MoneyAmount.ofMinorUnits(operation.amountMinorUnits()),
                operation.reason(),
                operation.state(),
                java.time.Instant.ofEpochMilli(operation.preparedAtEpochMillis()),
                operation.committedAtEpochMillis() == null
                        ? null
                        : java.time.Instant.ofEpochMilli(
                                operation.committedAtEpochMillis()));
    }
}
