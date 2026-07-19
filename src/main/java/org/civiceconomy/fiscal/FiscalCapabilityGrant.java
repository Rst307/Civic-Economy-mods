package org.civiceconomy.fiscal;

import java.time.Instant;
import java.util.UUID;

public record FiscalCapabilityGrant(
        UUID grantId,
        ServiceIdentity administrator,
        String requestId,
        ServiceIdentity serviceIdentity,
        FiscalCapability capability,
        AccountId accountId,
        String reason,
        Instant grantedAt) {}
