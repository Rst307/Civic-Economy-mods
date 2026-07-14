package org.civiceconomy.territory;

import java.util.List;
import org.civiceconomy.fiscal.MoneyAmount;

public record TerritoryMaintenancePriorityDecision(
        List<TerritoryMaintenanceCandidate> funded,
        List<TerritoryMaintenanceCandidate> suspended,
        MoneyAmount fundedAmount,
        MoneyAmount unspentAmount) {
    public TerritoryMaintenancePriorityDecision {
        if (funded == null
                || suspended == null
                || fundedAmount == null
                || unspentAmount == null) {
            throw new IllegalArgumentException(
                    "Territory Maintenance priority decision cannot contain null values");
        }
        funded = List.copyOf(funded);
        suspended = List.copyOf(suspended);
    }
}
