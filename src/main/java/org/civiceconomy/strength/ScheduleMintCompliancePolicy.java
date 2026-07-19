package org.civiceconomy.strength;

import java.time.Instant;
import org.civiceconomy.fiscal.ServiceIdentity;

public record ScheduleMintCompliancePolicy(
        ServiceIdentity serviceIdentity,
        String requestId,
        String actorIdentity,
        MintCompliancePolicy policy,
        Instant effectiveAt,
        String reason) {
    public ScheduleMintCompliancePolicy {
        if (serviceIdentity == null || policy == null || effectiveAt == null
                || requestId == null || requestId.isBlank()
                || actorIdentity == null || actorIdentity.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Mint Compliance policy request is invalid");
        }
    }
}
