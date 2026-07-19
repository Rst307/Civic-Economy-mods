package org.civiceconomy.issuance;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.ServiceIdentity;

public record PublishIssuanceQuotaPeriod(
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID periodId,
        Instant startsAt,
        Instant endsAt,
        MoneyAmount globalQuota,
        List<NationalIssuanceQuotaAllocation> allocations,
        String reason) {
    public PublishIssuanceQuotaPeriod {
        if (serviceIdentity == null
                || periodId == null
                || startsAt == null
                || endsAt == null
                || globalQuota == null
                || allocations == null) {
            throw new IllegalArgumentException("Issuance Quota Period request cannot contain null values");
        }
        allocations = List.copyOf(allocations);
        if (requestId == null
                || requestId.isBlank()
                || reason == null
                || reason.isBlank()
                || !endsAt.isAfter(startsAt)) {
            throw new IllegalArgumentException("Issuance Quota Period request values are invalid");
        }
    }
}
