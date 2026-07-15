package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredPermanentDestructionOperation(
        UUID operationId,
        String serviceIdentity,
        String requestId,
        String sourceAccount,
        long amountMinorUnits,
        String operatorIdentity,
        String reason,
        String state,
        long preparedAtEpochMillis,
        Long committedAtEpochMillis) {}
