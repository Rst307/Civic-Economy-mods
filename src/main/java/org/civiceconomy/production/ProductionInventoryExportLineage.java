package org.civiceconomy.production;

import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/** Immutable projection of the Receipt sources allocated to one durable export event. */
public record ProductionInventoryExportLineage(
        UUID exportId,
        List<UUID> sourceReceiptIds) {
    public ProductionInventoryExportLineage {
        if (exportId == null || sourceReceiptIds == null || sourceReceiptIds.isEmpty()
                || sourceReceiptIds.stream().anyMatch(java.util.Objects::isNull)
                || new HashSet<>(sourceReceiptIds).size() != sourceReceiptIds.size()) {
            throw new IllegalArgumentException("Production inventory export lineage is invalid");
        }
        sourceReceiptIds = List.copyOf(sourceReceiptIds);
    }
}
