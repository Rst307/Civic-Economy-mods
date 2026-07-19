package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredDatabaseBackupOperation(
        UUID operationId,
        String administratorIdentity,
        String requestId,
        String fileName,
        String reason,
        String state,
        long sizeBytes,
        String sha256,
        long preparedAtEpochMillis,
        Long committedAtEpochMillis,
        Long retiredAtEpochMillis) {}
