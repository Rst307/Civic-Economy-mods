package org.civiceconomy.strength;

import java.time.Instant;
import java.util.UUID;

public record MintCompliancePolicyVersion(
        UUID policyId,
        MintCompliancePolicy policy,
        Instant effectiveAt,
        String actorIdentity,
        String reason,
        Instant recordedAt) {
    public MintCompliancePolicyVersion {
        if (policyId == null || policy == null || effectiveAt == null || recordedAt == null
                || actorIdentity == null || actorIdentity.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Mint Compliance policy version is invalid");
        }
    }
}
