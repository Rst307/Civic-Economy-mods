package org.civiceconomy.territory;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredTerritoryClaimPermit;
import org.civiceconomy.persistence.StoredTerritoryClaimPermitConsumption;

public final class TerritoryClaimPermitRegistry {
    private final CivicDatabase database;
    private final TerritoryPrepaymentVerifier prepayments;
    private final Clock clock;

    public TerritoryClaimPermitRegistry(
            CivicDatabase database, TerritoryPrepaymentVerifier prepayments, Clock clock) {
        if (database == null || prepayments == null || clock == null) {
            throw new IllegalArgumentException("Territory Claim Permit dependencies cannot be null");
        }
        this.database = database;
        this.prepayments = prepayments;
        this.clock = clock;
    }

    public TerritoryClaimPermit issue(IssueTerritoryClaimPermit request) {
        StoredTerritoryClaimPermit replay = database.territoryClaimPermit(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            requirePayload(replay, request);
            return toPermit(replay);
        }
        long issuedAt = clock.millis();
        if (request.expiresAt().toEpochMilli() <= issuedAt) {
            throw new IllegalArgumentException("Territory Claim Permit expiry must be in the future");
        }
        MoneyAmount prepayment = MoneyAmount.ofMinorUnits(request.prepaymentMinorUnits());
        if (!prepayments.isCommitted(
                request.prepaymentTransactionId(), request.nationId(), prepayment)) {
            throw new SecurityException(
                    "Territory Claim Permit requires an exact committed fiscal prepayment");
        }
        return toPermit(database.issueTerritoryClaimPermit(
                UUID.randomUUID(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.nationId().value(),
                request.ftbTeamId(),
                request.actorPlayerId(),
                request.dimensionId(),
                request.chunkX(),
                request.chunkZ(),
                request.quotedCurrentClaimedChunks(),
                request.quotedFreeAllocation(),
                request.prepaymentMinorUnits(),
                request.prepaymentTransactionId(),
                TerritoryClaimPermitState.READY.name(),
                issuedAt,
                request.expiresAt().toEpochMilli()));
    }

    public Optional<TerritoryClaimPermit> find(UUID permitId) {
        if (permitId == null) {
            throw new IllegalArgumentException("Territory Claim Permit ID cannot be null");
        }
        return Optional.ofNullable(database.territoryClaimPermit(permitId))
                .map(TerritoryClaimPermitRegistry::toPermit);
    }

    public Optional<TerritoryClaimPermit> findByTarget(
            org.civiceconomy.nation.NationId nationId,
            String dimensionId,
            int chunkX,
            int chunkZ) {
        if (nationId == null || dimensionId == null || dimensionId.isBlank()) {
            throw new IllegalArgumentException("Territory Claim Permit target cannot be null or blank");
        }
        return Optional.ofNullable(database.territoryClaimPermitByTarget(
                        nationId.value(), dimensionId, chunkX, chunkZ))
                .map(TerritoryClaimPermitRegistry::toPermit);
    }

    public TerritoryClaimPermit consume(ConsumeTerritoryClaimPermit request) {
        StoredTerritoryClaimPermitConsumption replay = database.territoryClaimPermitConsumption(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            requireConsumptionPayload(replay, request);
            return find(replay.permitId()).orElseThrow();
        }
        TerritoryClaimPermit permit = find(request.permitId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown Territory Claim Permit " + request.permitId()));
        requireConsumptionTarget(permit, request);
        if (permit.state() != TerritoryClaimPermitState.READY) {
            throw new IllegalStateException(
                    "Territory Claim Permit is not READY " + permit.permitId());
        }
        if (clock.millis() >= permit.expiresAt().toEpochMilli()) {
            throw new IllegalStateException(
                    "Territory Claim Permit has expired " + permit.permitId());
        }
        return toPermit(database.consumeTerritoryClaimPermit(
                request.permitId(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.nationId().value(),
                request.ftbTeamId(),
                request.actorPlayerId(),
                request.dimensionId(),
                request.chunkX(),
                request.chunkZ(),
                clock.millis()));
    }

    private static void requirePayload(
            StoredTerritoryClaimPermit stored, IssueTerritoryClaimPermit request) {
        if (!stored.nationId().equals(request.nationId().value())
                || !stored.ftbTeamId().equals(request.ftbTeamId())
                || !stored.actorPlayerId().equals(request.actorPlayerId())
                || !stored.dimensionId().equals(request.dimensionId())
                || stored.chunkX() != request.chunkX()
                || stored.chunkZ() != request.chunkZ()
                || stored.quotedCurrentClaimedChunks() != request.quotedCurrentClaimedChunks()
                || stored.quotedFreeAllocation() != request.quotedFreeAllocation()
                || stored.prepaymentMinorUnits() != request.prepaymentMinorUnits()
                || !stored.prepaymentTransactionId().equals(request.prepaymentTransactionId())
                || stored.expiresAtEpochMillis() != request.expiresAt().toEpochMilli()) {
            throw new IdempotencyConflictException(
                    request.serviceIdentity(), request.requestId());
        }
    }

    private static void requireConsumptionPayload(
            StoredTerritoryClaimPermitConsumption stored,
            ConsumeTerritoryClaimPermit request) {
        if (!stored.permitId().equals(request.permitId())
                || !stored.nationId().equals(request.nationId().value())
                || !stored.ftbTeamId().equals(request.ftbTeamId())
                || !stored.actorPlayerId().equals(request.actorPlayerId())
                || !stored.dimensionId().equals(request.dimensionId())
                || stored.chunkX() != request.chunkX()
                || stored.chunkZ() != request.chunkZ()) {
            throw new IdempotencyConflictException(
                    request.serviceIdentity(), request.requestId());
        }
    }

    private static void requireConsumptionTarget(
            TerritoryClaimPermit permit, ConsumeTerritoryClaimPermit request) {
        if (!permit.nationId().equals(request.nationId())
                || !permit.ftbTeamId().equals(request.ftbTeamId())
                || !permit.actorPlayerId().equals(request.actorPlayerId())
                || !permit.dimensionId().equals(request.dimensionId())
                || permit.chunkX() != request.chunkX()
                || permit.chunkZ() != request.chunkZ()) {
            throw new SecurityException(
                    "Territory Claim Permit does not match the exact claim target and actor");
        }
    }

    private static TerritoryClaimPermit toPermit(StoredTerritoryClaimPermit stored) {
        return new TerritoryClaimPermit(
                stored.permitId(),
                new org.civiceconomy.nation.NationId(stored.nationId()),
                stored.ftbTeamId(),
                stored.actorPlayerId(),
                stored.dimensionId(),
                stored.chunkX(),
                stored.chunkZ(),
                stored.quotedCurrentClaimedChunks(),
                stored.quotedFreeAllocation(),
                MoneyAmount.ofMinorUnits(stored.prepaymentMinorUnits()),
                stored.prepaymentTransactionId(),
                TerritoryClaimPermitState.valueOf(stored.state()),
                Instant.ofEpochMilli(stored.issuedAtEpochMillis()),
                Instant.ofEpochMilli(stored.expiresAtEpochMillis()));
    }
}
