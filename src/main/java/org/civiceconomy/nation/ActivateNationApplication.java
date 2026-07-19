package org.civiceconomy.nation;

import org.civiceconomy.fiscal.ServiceIdentity;

public record ActivateNationApplication(
        ServiceIdentity serviceIdentity,
        String requestId,
        NationApplicationId applicationId,
        Capital capital,
        String reason) {
    public ActivateNationApplication {
        if (serviceIdentity == null || applicationId == null || capital == null) {
            throw new IllegalArgumentException("Nation activation identity and Capital cannot be null");
        }
        if (requestId == null || requestId.isBlank() || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Nation activation request and reason are required");
        }
    }
}
