package org.civiceconomy.issuance;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.ServiceIdentity;

public record IssuanceQuotaPeriod(
        UUID periodId,
        ServiceIdentity serviceIdentity,
        String requestId,
        Instant startsAt,
        Instant endsAt,
        MoneyAmount hardCap,
        MoneyAmount globalQuota,
        List<NationalIssuanceQuota> nationalQuotas,
        String reason,
        Instant publishedAt) {
    public IssuanceQuotaPeriod {
        nationalQuotas = List.copyOf(nationalQuotas);
    }
}
