package org.civiceconomy.production;

import java.util.UUID;

public record ProductionInventoryAgeBatch(
        UUID batchId,
        UUID receiptId,
        UUID interfaceId,
        int slot,
        ProductionStack stack,
        int remainingCount,
        ProductionInventoryAge age) {
    public ProductionInventoryAgeBatch {
        if (batchId == null || receiptId == null || interfaceId == null || slot < 0
                || stack == null || stack.isEmpty() || remainingCount < 0
                || remainingCount > stack.count() || age == null) {
            throw new IllegalArgumentException("Production inventory age batch is invalid");
        }
    }

    public ProductionInventoryAgeBatch withRemainingCount(int newRemainingCount) {
        return new ProductionInventoryAgeBatch(
                batchId, receiptId, interfaceId, slot, stack, newRemainingCount, age);
    }
}
