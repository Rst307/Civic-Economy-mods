package org.civiceconomy.production;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredProductionInventoryExport;

public final class ProductionInventoryExportCoordinator {
    private final CivicDatabase database;
    private final Clock clock;
    private final ProductionInventoryExportSource source;

    public ProductionInventoryExportCoordinator(
            CivicDatabase database, Clock clock, ProductionInventoryExportSource source) {
        if (database == null || clock == null || source == null) {
            throw new IllegalArgumentException("Production inventory export dependencies are required");
        }
        this.database = database;
        this.clock = clock;
        this.source = source;
    }

    public ProductionInventoryExport export(ProductionInventoryExportRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Production inventory export request is required");
        }
        UUID exportId = UUID.nameUUIDFromBytes((request.serviceIdentity().value()
                + ":production-inventory-export:" + request.requestId())
                .getBytes(StandardCharsets.UTF_8));
        StoredProductionInventoryExport replay = database.productionInventoryExport(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            requireSameRequest(replay, exportId, request);
            return toDomain(replay, database.productionInventoryConsumption(
                    request.serviceIdentity().value(), request.requestId()), request);
        }

        ProductionStack actual = source.export(request);
        if (actual == null || actual.isEmpty() || actual.count() != request.count()) {
            throw new IllegalStateException(
                    "Trusted export source did not return the requested server-observed quantity");
        }
        StoredProductionInventoryExport stored = database.recordProductionInventoryExport(
                exportId,
                request.serviceIdentity().value(),
                request.requestId(),
                request.interfaceId(),
                request.actorPlayerId(),
                request.slot(),
                actual.itemId(),
                actual.componentFingerprint(),
                actual.count(),
                request.kind().name(),
                request.destinationReference(),
                request.exportedAtEpochMillis(),
                request.reason(),
                UUID.nameUUIDFromBytes((request.serviceIdentity().value()
                        + ":production-inventory-consumption:" + request.requestId())
                        .getBytes(StandardCharsets.UTF_8)));
        var consumption = database.productionInventoryConsumption(
                request.serviceIdentity().value(), request.requestId());
        return toDomain(stored, consumption, request);
    }

    private static void requireSameRequest(
            StoredProductionInventoryExport stored,
            UUID exportId,
            ProductionInventoryExportRequest request) {
        if (!stored.exportId().equals(exportId)
                || !stored.interfaceId().equals(request.interfaceId())
                || !stored.actorPlayerId().equals(request.actorPlayerId())
                || stored.slot() != request.slot()
                || stored.exportedCount() != request.count()
                || !stored.kind().equals(request.kind().name())
                || !stored.destinationReference().equals(request.destinationReference())
                || stored.exportedAtEpochMillis() != request.exportedAtEpochMillis()
                || !stored.reason().equals(request.reason())) {
            throw new IllegalStateException(
                    "Production inventory export replay changed its immutable payload");
        }
    }

    private static ProductionInventoryExport toDomain(
            StoredProductionInventoryExport stored,
            org.civiceconomy.persistence.StoredProductionInventoryConsumption consumption,
            ProductionInventoryExportRequest request) {
        if (consumption == null || !stored.consumptionId().equals(consumption.consumptionId())) {
            throw new IllegalStateException("Production inventory export is missing its consumption audit");
        }
        return new ProductionInventoryExport(
                stored.exportId(),
                request,
                new ProductionStack(
                        stored.itemId(), stored.componentFingerprint(), stored.exportedCount()),
                new ProductionInventoryConsumption(
                        consumption.consumptionId(),
                        new org.civiceconomy.fiscal.ServiceIdentity(consumption.serviceIdentity()),
                        consumption.requestId(),
                        consumption.interfaceId(),
                        consumption.itemId(),
                        consumption.componentFingerprint(),
                        consumption.consumedCount(),
                        consumption.consumedAtEpochMillis(),
                        consumption.reason()));
    }
}
