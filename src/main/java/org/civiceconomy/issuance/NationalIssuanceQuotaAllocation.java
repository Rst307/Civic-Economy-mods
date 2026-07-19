package org.civiceconomy.issuance;

import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.nation.NationId;

public record NationalIssuanceQuotaAllocation(NationId nationId, MoneyAmount ceiling) {
    public NationalIssuanceQuotaAllocation {
        if (nationId == null || ceiling == null) {
            throw new IllegalArgumentException("National Issuance Quota allocation cannot contain null values");
        }
    }
}
