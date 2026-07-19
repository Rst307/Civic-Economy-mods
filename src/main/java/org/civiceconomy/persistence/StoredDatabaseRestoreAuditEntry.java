package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredDatabaseRestoreAuditEntry(
        UUID auditId,
        UUID operationId,
        String action,
        String actorIdentity,
        String detail,
        long recordedAtEpochMillis) {}
