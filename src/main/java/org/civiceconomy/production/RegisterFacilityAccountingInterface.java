package org.civiceconomy.production;

import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;

public record RegisterFacilityAccountingInterface(
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID interfaceId,
        UUID facilityId,
        FacilityAccountingInterfacePosition position,
        UUID actorPlayerId,
        String reason) {
    public RegisterFacilityAccountingInterface {
        if (serviceIdentity == null || interfaceId == null || facilityId == null
                || position == null || actorPlayerId == null) {
            throw new IllegalArgumentException(
                    "Facility Accounting Interface request cannot contain null values");
        }
        if (requestId == null || requestId.isBlank()
                || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Facility Accounting Interface request values are invalid");
        }
    }
}
