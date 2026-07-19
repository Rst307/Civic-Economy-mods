package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredFiscalBillExpiry(
        UUID expiryId,
        UUID billId,
        String serviceIdentity,
        String requestId,
        long expiredAtEpochMillis) {}
