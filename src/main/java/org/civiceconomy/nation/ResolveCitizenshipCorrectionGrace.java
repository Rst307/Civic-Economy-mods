package org.civiceconomy.nation;

import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;

public record ResolveCitizenshipCorrectionGrace(
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID graceId,
        CitizenshipCorrectionResolution resolution,
        String reason) {
    public ResolveCitizenshipCorrectionGrace {
        if (serviceIdentity == null || graceId == null || resolution == null) {
            throw new IllegalArgumentException("Citizenship Correction Grace resolution identity cannot be null");
        }
        if (requestId == null || requestId.isBlank() || reason == null || reason.isBlank()) {
            throw new IllegalArgumentException(
                    "Citizenship Correction Grace resolution request and reason are required");
        }
    }
}
