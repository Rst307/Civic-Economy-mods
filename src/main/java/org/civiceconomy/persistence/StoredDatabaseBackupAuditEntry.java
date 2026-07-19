package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredDatabaseBackupAuditEntry(
        UUID auditId,
        UUID operationId,
        String action,
        String detail,
        long recordedAtEpochMillis) {}
