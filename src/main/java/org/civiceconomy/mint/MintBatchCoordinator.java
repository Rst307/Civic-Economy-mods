package org.civiceconomy.mint;

import java.time.Clock;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.Optional;
import org.civiceconomy.fiscal.FailurePoint;
import org.civiceconomy.fiscal.FiscalAuthorization;
import org.civiceconomy.fiscal.FiscalCapability;
import org.civiceconomy.fiscal.FiscalServiceSession;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.fiscal.SimulatedCrash;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.issuance.IssuanceQuotaRegistry;
import org.civiceconomy.nation.NationFiscalAuthorityRegistry;
import org.civiceconomy.nation.NationFiscalPermission;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredMintBatch;
import org.civiceconomy.persistence.StoredMintMaterialStack;
import org.civiceconomy.persistence.StoredRegisteredMint;

public final class MintBatchCoordinator {
    private final CivicDatabase database;
    private final FiscalAuthorization fiscalAuthorization;
    private final FiscalServiceSession session;
    private final NationFiscalAuthorityRegistry nationAuthorities;
    private final RegisteredMintTerritoryAuthority territoryAuthority;
    private final ExternalMintMaterialCustody custody;
    private final Clock clock;

    public MintBatchCoordinator(
            CivicDatabase database,
            FiscalServiceSession session,
            NationFiscalAuthorityRegistry nationAuthorities,
            RegisteredMintTerritoryAuthority territoryAuthority,
            ExternalMintMaterialCustody custody,
            Clock clock) {
        if (database == null || session == null || nationAuthorities == null
                || territoryAuthority == null || custody == null || clock == null) {
            throw new IllegalArgumentException("Mint Batch Coordinator dependencies cannot be null");
        }
        this.database = database;
        this.fiscalAuthorization = new FiscalAuthorization(database);
        this.session = session;
        this.nationAuthorities = nationAuthorities;
        this.territoryAuthority = territoryAuthority;
        this.custody = custody;
        this.clock = clock;
    }

    public MintBatch prepare(PrepareMintBatch request, FailurePoint failurePoint) {
        PendingMintMaterialTake pending = prepareExternal(request);
        pending.apply(custody);
        if (failurePoint == FailurePoint.AFTER_EXTERNAL_BEFORE_RECORD) {
            throw new SimulatedCrash(failurePoint);
        }
        return confirmExternal(pending);
    }

    public PendingMintMaterialTake prepareExternal(PrepareMintBatch request) {
        StoredRegisteredMint mint = database.registeredMint(request.mintId());
        if (mint == null) {
            throw new IllegalArgumentException("Unknown Registered Mint " + request.mintId());
        }
        NationId nationId = new NationId(mint.nationId());
        fiscalAuthorization.require(
                session,
                request.serviceIdentity(),
                FiscalCapability.MANAGE_ISSUANCE,
                IssuanceQuotaRegistry.treasury(nationId));
        nationAuthorities.require(
                nationId,
                request.actorPlayerId(),
                NationFiscalPermission.MANAGE_ISSUANCE);
        RegisteredMint domainMint = toMint(mint);
        if (!territoryAuthority.isEffective(domainMint)) {
            throw new SecurityException("Registered Mint is not inside effective exact-Nation territory");
        }
        StoredMintBatch stored = database.prepareMintBatch(
                UUID.randomUUID(),
                request.serviceIdentity().value(),
                request.requestId(),
                mint.mintId(),
                request.periodId(),
                mint.nationId(),
                mint.recipeVersionId(),
                request.amount().minorUnits(),
                request.materials().stream().map(MintBatchCoordinator::toStored).toList(),
                request.actorPlayerId(),
                request.reason(),
                clock.millis());
        return pendingTake(stored);
    }

    public Optional<MintBatch> findByRequest(ServiceIdentity serviceIdentity, String requestId) {
        if (serviceIdentity == null || requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("Mint Batch request identity is invalid");
        }
        return Optional.ofNullable(database.mintBatch(serviceIdentity.value(), requestId))
                .map(this::toBatch);
    }

