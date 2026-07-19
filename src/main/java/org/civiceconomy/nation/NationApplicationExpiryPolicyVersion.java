package org.civiceconomy.nation;

import java.time.Instant;
import java.util.UUID;

public record NationApplicationExpiryPolicyVersion(
        UUID policyId,
        NationApplicationExpiryPolicy policy,
        Instant effectiveAt,
        String actorIdentity,
        String reason,
        Instant recordedAt) {
    public NationApplicationExpiryPolicyVersion {
        if (policyId == null || policy == null || effectiveAt == null || recordedAt == null
                || actorIdentity == null || actorIdentity.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Nation Application expiry policy version is invalid");
        }
    }
}
