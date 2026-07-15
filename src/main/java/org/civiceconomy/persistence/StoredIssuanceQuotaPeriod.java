package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredIssuanceQuotaPeriod(
        UUID periodId,
        String serviceIdentity,
        String requestId,
        long startsAtEpochMillis,
        long endsAtEpochMillis,
        long hardCapMinorUnits,
        long globalQuotaMinorUnits,
        String reason,
        long publishedAtEpochMillis) {}
