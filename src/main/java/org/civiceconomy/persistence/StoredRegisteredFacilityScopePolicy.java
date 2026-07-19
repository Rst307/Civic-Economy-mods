package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredRegisteredFacilityScopePolicy(
        UUID policyId,
        String serviceIdentity,
        String requestId,
        String actorIdentity,
        int maxScopeChunks,
        long effectiveAtEpochMillis,
        String reason,
        long recordedAtEpochMillis) {}
