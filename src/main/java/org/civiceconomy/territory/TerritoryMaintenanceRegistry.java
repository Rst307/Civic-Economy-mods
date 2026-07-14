package org.civiceconomy.territory;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.fiscal.MoneyAmount;
import org.civiceconomy.nation.NationId;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredTerritoryFiscalAssessment;
import org.civiceconomy.persistence.StoredTerritoryMaintenanceCycle;

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
                request.validity().name(),
                request.reason(),
                clock.millis()));
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
                || !stored.validity().equals(request.validity().name())
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
                TerritoryFiscalValidity.valueOf(stored.validity()),
                stored.reason(),
                Instant.ofEpochMilli(stored.assessedAtEpochMillis()));
    }
}
