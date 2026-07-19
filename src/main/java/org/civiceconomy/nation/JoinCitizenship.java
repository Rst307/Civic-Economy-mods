package org.civiceconomy.nation;

import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;

public record JoinCitizenship(
        ServiceIdentity serviceIdentity, String requestId, UUID playerId, NationId nationId) {
    public JoinCitizenship {
        if (serviceIdentity == null || playerId == null || nationId == null) {
            throw new IllegalArgumentException("Citizenship join request cannot contain null values");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Request ID cannot be blank");
        }
    }
}
