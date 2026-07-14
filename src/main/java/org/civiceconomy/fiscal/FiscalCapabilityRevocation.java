package org.civiceconomy.fiscal;

import java.time.Instant;
import java.util.UUID;

public record FiscalCapabilityRevocation(
        UUID revocationId,
        UUID grantId,
        FiscalCapabilityGrant grant,
        ServiceIdentity administrator,
        String requestId,
        String reason,
        Instant revokedAt) {}
