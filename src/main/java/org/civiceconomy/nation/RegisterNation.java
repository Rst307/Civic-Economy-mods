package org.civiceconomy.nation;

import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;

public record RegisterNation(ServiceIdentity serviceIdentity, String requestId, UUID ftbTeamId) {
    public RegisterNation {
        if (serviceIdentity == null) {
            throw new IllegalArgumentException("Service identity cannot be null");
        }
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Request ID cannot be blank");
        }
        if (ftbTeamId == null) {
            throw new IllegalArgumentException("FTB Team ID cannot be null");
        }
    }
}
