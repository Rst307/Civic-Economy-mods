package org.civiceconomy.fiscal;

import java.time.Instant;
import java.util.UUID;

public record RecoveryAuditEntry(
        UUID auditId,
        UUID transactionId,
        RecoveryAction action,
        ServiceIdentity serviceIdentity,
        String detail,
        Instant recordedAt) {}
