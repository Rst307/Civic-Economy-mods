package org.civiceconomy.strength;

import java.time.Instant;
import java.util.UUID;

public record AuditableEconomicActivityPolicyVersion(
        UUID policyId,
        AuditableEconomicActivityPolicy policy,
        Instant effectiveAt,
        String actorIdentity,
        String reason,
        Instant recordedAt) {
    public AuditableEconomicActivityPolicyVersion {
        if (policyId == null || policy == null || effectiveAt == null || recordedAt == null
                || actorIdentity == null || actorIdentity.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Auditable Economic Activity policy version is invalid");
        }
    }
}
