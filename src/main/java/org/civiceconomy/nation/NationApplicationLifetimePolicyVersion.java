package org.civiceconomy.nation;

import java.time.Instant;
import java.util.UUID;

public record NationApplicationLifetimePolicyVersion(
        UUID policyId,
        NationApplicationLifetimePolicy policy,
        Instant effectiveAt,
        String actorIdentity,
        String reason,
        Instant recordedAt) {
    public NationApplicationLifetimePolicyVersion {
        if (policyId == null || policy == null || effectiveAt == null || recordedAt == null
                || actorIdentity == null || actorIdentity.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Nation Application lifetime policy version is invalid");
        }
    }
}
