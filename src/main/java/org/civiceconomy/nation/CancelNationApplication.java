package org.civiceconomy.nation;

import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;

public record CancelNationApplication(
        ServiceIdentity serviceIdentity,
        String requestId,
        NationApplicationId applicationId,
        UUID applicantPlayerId,
        String reason) {
    public CancelNationApplication {
        if (serviceIdentity == null || applicationId == null || applicantPlayerId == null) {
            throw new IllegalArgumentException("Nation Application cancellation identity cannot be null");
        }
        if (requestId == null || requestId.isBlank() || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Nation Application cancellation request and reason are required");
        }
    }
}
