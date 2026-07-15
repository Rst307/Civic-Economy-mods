package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredMintBatch(
        UUID batchId,
        String serviceIdentity,
        String requestId,
        UUID mintId,
        UUID periodId,
        UUID nationId,
        UUID recipeVersionId,
        long issuedMinorUnits,
        UUID actorPlayerId,
        String state,
        String custodyState,
        String custodyServiceIdentity,
        String custodyRequestId,
        String custodyExternalReference,
        String reason,
        String cancellationServiceIdentity,
        String cancellationRequestId,
        UUID cancellationActorPlayerId,
        String cancellationReason,
        Long cancellationPreparedAtEpochMillis,
        String returnServiceIdentity,
        String returnRequestId,
        String returnExternalReference,
        Long cancelledAtEpochMillis,
        long preparedAtEpochMillis,
        Long processingStartedAtEpochMillis,
        Long processingCompletesAtEpochMillis) {}
