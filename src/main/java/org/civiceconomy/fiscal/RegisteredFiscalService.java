package org.civiceconomy.fiscal;

import java.time.Instant;

public record RegisteredFiscalService(
        ServiceIdentity serviceIdentity,
        String ownerModId,
        String displayName,
        ServiceIdentity administrator,
        String requestId,
        String reason,
        Instant registeredAt) {}
