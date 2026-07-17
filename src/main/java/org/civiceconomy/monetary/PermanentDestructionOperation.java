package org.civiceconomy.monetary;

import java.time.Instant;
import java.util.UUID;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.ServiceIdentity;

public record PermanentDestructionOperation(
        UUID operationId,
        ServiceIdentity serviceIdentity,
        String requestId,
        AccountId sourceAccount,
        MoneyAmount amount,
        String operatorIdentity,
        String reason,
        String state,
        Instant preparedAt,
        Instant externalAppliedAt,
        Instant committedAt) {
    public PermanentDestructionOperation {
        if (operationId == null
                || serviceIdentity == null
                || requestId == null
                || requestId.isBlank()
                || sourceAccount == null
                || amount == null
                || amount.minorUnits() == 0L
                || operatorIdentity == null
                || operatorIdentity.isBlank()
                || reason == null
                || reason.isBlank()
                || state == null
                || state.isBlank()
                || preparedAt == null) {
            throw new IllegalArgumentException(
                    "Permanent Destruction Operation values are invalid");
        }
    }
}
