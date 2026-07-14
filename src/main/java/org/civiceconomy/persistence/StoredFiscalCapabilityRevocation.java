package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredFiscalCapabilityRevocation(
        UUID revocationId,
        UUID grantId,
        String administratorIdentity,
        String requestId,
        String reason,
        long revokedAtEpochMillis) {}
