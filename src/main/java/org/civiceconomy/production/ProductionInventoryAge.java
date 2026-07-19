package org.civiceconomy.production;

public record ProductionInventoryAge(long firstObservedAtEpochMillis) {
    public ProductionInventoryAge {
        if (firstObservedAtEpochMillis < 0L) {
            throw new IllegalArgumentException("Production inventory age must start at a valid time");
        }
    }
}
