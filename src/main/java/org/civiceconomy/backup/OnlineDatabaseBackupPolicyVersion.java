package org.civiceconomy.backup;

import java.time.Instant;
import java.util.UUID;

public record OnlineDatabaseBackupPolicyVersion(
        UUID policyId,
        OnlineDatabaseBackupPolicy policy,
        Instant effectiveAt,
        String actorIdentity,
        String reason,
        Instant recordedAt) {
    public OnlineDatabaseBackupPolicyVersion {
        if (policyId == null || policy == null || effectiveAt == null || recordedAt == null
                || actorIdentity == null || actorIdentity.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Online database backup policy version is invalid");
        }
    }
}
