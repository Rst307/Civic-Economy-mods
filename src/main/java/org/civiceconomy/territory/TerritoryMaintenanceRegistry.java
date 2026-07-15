package org.civiceconomy.territory;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredTerritoryFiscalAssessment;
import org.civiceconomy.persistence.StoredTerritoryMaintenanceAssessmentBatch;
import org.civiceconomy.persistence.StoredTerritoryMaintenanceAssessmentClaim;
import org.civiceconomy.persistence.StoredTerritoryMaintenanceCycle;
import org.civiceconomy.persistence.StoredTerritoryMaintenanceSettlement;

public final class TerritoryMaintenanceRegistry {
    private final CivicDatabase database;
    private final Clock clock;

    public TerritoryMaintenanceRegistry(CivicDatabase database) {
        this(database, Clock.systemUTC());
    }

    public TerritoryMaintenanceRegistry(CivicDatabase database, Clock clock) {
        if (database == null || clock == null) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Registry dependencies cannot be null");
        }
        this.database = database;
        this.clock = clock;
    }

    public TerritoryMaintenanceCycle openCycle(OpenTerritoryMaintenanceCycle request) {
        StoredTerritoryMaintenanceCycle replay = database.territoryMaintenanceCycle(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            if (replay.startsAtEpochMillis() != request.startsAt().toEpochMilli()
                    || replay.endsAtEpochMillis() != request.endsAt().toEpochMilli()) {
                throw new IdempotencyConflictException(
                        request.serviceIdentity(), request.requestId());
            }
            return toCycle(replay);
        }
        return toCycle(database.openTerritoryMaintenanceCycle(
                UUID.randomUUID(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.startsAt().toEpochMilli(),
                request.endsAt().toEpochMilli(),
                clock.millis()));
    }

    public TerritoryMaintenanceCycle cycle(UUID cycleId) {
        if (cycleId == null) {
            throw new IllegalArgumentException(
                    "Territory Maintenance Cycle ID cannot be null");
        }
        StoredTerritoryMaintenanceCycle stored = database.territoryMaintenanceCycle(cycleId);
        if (stored == null) {
            throw new IllegalArgumentException(
                    "Unknown Territory Maintenance Cycle " + cycleId);
        }
        return toCycle(stored);
    }

    public TerritoryFiscalAssessment assess(AssessTerritoryFiscalValidity request) {
        StoredTerritoryFiscalAssessment replay = database.territoryFiscalAssessment(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            requirePayload(replay, request);
            return toAssessment(replay);
        }
        if (database.territoryMaintenanceCycle(request.cycleId()) == null) {
            throw new IllegalArgumentException(
                    "Unknown Territory Maintenance Cycle " + request.cycleId());
        }
        var nation = database.nation(request.nationId().value());
        if (nation == null || !nation.ftbTeamId().equals(request.ftbTeamId())) {
            throw new SecurityException(
                    "Territory Fiscal Assessment Team must match the registered Nation binding");
        }
        return toAssessment(database.assessTerritoryFiscalValidity(
                UUID.randomUUID(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.cycleId(),
                request.nationId().value(),
                request.ftbTeamId(),
                request.dimensionId(),
                request.chunkX(),
                request.chunkZ(),
                request.maintenanceDueMinorUnits(),
                request.priority().name(),
                request.reason(),
                clock.millis()));
    }

    public StoredTerritoryMaintenanceAssessmentBatch registerAssessmentBatch(
            UUID cycleId,
            org.civiceconomy.fiscal.ServiceIdentity serviceIdentity,
            String requestId,
            List<TerritoryMaintenanceClaimSnapshot> claims,
            String snapshotSha256) {
        StoredTerritoryMaintenanceAssessmentBatch replay =
                database.territoryMaintenanceAssessmentBatch(
                        serviceIdentity.value(), requestId);
        if (replay != null) {
            if (!replay.cycleId().equals(cycleId)
                    || replay.claimCount() != claims.size()
                    || !replay.snapshotSha256().equals(snapshotSha256)) {
                throw new IdempotencyConflictException(serviceIdentity, requestId);
            }
            return replay;
        }
        return database.registerTerritoryMaintenanceAssessmentBatch(
                cycleId,
                serviceIdentity.value(),
                requestId,
                java.util.stream.IntStream.range(0, claims.size())
                        .mapToObj(index -> {
                            TerritoryMaintenanceClaimSnapshot claim = claims.get(index);
                            return new StoredTerritoryMaintenanceAssessmentClaim(
                                    cycleId,
                                    index,
                                    claim.nationId().value(),
                                    claim.ftbTeamId(),
                                    claim.dimensionId(),
                                    claim.chunkX(),
                                    claim.chunkZ(),
                                    claim.maintenanceDueMinorUnits(),
                                    claim.priority().name());
                        })
                        .toList(),
                snapshotSha256,
                clock.millis());
    }

    public List<TerritoryMaintenanceClaimSnapshot> assessmentBatchClaims(UUID cycleId) {
        return database.territoryMaintenanceAssessmentClaims(cycleId).stream()
                .map(claim -> new TerritoryMaintenanceClaimSnapshot(
                        new NationId(claim.nationId()),
                        claim.ftbTeamId(),
                        claim.dimensionId(),
                        claim.chunkX(),
                        claim.chunkZ(),
                        claim.maintenanceDueMinorUnits(),
                        TerritoryMaintenancePriority.valueOf(claim.priority())))
                .toList();
    }

    public StoredTerritoryMaintenanceAssessmentBatch assessmentBatch(
            org.civiceconomy.fiscal.ServiceIdentity serviceIdentity, String requestId) {
        return database.territoryMaintenanceAssessmentBatch(serviceIdentity.value(), requestId);
    }

    public boolean isEffective(
            UUID cycleId,
            NationId nationId,
            UUID ftbTeamId,
            String dimensionId,
            int chunkX,
            int chunkZ) {
        if (cycleId == null
                || nationId == null
                || ftbTeamId == null
                || dimensionId == null
                || dimensionId.isBlank()) {
            return false;
        }
        StoredTerritoryFiscalAssessment assessment = database.territoryFiscalAssessment(
                cycleId, nationId.value(), ftbTeamId, dimensionId, chunkX, chunkZ);
        return assessment != null
                && assessment.validity().equals(TerritoryFiscalValidity.EFFECTIVE.name());
    }

    public TerritoryFiscalAssessment assessment(
            UUID cycleId,
            NationId nationId,
            UUID ftbTeamId,
            String dimensionId,
            int chunkX,
            int chunkZ) {
        StoredTerritoryFiscalAssessment stored = database.territoryFiscalAssessment(
                cycleId, nationId.value(), ftbTeamId, dimensionId, chunkX, chunkZ);
        if (stored == null) {
            throw new IllegalArgumentException("Unknown Territory Fiscal Assessment");
        }
        return toAssessment(stored);
    }

    public List<TerritoryMaintenanceCandidate> pendingCandidates(
            UUID cycleId, NationId nationId) {
        if (cycleId == null || nationId == null) {
            throw new IllegalArgumentException(
                    "Territory Maintenance candidate query cannot contain null values");
        }
        return database.pendingTerritoryFiscalAssessments(cycleId, nationId.value()).stream()
                .map(stored -> new TerritoryMaintenanceCandidate(
                        stored.assessmentId(),
                        TerritoryMaintenancePriority.valueOf(stored.priority()),
                        stored.dimensionId(),
                        stored.chunkX(),
                        stored.chunkZ(),
                        MoneyAmount.ofMinorUnits(stored.maintenanceDueMinorUnits())))
                .sorted(Comparator.comparing(TerritoryMaintenanceCandidate::priority)
                        .thenComparing(TerritoryMaintenanceCandidate::dimensionId)
                        .thenComparingInt(TerritoryMaintenanceCandidate::chunkX)
                        .thenComparingInt(TerritoryMaintenanceCandidate::chunkZ)
                        .thenComparing(TerritoryMaintenanceCandidate::assessmentId))
                .toList();
    }

    public TerritoryMaintenanceSettlement confirmSettlement(
            ConfirmTerritoryMaintenanceSettlement request) {
        StoredTerritoryMaintenanceSettlement replay = database.territoryMaintenanceSettlement(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            requireSettlementPayload(replay, request);
            return toSettlement(replay);
        }
        long available = database.territoryMaintenanceSettlementEvidenceAmount(
                request.serviceIdentity().value(),
                request.nationId().value(),
                request.reservationId(),
                request.publicFundPaymentId(),
                request.destructionOperationId());
        TerritoryMaintenancePriorityDecision decision =
                new TerritoryMaintenancePriorityPolicy().select(
                        pendingCandidates(request.cycleId(), request.nationId()),
                        MoneyAmount.ofMinorUnits(available));
        return toSettlement(database.confirmTerritoryMaintenanceSettlement(
                UUID.randomUUID(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.cycleId(),
                request.nationId().value(),
                request.reservationId(),
                request.publicFundPaymentId(),
                request.destructionOperationId(),
                decision.funded().stream()
                        .map(TerritoryMaintenanceCandidate::assessmentId)
                        .toList(),
                decision.suspended().stream()
                        .map(TerritoryMaintenanceCandidate::assessmentId)
                        .toList(),
                request.reason(),
                clock.millis()));
    }

    public TerritoryMaintenanceSettlement suspend(SuspendTerritoryMaintenance request) {
        StoredTerritoryMaintenanceSettlement replay = database.territoryMaintenanceSettlement(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            if (!replay.cycleId().equals(request.cycleId())
                    || !replay.nationId().equals(request.nationId().value())
                    || !replay.reason().equals(request.reason())
                    || !replay.outcome().equals(
                            TerritoryMaintenanceSettlementOutcome.UNFUNDED.name())) {
                throw new IdempotencyConflictException(
                        request.serviceIdentity(), request.requestId());
            }
            return toSettlement(replay);
        }
        return toSettlement(database.suspendTerritoryMaintenance(
                UUID.randomUUID(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.cycleId(),
                request.nationId().value(),
                request.reason(),
                clock.millis()));
    }

    private static void requirePayload(
            StoredTerritoryFiscalAssessment stored,
            AssessTerritoryFiscalValidity request) {
        if (!stored.cycleId().equals(request.cycleId())
                || !stored.nationId().equals(request.nationId().value())
                || !stored.ftbTeamId().equals(request.ftbTeamId())
                || !stored.dimensionId().equals(request.dimensionId())
                || stored.chunkX() != request.chunkX()
                || stored.chunkZ() != request.chunkZ()
                || stored.maintenanceDueMinorUnits() != request.maintenanceDueMinorUnits()
                || !stored.priority().equals(request.priority().name())
                || !stored.reason().equals(request.reason())) {
            throw new IdempotencyConflictException(
                    request.serviceIdentity(), request.requestId());
        }
    }

    private static void requireSettlementPayload(
            StoredTerritoryMaintenanceSettlement stored,
            ConfirmTerritoryMaintenanceSettlement request) {
        if (!stored.cycleId().equals(request.cycleId())
                || !stored.nationId().equals(request.nationId().value())
                || !stored.reservationId().equals(request.reservationId())
                || !stored.publicFundPaymentId().equals(request.publicFundPaymentId())
                || !java.util.Objects.equals(
                        stored.destructionOperationId(), request.destructionOperationId())
                || !stored.reason().equals(request.reason())) {
            throw new IdempotencyConflictException(
                    request.serviceIdentity(), request.requestId());
        }
    }

    private static TerritoryMaintenanceCycle toCycle(StoredTerritoryMaintenanceCycle stored) {
        return new TerritoryMaintenanceCycle(
                stored.cycleId(),
                new org.civiceconomy.fiscal.ServiceIdentity(stored.serviceIdentity()),
                stored.requestId(),
                Instant.ofEpochMilli(stored.startsAtEpochMillis()),
                Instant.ofEpochMilli(stored.endsAtEpochMillis()),
                Instant.ofEpochMilli(stored.openedAtEpochMillis()));
    }

    private static TerritoryFiscalAssessment toAssessment(
            StoredTerritoryFiscalAssessment stored) {
        return new TerritoryFiscalAssessment(
                stored.assessmentId(),
                new org.civiceconomy.fiscal.ServiceIdentity(stored.serviceIdentity()),
                stored.requestId(),
                stored.cycleId(),
                new NationId(stored.nationId()),
                stored.ftbTeamId(),
                stored.dimensionId(),
                stored.chunkX(),
                stored.chunkZ(),
                MoneyAmount.ofMinorUnits(stored.maintenanceDueMinorUnits()),
                TerritoryMaintenancePriority.valueOf(stored.priority()),
                TerritoryFiscalValidity.valueOf(stored.validity()),
                stored.reason(),
                Instant.ofEpochMilli(stored.assessedAtEpochMillis()));
    }

    private static TerritoryMaintenanceSettlement toSettlement(
            StoredTerritoryMaintenanceSettlement stored) {
        return new TerritoryMaintenanceSettlement(
                stored.settlementId(),
                new org.civiceconomy.fiscal.ServiceIdentity(stored.serviceIdentity()),
                stored.requestId(),
                stored.cycleId(),
                new NationId(stored.nationId()),
                Optional.ofNullable(stored.reservationId()),
                Optional.ofNullable(stored.publicFundPaymentId()),
                Optional.ofNullable(stored.destructionOperationId()),
                TerritoryMaintenanceSettlementOutcome.valueOf(stored.outcome()),
                stored.fundedAssessmentIds(),
                stored.suspendedAssessmentIds(),
                stored.reason(),
                Instant.ofEpochMilli(stored.settledAtEpochMillis()));
    }
}
