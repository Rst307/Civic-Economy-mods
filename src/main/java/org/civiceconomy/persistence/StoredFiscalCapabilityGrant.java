package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredFiscalCapabilityGrant(
        UUID grantId,
        String administratorIdentity,
        String requestId,
        String serviceIdentity,
        String capability,
        String accountId,
        String reason,
        long grantedAtEpochMillis) {}
