package org.civiceconomy.nation;

import java.time.Instant;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;

public record CreateNationApplication(
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID ftbTeamId,
        UUID applicantPlayerId,
        Instant expiresAt) {
    public CreateNationApplication {
        if (serviceIdentity == null || ftbTeamId == null || applicantPlayerId == null || expiresAt == null) {
            throw new IllegalArgumentException("Nation Application fields cannot be null");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Nation Application request ID cannot be blank");
        }
    }
}
