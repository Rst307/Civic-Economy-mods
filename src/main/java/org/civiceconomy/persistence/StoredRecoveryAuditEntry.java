package org.civiceconomy.persistence;

import java.time.Instant;
import java.util.UUID;

public record StoredRecoveryAuditEntry(
        UUID auditId,
        UUID transactionId,
        String action,
        String serviceIdentity,
        String detail,
        Instant recordedAt) {}
