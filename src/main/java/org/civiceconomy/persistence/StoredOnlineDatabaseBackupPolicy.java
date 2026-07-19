package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredOnlineDatabaseBackupPolicy(
        UUID policyId,
        String serviceIdentity,
        String requestId,
        String actorIdentity,
        long intervalMillis,
        int retention,
        long effectiveAtEpochMillis,
        String reason,
        long recordedAtEpochMillis) {}
