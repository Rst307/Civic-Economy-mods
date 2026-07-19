package org.civiceconomy.production;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.UUID;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredFacilityAccountingInterface;
import org.civiceconomy.persistence.StoredFacilityAccountingReceipt;
import org.civiceconomy.persistence.StoredProductionInventoryExport;
import org.civiceconomy.persistence.StoredProductionInventoryExportHandoff;

/** Deep module for recording and reading explicit cross-facility export handoffs. */
public final class ProductionInventoryExportHandoffRegistry {
    private final CivicDatabase database;
    private final Clock clock;

    public ProductionInventoryExportHandoffRegistry(CivicDatabase database, Clock clock) {
        if (database == null || clock == null) {
            throw new IllegalArgumentException("Production export handoff dependencies are required");
        }
        this.database = database;
        this.clock = clock;
    }

    public ProductionInventoryExportHandoff record(RecordProductionInventoryExportHandoff request) {
        if (request == null) {
            throw new IllegalArgumentException("Production export handoff request is required");
        }
        UUID handoffId = UUID.nameUUIDFromBytes((request.serviceIdentity().value()
                + ":production-inventory-export-handoff:" + request.requestId())
                .getBytes(StandardCharsets.UTF_8));
        StoredProductionInventoryExportHandoff replay =
                database.productionInventoryExportHandoff(
                        request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            requireSameRequest(replay, handoffId, request);
            return toDomain(replay);
        }

        StoredProductionInventoryExport export = database.productionInventoryExport(request.exportId());
        if (export == null) {
            throw new IllegalStateException("Production export handoff source EXPORT is missing");
        }
        if (!export.serviceIdentity().equals(request.serviceIdentity().value())
                || !"EXPORT".equals(export.kind())) {
            throw new IllegalStateException(
                    "Production export handoff source is not an EXPORT owned by this service");
        }
        StoredFacilityAccountingInterface destinationInterface =
                database.facilityAccountingInterfaceById(request.destinationInterfaceId());
        StoredFacilityAccountingReceipt destinationReceipt =
                database.facilityAccountingReceipt(request.destinationReceiptId());
        if (destinationInterface == null || destinationReceipt == null
                || !destinationReceipt.interfaceId().equals(request.destinationInterfaceId())) {
            throw new IllegalStateException(
                    "Production export handoff destination interface or Receipt is missing");
        }
        StoredFacilityAccountingInterface sourceInterface =
                database.facilityAccountingInterfaceById(export.interfaceId());
        if (sourceInterface == null
                || sourceInterface.facilityId().equals(destinationInterface.facilityId())) {
            throw new IllegalStateException(
                    "Production export handoff must cross two distinct Registered Facilities");
        }
        StoredProductionInventoryExportHandoff stored =
                database.recordProductionInventoryExportHandoff(
                        handoffId,
                        request.serviceIdentity().value(),
                        request.requestId(),
                        export.exportId(),
                        export.interfaceId(),
                        destinationInterface.interfaceId(),
                        destinationReceipt.receiptId(),
                        request.actorPlayerId(),
                        clock.millis(),
                        request.reason());
        return toDomain(stored);
    }

    public ProductionInventoryExportHandoff handoff(String serviceIdentity, String requestId) {
        StoredProductionInventoryExportHandoff stored =
                database.productionInventoryExportHandoff(serviceIdentity, requestId);
        return stored == null ? null : toDomain(stored);
    }

    private static void requireSameRequest(
            StoredProductionInventoryExportHandoff stored,
            UUID handoffId,
            RecordProductionInventoryExportHandoff request) {
        if (!stored.handoffId().equals(handoffId)
                || !stored.exportId().equals(request.exportId())
                || !stored.destinationInterfaceId().equals(request.destinationInterfaceId())
                || !stored.destinationReceiptId().equals(request.destinationReceiptId())
                || !stored.actorPlayerId().equals(request.actorPlayerId())
                || !stored.reason().equals(request.reason())) {
            throw new IllegalStateException(
                    "Production export handoff replay changed its immutable payload");
        }
    }

    private static ProductionInventoryExportHandoff toDomain(
            StoredProductionInventoryExportHandoff stored) {
        return new ProductionInventoryExportHandoff(
                stored.handoffId(),
                new org.civiceconomy.fiscal.ServiceIdentity(stored.serviceIdentity()),
                stored.requestId(),
                stored.exportId(),
                stored.sourceInterfaceId(),
                stored.destinationInterfaceId(),
                stored.destinationReceiptId(),
                stored.actorPlayerId(),
                stored.recordedAtEpochMillis(),
                stored.reason());
    }
}
