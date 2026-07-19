package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredEscrowExpiry(
        UUID expiryId,
        String serviceIdentity,
        String requestId,
        UUID escrowId,
        long expiredAtEpochMillis) {}
