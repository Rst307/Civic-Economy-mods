package org.civiceconomy.territory;

@FunctionalInterface
public interface TerritoryClaimPermitConsumptionQueue {
    void submit(TerritoryClaimPermitConsumptionIntent intent);
}
