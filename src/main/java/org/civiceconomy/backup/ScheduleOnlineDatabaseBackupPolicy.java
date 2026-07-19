package org.civiceconomy.backup;

import java.time.Instant;
import org.civiceconomy.fiscal.ServiceIdentity;

public record ScheduleOnlineDatabaseBackupPolicy(
        ServiceIdentity serviceIdentity,
        String requestId,
        String actorIdentity,
        OnlineDatabaseBackupPolicy policy,
        Instant effectiveAt,
        String reason) {
    public ScheduleOnlineDatabaseBackupPolicy {
        if (serviceIdentity == null || policy == null || effectiveAt == null
                || requestId == null || requestId.isBlank()
                || actorIdentity == null || actorIdentity.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Online database backup policy request is invalid");
        }
    }
}
