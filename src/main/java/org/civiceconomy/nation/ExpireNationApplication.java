package org.civiceconomy.nation;

import java.time.Duration;
import org.civiceconomy.fiscal.ServiceIdentity;

public record ExpireNationApplication(
        ServiceIdentity serviceIdentity,
        String requestId,
        NationApplicationId applicationId,
        Duration observationWindow,
        String reason) {
    public ExpireNationApplication {
        if (serviceIdentity == null || applicationId == null || observationWindow == null) {
            throw new IllegalArgumentException("Nation Application expiry identity cannot be null");
        }
        if (requestId == null || requestId.isBlank() || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Nation Application expiry request and reason are required");
        }
        if (observationWindow.isZero() || observationWindow.isNegative()) {
            throw new IllegalArgumentException(
                    "Nation Application expiry observation window must be positive");
        }
    }
}
