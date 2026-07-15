package org.civiceconomy.mint;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;
import org.civiceconomy.fiscal.AccountId;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredMintBatch;
import org.civiceconomy.persistence.StoredMintIssuanceOperation;
import org.civiceconomy.persistence.StoredMintMaterialStack;
import org.civiceconomy.persistence.StoredMintRecoveryIncident;

public final class MintIssuanceRecovery {
    private static final String ISSUANCE_REASON = "Complete authorized Mint Batch";

    private final CivicDatabase database;
    private final Clock clock;

    public MintIssuanceRecovery(CivicDatabase database, Clock clock) {
        if (database == null || clock == null) {
            throw new IllegalArgumentException("Mint issuance recovery dependencies cannot be null");
        }
        this.database = database;
        this.clock = clock;
    }

    public List<PendingMintIssuanceStep> pendingExternal() {
        List<PendingMintIssuanceStep> pending = new ArrayList<>();
        for (StoredMintIssuanceOperation operation :
                database.recoverableMintIssuanceOperations()) {
            if ("MATERIALS_CONSUMED".equals(operation.state())) {
                database.commitMintBatchIssuance(operation.operationId(), clock.millis());
            } else {
                pending.add(pending(operation));
            }
        }
        for (StoredMintBatch batch : database.recoverableMintBatches()) {
            if ("PROCESSING".equals(batch.state())
                    && "HELD".equals(batch.custodyState())
                    && batch.processingCompletesAtEpochMillis() != null
                    && clock.millis() >= batch.processingCompletesAtEpochMillis()) {
                StoredMintIssuanceOperation operation = database.prepareMintBatchIssuance(
                        operationId(batch.batchId()),
                        batch.batchId(),
                        batch.serviceIdentity(),
                        "issue:" + batch.requestId(),
                        ISSUANCE_REASON,
                        clock.millis());
                pending.add(pending(operation));
            }
        }
        return List.copyOf(pending);
    }

    public StoredMintIssuanceOperation confirmExternal(PendingMintIssuanceStep pending) {
        if (pending instanceof PendingMintTreasuryCredit credit) {
            return database.confirmMintBatchIssuanceExternal(
                    credit.operationId(),
                    "lc-mint-issuance:" + credit.operationId(),
                    clock.millis());
        }
        if (pending instanceof PendingMintMaterialConsumption consumption) {
            database.confirmMintBatchMaterialsConsumed(
                    consumption.operationId(),
                    "mint-material-consume:" + consumption.operationId(),
                    clock.millis());
            return database.commitMintBatchIssuance(
                    consumption.operationId(), clock.millis());
        }
        throw new IllegalArgumentException("Unknown pending Mint issuance step");
    }

    public StoredMintRecoveryIncident recordFailure(
            PendingMintIssuanceStep pending, Throwable failure) {
        if (pending == null || failure == null) {
            throw new IllegalArgumentException("Mint recovery failure evidence cannot be null");
        }
        Throwable cause = unwrap(failure);
        String kind = cause.getClass().getSimpleName();
        if (kind.isBlank()) {
            kind = cause.getClass().getName();
        }
        String message = cause.getMessage();
        if (message == null || message.isBlank()) {
            message = kind;
        }
        return database.recordMintRecoveryIncident(
                pending.operationId(), recoveryStep(pending), kind, message, clock.millis());
    }

    private PendingMintIssuanceStep pending(StoredMintIssuanceOperation operation) {
        StoredMintBatch batch = database.mintBatch(operation.batchId());
        if (batch == null) {
            throw new IllegalStateException("Mint issuance references a missing Mint Batch");
        }
        if ("PREPARED".equals(operation.state())) {
            return new PendingMintTreasuryCredit(
                    operation.operationId(),
                    operation.batchId(),
                    new ExternalMintIssuance(
                            operation.operationId(),
                            new AccountId(operation.treasuryAccount()),
                            MoneyAmount.ofMinorUnits(operation.amountMinorUnits())));
        }
        if ("EXTERNAL_APPLIED".equals(operation.state())) {
            return new PendingMintMaterialConsumption(
                    operation.operationId(),
                    operation.batchId(),
                    new MintMaterialCustodyConsumption(
                            operation.operationId(),
                            operation.batchId(),
                            batch.mintId(),
                            batch.actorPlayerId(),
                            materials(batch.batchId())));
        }
        throw new IllegalStateException(
                "Unsupported recoverable Mint issuance state " + operation.state());
    }

    private static String recoveryStep(PendingMintIssuanceStep pending) {
        if (pending instanceof PendingMintTreasuryCredit) {
            return "TREASURY_CREDIT";
        }
        if (pending instanceof PendingMintMaterialConsumption) {
            return "MATERIAL_CONSUMPTION";
        }
        throw new IllegalArgumentException("Unknown pending Mint issuance step");
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable cause = failure;
        while ((cause instanceof CompletionException || cause instanceof ExecutionException)
                && cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private List<MintMaterialStack> materials(UUID batchId) {
        return database.mintBatchMaterials(batchId).stream()
                .map(MintIssuanceRecovery::toMaterial)
                .toList();
    }

    private static UUID operationId(UUID batchId) {
        return UUID.nameUUIDFromBytes(
                ("mint-issuance:" + batchId).getBytes(StandardCharsets.UTF_8));
    }

    private static MintMaterialStack toMaterial(StoredMintMaterialStack material) {
        return new MintMaterialStack(
                material.groupIndex(),
                material.matcherKind(),
                material.matcherValue(),
                material.itemId(),
                material.count());
    }
}
