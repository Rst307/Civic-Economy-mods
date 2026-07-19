package org.civiceconomy.nation;

import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;

public record LeaveCitizenship(
        ServiceIdentity serviceIdentity, String requestId, UUID playerId, NationId nationId) {
    public LeaveCitizenship {
        if (serviceIdentity == null || playerId == null || nationId == null) {
            throw new IllegalArgumentException("Citizenship leave request cannot contain null values");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Request ID cannot be blank");
        }
    }
}
