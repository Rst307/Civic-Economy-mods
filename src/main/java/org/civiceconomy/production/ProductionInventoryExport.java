package org.civiceconomy.production;

import java.util.UUID;

public record ProductionInventoryExport(
        UUID exportId,
        ProductionInventoryExportRequest request,
        ProductionStack exportedStack,
        ProductionInventoryConsumption consumption) {
    public ProductionInventoryExport {
        if (exportId == null || request == null || exportedStack == null
                || exportedStack.isEmpty() || exportedStack.count() != request.count()
                || consumption == null || !consumption.interfaceId().equals(request.interfaceId())
                || !consumption.serviceIdentity().equals(request.serviceIdentity())
                || !consumption.requestId().equals(request.requestId())
                || consumption.consumedCount() != request.count()) {
            throw new IllegalArgumentException("Production inventory export is invalid");
        }
    }
}
