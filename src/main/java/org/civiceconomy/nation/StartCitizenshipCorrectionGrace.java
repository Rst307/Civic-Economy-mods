package org.civiceconomy.nation;

import java.time.Instant;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;

public record StartCitizenshipCorrectionGrace(
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID citizenshipId,
        UUID playerId,
        NationId nationId,
        UUID ftbTeamId,
        Instant deadline,
        String reason) {
    public StartCitizenshipCorrectionGrace {
        if (serviceIdentity == null || citizenshipId == null || playerId == null
                || nationId == null || ftbTeamId == null || deadline == null) {
            throw new IllegalArgumentException("Citizenship Correction Grace identity cannot be null");
        }
        if (requestId == null || requestId.isBlank() || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("Citizenship Correction Grace request and reason are required");
        }
    }
}
