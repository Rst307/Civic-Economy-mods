package org.civiceconomy.territory;

import java.util.UUID;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;

public record SettleAvailableTerritoryMaintenance(
        ServiceIdentity serviceIdentity,
        String requestId,
        UUID cycleId,
        NationId nationId,
        AccountId treasuryAccount,
        int destructionBasisPoints,
        String reason) {
    public SettleAvailableTerritoryMaintenance {
        if (serviceIdentity == null
                || cycleId == null
                || nationId == null
                || treasuryAccount == null) {
            throw new IllegalArgumentException(
                    "Available Territory Maintenance settlement cannot contain null values");
        }
        if (requestId == null
                || requestId.isBlank()
                || reason == null
                || reason.isBlank()
                || destructionBasisPoints < 3_000
                || destructionBasisPoints > 8_000) {
            throw new IllegalArgumentException(
                    "Available Territory Maintenance settlement values are invalid");
        }
        AccountId expected = new AccountId("nation:" + nationId.value() + ":treasury");
        if (!expected.equals(treasuryAccount)) {
            throw new IllegalArgumentException(
                    "Available Territory Maintenance settlement must use the exact National Treasury");
        }
    }
}
