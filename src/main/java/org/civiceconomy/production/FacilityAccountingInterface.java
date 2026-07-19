package org.civiceconomy.production;

import java.time.Instant;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;

public record FacilityAccountingInterface(
        UUID interfaceId,
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID facilityId,
        FacilityAccountingInterfacePosition position,
        UUID actorPlayerId,
        String reason,
        Instant registeredAt) {}
