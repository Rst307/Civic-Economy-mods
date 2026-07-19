package org.civiceconomy.production;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredFacilityAccountingReceipt;
import org.civiceconomy.persistence.StoredProductionInventoryAgeBatch;
import org.civiceconomy.persistence.StoredProductionInventoryChange;
import org.civiceconomy.persistence.StoredProductionInventoryConsumption;

public final class ProductionInventoryAgeLedger {
    private final CivicDatabase database;
    @SuppressWarnings("unused")
    private final Clock clock;

    public ProductionInventoryAgeLedger(CivicDatabase database, Clock clock) {
        if (database == null || clock == null) {
            throw new IllegalArgumentException(
                    "Production Inventory Age Ledger dependencies cannot be null");
        }
        this.database = database;
        this.clock = clock;
    }

    public List<ProductionInventoryAgeBatch> recordReceipt(FacilityAccountingReceipt receipt) {
        if (receipt == null) {
            throw new IllegalArgumentException("Facility Accounting Receipt is required");
        }
        List<StoredProductionInventoryAgeBatch> stored = database.recordProductionInventoryAgeReceipt(
                new StoredFacilityAccountingReceipt(
                        receipt.receiptId(),
                        receipt.interfaceId(),
                        receipt.position().dimensionId(),
                        receipt.position().blockX(),
                        receipt.position().blockY(),
                        receipt.position().blockZ(),
                        receipt.observedAtEpochMillis()),
                toStored(receipt.receiptId(), receipt.receivedOutputs()));
        return stored.stream()
                .filter(batch -> batch.receiptId().equals(receipt.receiptId()))
                .map(ProductionInventoryAgeLedger::toDomain)
                .toList();
    }

    public List<ProductionInventoryAgeBatch> batches(UUID interfaceId) {
        return database.productionInventoryAgeBatches(interfaceId).stream()
                .map(ProductionInventoryAgeLedger::toDomain)
                .toList();
    }

    public ProductionInventoryConsumption consume(ProductionInventoryConsumptionRequest request) {
        if (request == null) {
            throw new IllegalArgumentException("Production inventory consumption is required");
        }
        UUID consumptionId = UUID.nameUUIDFromBytes((request.serviceIdentity().value()
                + ":production-inventory-consumption:" + request.requestId())
                .getBytes(StandardCharsets.UTF_8));
        StoredProductionInventoryConsumption stored =
                database.recordProductionInventoryConsumption(
                        consumptionId,
                        request.serviceIdentity().value(),
                        request.requestId(),
                        request.interfaceId(),
                        request.itemId(),
                        request.componentFingerprint(),
                        request.count(),
                        request.consumedAtEpochMillis(),
                        request.reason());
        return new ProductionInventoryConsumption(
                stored.consumptionId(),
                new org.civiceconomy.fiscal.ServiceIdentity(stored.serviceIdentity()),
                stored.requestId(),
                stored.interfaceId(),
                stored.itemId(),
                stored.componentFingerprint(),
                stored.consumedCount(),
                stored.consumedAtEpochMillis(),
                stored.reason());
    }

    private static ProductionInventoryAgeBatch toDomain(
            StoredProductionInventoryAgeBatch stored) {
        return new ProductionInventoryAgeBatch(
                stored.batchId(),
                stored.receiptId(),
                stored.interfaceId(),
                stored.slot(),
                new ProductionStack(
                        stored.itemId(), stored.componentFingerprint(), stored.originalCount()),
                stored.remainingCount(),
                new ProductionInventoryAge(stored.firstObservedAtEpochMillis()));
    }

    private static List<StoredProductionInventoryChange> toStored(
            UUID receiptId, List<MachineInventoryChange> changes) {
        return changes.stream()
                .map(change -> new StoredProductionInventoryChange(
                        receiptId,
                        "RECEIPT",
                        change.slot(),
                        change.stack().itemId(),
                        change.stack().componentFingerprint(),
                        change.stack().count()))
                .toList();
    }
}
