package org.civiceconomy.nation;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public record NationApplicationCandidate(
        NationApplicationId applicationId,
        UUID playerId,
        Instant affiliatedAt,
        Optional<Instant> endedAt) {
    public NationApplicationCandidate {
        if (applicationId == null || playerId == null || affiliatedAt == null || endedAt == null) {
            throw new IllegalArgumentException("Nation Application candidate fields cannot be null");
        }
    }
}
