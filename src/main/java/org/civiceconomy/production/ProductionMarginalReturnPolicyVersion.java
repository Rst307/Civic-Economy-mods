package org.civiceconomy.production;

import java.time.Instant;
import java.util.UUID;

public record ProductionMarginalReturnPolicyVersion(
        UUID policyId,
        ProductionMarginalReturnPolicy policy,
        Instant effectiveAt,
        String actorIdentity,
        String reason,
        Instant recordedAt) {
    public ProductionMarginalReturnPolicyVersion {
        if (policyId == null || policy == null || effectiveAt == null || recordedAt == null
                || actorIdentity == null || actorIdentity.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Production Marginal Return policy version is invalid");
        }
    }
}
