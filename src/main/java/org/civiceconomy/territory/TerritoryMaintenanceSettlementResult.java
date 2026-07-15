package org.civiceconomy.territory;

import java.util.Optional;

public record TerritoryMaintenanceSettlementResult(
        Optional<TerritoryMaintenancePayment> payment,
        TerritoryMaintenanceSettlement settlement) {
    public TerritoryMaintenanceSettlementResult {
        if (payment == null || settlement == null) {
            throw new IllegalArgumentException(
                    "Territory Maintenance settlement result cannot contain null values");
        }
        if (payment.isPresent() && !payment.orElseThrow().settlement().equals(settlement)) {
            throw new IllegalArgumentException(
                    "Territory Maintenance payment must reference the returned Settlement");
        }
    }
}
