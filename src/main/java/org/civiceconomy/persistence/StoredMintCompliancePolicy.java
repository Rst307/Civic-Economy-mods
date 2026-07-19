package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredMintCompliancePolicy(
        UUID policyId,
        String serviceIdentity,
        String requestId,
        String actorIdentity,
        long observationWindowMillis,
        int recoveredCommitBasisPoints,
        long effectiveAtEpochMillis,
        String reason,
        long recordedAtEpochMillis) {}
