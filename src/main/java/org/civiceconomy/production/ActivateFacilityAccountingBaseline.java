package org.civiceconomy.production;

import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;

public record ActivateFacilityAccountingBaseline(
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID facilityId,
        UUID actorPlayerId,
        String reason) {
    public ActivateFacilityAccountingBaseline {
        if (serviceIdentity == null || requestId == null || requestId.isBlank()
                || facilityId == null || actorPlayerId == null
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Facility Accounting Baseline activation request is invalid");
        }
    }
}
