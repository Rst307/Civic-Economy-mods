package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredMintIssuanceOperation(
        UUID operationId,
        UUID batchId,
        String serviceIdentity,
        String requestId,
        String treasuryAccount,
        long amountMinorUnits,
        String state,
        String externalReference,
        String materialConsumptionReference,
        String reason,
        long preparedAtEpochMillis,
        Long externalAppliedAtEpochMillis,
        Long materialsConsumedAtEpochMillis,
        Long committedAtEpochMillis) {}