    public Optional<MintBatch> findByRequest(
            ServiceIdentity serviceIdentity,
            String requestId,
            UUID mintId,
            UUID periodId,
            UUID actorPlayerId,
            MoneyAmount amount) {
        if (mintId == null || periodId == null || actorPlayerId == null || amount == null) {
            throw new IllegalArgumentException("Mint Batch replay payload cannot contain null values");
        }
        Optional<MintBatch> replay = findByRequest(serviceIdentity, requestId);
        replay.ifPresent(batch -> {
            if (!batch.mintId().equals(mintId)
                    || !batch.periodId().equals(periodId)
                    || !batch.actorPlayerId().equals(actorPlayerId)
                    || !batch.amount().equals(amount)) {
                throw new IdempotencyConflictException(serviceIdentity, requestId);
            }
        });
        return replay;
    }

    public MintBatch cancel(CancelMintBatch request, FailurePoint failurePoint) {
        Optional<MintBatch> replay = findCompletedCancellation(request);
        if (replay.isPresent()) {
            return replay.get();
        }
        PendingMintMaterialReturn pending = prepareCancellationExternal(request);
        pending.apply(custody);
        if (failurePoint == FailurePoint.AFTER_EXTERNAL_BEFORE_RECORD) {
            throw new SimulatedCrash(failurePoint);
        }
        return confirmExternal(pending);
    }

