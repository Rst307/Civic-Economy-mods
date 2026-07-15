package org.civiceconomy.persistence;

import java.util.UUID;

public record StoredNationalIssuanceQuotaAllocation(UUID nationId, long ceilingMinorUnits) {
    public StoredNationalIssuanceQuotaAllocation {
        if (nationId == null || ceilingMinorUnits < 0L) {
            throw new IllegalArgumentException("National Issuance Quota allocation is invalid");
        }
    }
}
