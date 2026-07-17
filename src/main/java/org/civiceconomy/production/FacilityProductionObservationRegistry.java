package org.civiceconomy.production;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredCreateRecipeCompletion;
import org.civiceconomy.persistence.StoredFacilityAccountingReceipt;
import org.civiceconomy.persistence.StoredFacilityProductionDecision;
import org.civiceconomy.persistence.StoredProductionInventoryChange;

public final class FacilityProductionObservationRegistry {
    private final CivicDatabase database;
    private final FacilityProductionMatcher matcher;
    private final Clock clock;

    public FacilityProductionObservationRegistry(
            CivicDatabase database, FacilityProductionMatcher matcher, Clock clock) {
        if (database == null || matcher == null || clock == null) {
            throw new IllegalArgumentException(
                    "Facility Production Observation dependencies cannot be null");
        }
        this.database = database;
        this.matcher = matcher;
        this.clock = clock;
    }

    public FacilityProductionObservation record(
            CreateRecipeCompletion completion, FacilityAccountingReceipt receipt) {
        FacilityProductionDecision decision = matcher.match(completion, receipt);
        database.recordFacilityProductionObservation(
                toStored(completion),
                toStored(completion.observationId(), completion.inventoryDelta().inputs(), "INPUT"),
                toStored(completion.observationId(), completion.inventoryDelta().outputs(), "OUTPUT"),
                toStored(receipt),
                toStored(receipt.receiptId(), receipt.receivedOutputs(), "RECEIPT"),
                new StoredFacilityProductionDecision(
                        decision.observationId(),
                        decision.facilityId(),
                        decision.interfaceId(),
                        decision.receiptId(),
                        decision.kind().name(),
                        decision.reason(),
                        clock.millis()));
        return observation(completion.observationId());
    }

    public FacilityProductionObservation observation(UUID observationId) {
        StoredCreateRecipeCompletion completion = database.createRecipeCompletion(observationId);
        if (completion == null) {
            return null;
        }
        StoredFacilityProductionDecision decision =
                database.facilityProductionDecision(observationId);
        StoredFacilityAccountingReceipt receipt =
                database.facilityAccountingReceipt(decision.receiptId());
        return new FacilityProductionObservation(
                new CreateRecipeCompletion(
                        completion.observationId(),
                        completion.createVersion(),
                        CreateMachineKind.valueOf(completion.machineKind()),
                        completion.recipeId(),
                        completion.dimensionId(),
                        completion.blockX(),
                        completion.blockY(),
                        completion.blockZ(),
                        completion.observedAtEpochMillis(),
                        new MachineInventoryDelta(
                                toChanges(database.createRecipeCompletionChanges(
                                        observationId, "INPUT")),
                                toChanges(database.createRecipeCompletionChanges(
                                        observationId, "OUTPUT")))),
                new FacilityAccountingReceipt(
                        receipt.receiptId(),
                        receipt.interfaceId(),
                        new FacilityAccountingInterfacePosition(
                                receipt.dimensionId(),
                                receipt.blockX(),
                                receipt.blockY(),
                                receipt.blockZ()),
                        receipt.observedAtEpochMillis(),
                        toChanges(database.facilityAccountingReceiptChanges(receipt.receiptId()))),
                new FacilityProductionDecision(
                        decision.observationId(),
                        decision.facilityId(),
                        decision.interfaceId(),
                        decision.receiptId(),
                        FacilityProductionDecisionKind.valueOf(decision.decision()),
                        decision.reason()),
                Instant.ofEpochMilli(decision.decidedAtEpochMillis()));
    }

    private static StoredCreateRecipeCompletion toStored(CreateRecipeCompletion completion) {
        return new StoredCreateRecipeCompletion(
                completion.observationId(),
                completion.createVersion(),
                completion.machineKind().name(),
                completion.recipeId(),
                completion.dimensionId(),
                completion.blockX(),
                completion.blockY(),
                completion.blockZ(),
                completion.observedAtEpochMillis());
    }

    private static StoredFacilityAccountingReceipt toStored(FacilityAccountingReceipt receipt) {
        return new StoredFacilityAccountingReceipt(
                receipt.receiptId(),
                receipt.interfaceId(),
                receipt.position().dimensionId(),
                receipt.position().blockX(),
                receipt.position().blockY(),
                receipt.position().blockZ(),
                receipt.observedAtEpochMillis());
    }

    private static List<StoredProductionInventoryChange> toStored(
            UUID sourceId, List<MachineInventoryChange> changes, String role) {
        return changes.stream()
                .map(change -> new StoredProductionInventoryChange(
                        sourceId,
                        role,
                        change.slot(),
                        change.stack().itemId(),
                        change.stack().componentFingerprint(),
                        change.stack().count()))
                .toList();
    }

    private static List<MachineInventoryChange> toChanges(
            List<StoredProductionInventoryChange> changes) {
        return changes.stream()
                .map(change -> new MachineInventoryChange(
                        change.slot(),
                        new ProductionStack(
                                change.itemId(),
                                change.componentFingerprint(),
                                change.count())))
                .toList();
    }
}