    public PendingMintMaterialReturn prepareCancellationExternal(CancelMintBatch request) {
        StoredMintBatch batch = database.mintBatch(request.batchId());
        if (batch == null) {
            throw new IllegalArgumentException("Unknown Mint Batch " + request.batchId());
        }
        requireCancellationAuthority(request, batch);
        StoredMintBatch cancelling = database.prepareMintBatchCancellation(
                batch.batchId(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.actorPlayerId(),
                request.reason(),
                clock.millis());
        return pendingReturn(cancelling);
    }

    public Optional<MintBatch> findCompletedCancellation(CancelMintBatch request) {
        StoredMintBatch batch = database.mintBatch(request.batchId());
        if (batch == null) {
            throw new IllegalArgumentException("Unknown Mint Batch " + request.batchId());
        }
        requireCancellationAuthority(request, batch);
        if (batch.cancellationRequestId() == null) {
            return Optional.empty();
        }
        StoredMintBatch replay = database.prepareMintBatchCancellation(
                batch.batchId(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.actorPlayerId(),
                request.reason(),
                clock.millis());
        if ("CANCELLED".equals(replay.state()) && "RETURNED".equals(replay.custodyState())) {
            return Optional.of(toBatch(replay));
        }
        return Optional.empty();
    }

    private void requireCancellationAuthority(CancelMintBatch request, StoredMintBatch batch) {
        NationId nationId = new NationId(batch.nationId());
        fiscalAuthorization.require(
                session,
                request.serviceIdentity(),
                FiscalCapability.MANAGE_ISSUANCE,
                IssuanceQuotaRegistry.treasury(nationId));
        nationAuthorities.require(
                nationId,
                request.actorPlayerId(),
                NationFiscalPermission.MANAGE_ISSUANCE);
    }

    public int recoverIncomplete() {
        int recovered = 0;
        for (StoredMintBatch batch : database.recoverableMintBatches()) {
            if ("PREPARING".equals(batch.state())
                    && "EXTERNAL_PENDING".equals(batch.custodyState())) {
                takeAndConfirm(batch, FailurePoint.NONE);
                recovered++;
            } else if ("CANCELLING".equals(batch.state())
                    && "RETURN_PENDING".equals(batch.custodyState())) {
                returnAndConfirm(batch, FailurePoint.NONE);
                recovered++;
            }
        }
        return recovered;
    }

    public MintBatch confirmExternal(PendingMintMaterialTake pending) {
        StoredMintBatch batch = requireBatch(
                pending.batchId(), "PREPARING", "EXTERNAL_PENDING");
        StoredMintBatch confirmed = database.confirmMintBatchCustody(
                batch.batchId(),
                batch.serviceIdentity(),
                "custody:" + batch.requestId(),
                "mint-custody:" + pending.transfer().transferId(),
                clock.millis());
        return toBatch(confirmed);
    }

    public MintBatch confirmExternal(PendingMintMaterialReturn pending) {
        StoredMintBatch batch = requireBatch(
                pending.batchId(), "CANCELLING", "RETURN_PENDING");
        StoredMintBatch confirmed = database.confirmMintBatchMaterialReturn(
                batch.batchId(),
                batch.cancellationServiceIdentity(),
                "return:" + batch.cancellationRequestId(),
                "mint-return:" + pending.operation().operationId(),
                clock.millis());
        return toBatch(confirmed);
    }

    private MintBatch takeAndConfirm(StoredMintBatch batch, FailurePoint failurePoint) {
        PendingMintMaterialTake pending = pendingTake(batch);
        pending.apply(custody);
        if (failurePoint == FailurePoint.AFTER_EXTERNAL_BEFORE_RECORD) {
            throw new SimulatedCrash(failurePoint);
        }
        return confirmExternal(pending);
    }

    private MintBatch returnAndConfirm(StoredMintBatch batch, FailurePoint failurePoint) {
        PendingMintMaterialReturn pending = pendingReturn(batch);
        pending.apply(custody);
        if (failurePoint == FailurePoint.AFTER_EXTERNAL_BEFORE_RECORD) {
            throw new SimulatedCrash(failurePoint);
        }
        return confirmExternal(pending);
    }

    private PendingMintMaterialTake pendingTake(StoredMintBatch batch) {
        var materials = materials(batch.batchId());
        return new PendingMintMaterialTake(new MintMaterialCustodyTransfer(
                batch.batchId(), batch.batchId(), batch.mintId(), batch.actorPlayerId(), materials));
    }

    private PendingMintMaterialReturn pendingReturn(StoredMintBatch batch) {
        UUID operationId = UUID.nameUUIDFromBytes(
                ("mint-return:" + batch.batchId()).getBytes(StandardCharsets.UTF_8));
        return new PendingMintMaterialReturn(new MintMaterialCustodyReturn(
                operationId,
                batch.batchId(),
                batch.mintId(),
                batch.actorPlayerId(),
                materials(batch.batchId())));
    }

    private java.util.List<MintMaterialStack> materials(UUID batchId) {
        return database.mintBatchMaterials(batchId).stream()
                .map(MintBatchCoordinator::toMaterial)
                .toList();
    }

    private StoredMintBatch requireBatch(UUID batchId, String state, String custodyState) {
        StoredMintBatch batch = database.mintBatch(batchId);
        if (batch == null
                || !state.equals(batch.state())
                || !custodyState.equals(batch.custodyState())) {
            throw new IllegalStateException("Mint Batch is not awaiting this external confirmation");
        }
        return batch;
    }

    private MintBatch toBatch(StoredMintBatch stored) {
        return new MintBatch(
                stored.batchId(),
                new org.civiceconomy.fiscal.ServiceIdentity(stored.serviceIdentity()),
                stored.requestId(),
                stored.mintId(),
                stored.periodId(),
                new NationId(stored.nationId()),
                stored.recipeVersionId(),
                MoneyAmount.ofMinorUnits(stored.issuedMinorUnits()),
                stored.actorPlayerId(),
                database.mintBatchMaterials(stored.batchId()).stream()
                        .map(MintBatchCoordinator::toMaterial).toList(),
                stored.state(),
                stored.custodyState(),
                stored.reason(),
                Instant.ofEpochMilli(stored.preparedAtEpochMillis()));
    }

    private static RegisteredMint toMint(StoredRegisteredMint stored) {
        return new RegisteredMint(
                stored.mintId(),
                new org.civiceconomy.fiscal.ServiceIdentity(stored.serviceIdentity()),
                stored.requestId(),
                new NationId(stored.nationId()),
                stored.dimensionId(),
                stored.blockX(),
                stored.blockY(),
                stored.blockZ(),
                stored.operatorOrganizationId(),
                stored.licenseId(),
                stored.automationAllowed(),
                stored.recipeVersionId(),
                stored.actorPlayerId(),
                stored.transactionState(),
                stored.reason(),
                Instant.ofEpochMilli(stored.registeredAtEpochMillis()));
    }

    private static StoredMintMaterialStack toStored(MintMaterialStack material) {
        return new StoredMintMaterialStack(
                material.groupIndex(), material.matcherKind(), material.matcherValue(),
                material.itemId(), material.count());
    }

    private static MintMaterialStack toMaterial(StoredMintMaterialStack material) {
        return new MintMaterialStack(
                material.groupIndex(), material.matcherKind(), material.matcherValue(),
                material.itemId(), material.count());
    }
}
