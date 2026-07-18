package org.civiceconomy.persistence;

import java.util.List;
import java.util.UUID;

public record StoredProductionInventoryExportLineage(
        UUID exportId,
        List<UUID> sourceReceiptIds) {
    public StoredProductionInventoryExportLineage {
        if (exportId == null || sourceReceiptIds == null || sourceReceiptIds.isEmpty()
                || sourceReceiptIds.stream().anyMatch(java.util.Objects::isNull)) {
            throw new IllegalArgumentException("Stored production inventory export lineage is invalid");
        }
        sourceReceiptIds = List.copyOf(sourceReceiptIds);
    }
}
