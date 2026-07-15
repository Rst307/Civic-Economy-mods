package org.civiceconomy.mint;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredMintBatch;
import org.civiceconomy.persistence.StoredMintMaterialStack;

public final class MintBatchRecovery {
    private final CivicDatabase database;
    private final Clock clock;

    public MintBatchRecovery(CivicDatabase database, Clock clock) {
        if (database == null || clock == null) {
            throw new IllegalArgumentException("Mint Batch Recovery dependencies cannot be null");
        }
        this.database = database;
        this.clock = clock;
    }

    public List<PendingMintMaterialOperation> pendingExternal() {
        return database.recoverableMintBatches().stream()
                .map(this::pendingExternal)
                .flatMap(java.util.Optional::stream)
                .toList();
    }

    public MintBatch confirmExternal(PendingMintMaterialOperation pending) {
        if (pending == null) {
            throw new IllegalArgumentException("Pending Mint material operation cannot be null");
        }
        StoredMintBatch batch = database.mintBatch(pending.batchId());
        if (batch == null) {
            throw new IllegalArgumentException("Unknown Mint Batch " + pending.batchId());
        }
        StoredMintBatch confirmed;
        if (pending instanceof PendingMintMaterialTake take) {
            confirmed = database.confirmMintBatchCustody(
                    batch.batchId(),
                    batch.serviceIdentity(),
                    "custody:" + batch.requestId(),
                    "mint-custody:" + take.transfer().transferId(),
                    clock.millis());
        } else if (pending instanceof PendingMintMaterialReturn materialReturn) {
            confirmed = database.confirmMintBatchMaterialReturn(
                    batch.batchId(),
                    batch.cancellationServiceIdentity(),
                    "return:" + batch.cancellationRequestId(),
                    "mint-return:" + materialReturn.operation().operationId(),
                    clock.millis());
        } else {
            throw new IllegalArgumentException("Unknown pending Mint material operation");
        }
        return toBatch(confirmed);
    }

    private java.util.Optional<PendingMintMaterialOperation> pendingExternal(
            StoredMintBatch batch) {
        if ("PREPARING".equals(batch.state())
                && "EXTERNAL_PENDING".equals(batch.custodyState())) {
            return java.util.Optional.of(new PendingMintMaterialTake(
                    new MintMaterialCustodyTransfer(
                            batch.batchId(),
                            batch.batchId(),
                            batch.mintId(),
                            batch.actorPlayerId(),
                            materials(batch.batchId()))));
        }
        if ("CANCELLING".equals(batch.state())
                && "RETURN_PENDING".equals(batch.custodyState())) {
            UUID operationId = UUID.nameUUIDFromBytes(
                    ("mint-return:" + batch.batchId()).getBytes(StandardCharsets.UTF_8));
            return java.util.Optional.of(new PendingMintMaterialReturn(
                    new MintMaterialCustodyReturn(
                            operationId,
                            batch.batchId(),
                            batch.mintId(),
                            batch.actorPlayerId(),
                            materials(batch.batchId()))));
        }
        return java.util.Optional.empty();
    }

    private List<MintMaterialStack> materials(UUID batchId) {
        return database.mintBatchMaterials(batchId).stream()
                .map(MintBatchRecovery::toMaterial)
                .toList();
    }

    private MintBatch toBatch(StoredMintBatch stored) {
        return new MintBatch(
                stored.batchId(),
                new ServiceIdentity(stored.serviceIdentity()),
                stored.requestId(),
                stored.mintId(),
                stored.periodId(),
                new NationId(stored.nationId()),
                stored.recipeVersionId(),
                MoneyAmount.ofMinorUnits(stored.issuedMinorUnits()),
                stored.actorPlayerId(),
                materials(stored.batchId()),
                stored.state(),
                stored.custodyState(),
                stored.reason(),
                Instant.ofEpochMilli(stored.preparedAtEpochMillis()));
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
