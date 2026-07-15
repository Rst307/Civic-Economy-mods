package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredTreasuryWithdrawalOperation(
        UUID withdrawalId,
        String serviceIdentity,
        String requestId,
        UUID nationId,
        String sourceAccount,
        UUID actorPlayerId,
        long amountMinorUnits,
        String reason,
        String state,
        long preparedAtEpochMillis,
        Long committedAtEpochMillis) {}
