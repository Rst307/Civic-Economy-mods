package org.civiceconomy.nation;

import java.time.Duration;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;

public record CancelNationApplication(
        ServiceIdentity serviceIdentity,
        String requestId,
        NationApplicationId applicationId,
        UUID applicantPlayerId,
        Duration observationWindow,
        String reason) {
    public CancelNationApplication {
        if (serviceIdentity == null || applicationId == null || applicantPlayerId == null
                || observationWindow == null) {
            throw new IllegalArgumentException("Nation Application cancellation identity cannot be null");
        }
        if (requestId == null || requestId.isBlank() || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Nation Application cancellation request and reason are required");
        }
        if (observationWindow.isZero() || observationWindow.isNegative()) {
            throw new IllegalArgumentException(
                    "Nation Application cancellation observation window must be positive");
        }
    }
}
