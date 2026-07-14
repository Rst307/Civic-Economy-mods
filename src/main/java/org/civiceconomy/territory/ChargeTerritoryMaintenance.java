package org.civiceconomy.territory;

import java.util.UUID;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;

public record ChargeTerritoryMaintenance(
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID cycleId,
        NationId nationId,
        AccountId treasuryAccount,
        MoneyAmount totalDue,
        int destructionBasisPoints,
        String reason) {
    public ChargeTerritoryMaintenance {
        if (serviceIdentity == null
                || cycleId == null
                || nationId == null
                || treasuryAccount == null
                || totalDue == null) {
            throw new IllegalArgumentException("Territory maintenance charge cannot contain null values");
        }
        if (requestId == null
                || requestId.isBlank()
                || reason == null
                || reason.isBlank()
                || totalDue.equals(MoneyAmount.ZERO)
                || destructionBasisPoints < 3_000
                || destructionBasisPoints > 8_000) {
            throw new IllegalArgumentException("Territory maintenance charge values are invalid");
        }
        AccountId expected = new AccountId("nation:" + nationId.value() + ":treasury");
        if (!expected.equals(treasuryAccount)) {
            throw new IllegalArgumentException(
                    "Territory maintenance charge must use the exact National Treasury");
        }
    }
}
