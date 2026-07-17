package org.civiceconomy.production;

import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;

public record CaptureFacilityAccountingBaseline(
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID baselineId,
        UUID facilityId,
        UUID actorPlayerId,
        String reason) {
    public CaptureFacilityAccountingBaseline {
        if (serviceIdentity == null || requestId == null || requestId.isBlank()
                || baselineId == null || facilityId == null || actorPlayerId == null
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Facility Accounting Baseline capture request is invalid");
        }
    }
}
