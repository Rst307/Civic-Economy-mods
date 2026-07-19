package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredDatabaseRestoreOperation(
        UUID operationId,
        String administratorIdentity,
        String requestId,
        UUID sourceBackupOperationId,
        String sourceFileName,
        long sourceSizeBytes,
        String sourceSha256,
        UUID rollbackBackupOperationId,
        String rollbackFileName,
        long rollbackSizeBytes,
        String rollbackSha256,
        String state,
        String reason,
        long stagedAtEpochMillis,
        Long activatedAtEpochMillis,
        String cancellationAdministratorIdentity,
        String cancellationRequestId,
        String cancellationReason,
        Long cancelledAtEpochMillis) {}
