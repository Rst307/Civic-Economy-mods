package org.civiceconomy.nation;

import java.util.UUID;

public record NationApplicationEvidence(
        NationApplicationId applicationId, UUID playerId, long attributedMillis) {
    public NationApplicationEvidence {
        if (applicationId == null || playerId == null) {
            throw new IllegalArgumentException("Nation Application evidence identity cannot be null");
        }
        if (attributedMillis <= 0) {
            throw new IllegalArgumentException("Nation Application evidence must be positive");
        }
    }
}
