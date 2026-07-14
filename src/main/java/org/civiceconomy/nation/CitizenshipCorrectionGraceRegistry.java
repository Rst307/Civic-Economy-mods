package org.civiceconomy.nation;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredCitizenshipCorrectionGrace;

public final class CitizenshipCorrectionGraceRegistry {
    private final CivicDatabase database;
    private final Clock clock;

    public CitizenshipCorrectionGraceRegistry(CivicDatabase database, Clock clock) {
        if (database == null || clock == null) {
            throw new IllegalArgumentException("Citizenship Correction Grace dependencies cannot be null");
        }
        this.database = database;
        this.clock = clock;
    }

    public CitizenshipCorrectionGrace start(StartCitizenshipCorrectionGrace request) {
        StoredCitizenshipCorrectionGrace replay = database.citizenshipCorrectionGraceStart(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            requireStartPayload(replay, request);
            return toGrace(replay);
        }
        StoredCitizenshipCorrectionGrace active =
                database.activeCitizenshipCorrectionGraceByCitizenship(request.citizenshipId());
        if (active != null) {
            if (!sameIdentity(active, request)
                    || active.deadlineEpochMillis() != request.deadline().toEpochMilli()
                    || !active.reason().equals(request.reason())) {
                throw new IllegalStateException(
                        "Citizenship already has a different active Correction Grace "
                                + request.citizenshipId());
            }
            return toGrace(active);
        }
        long startedAt = clock.millis();
        if (request.deadline().toEpochMilli() <= startedAt) {
            throw new IllegalArgumentException(
                    "Citizenship Correction Grace deadline must be in the future");
        }
        return toGrace(database.startCitizenshipCorrectionGrace(
                UUID.randomUUID(),
                request.citizenshipId(),
                request.playerId(),
                request.nationId().value(),
                request.ftbTeamId(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.reason(),
                startedAt,
                request.deadline().toEpochMilli()));
    }

    public CitizenshipCorrectionGrace resolve(ResolveCitizenshipCorrectionGrace request) {
        StoredCitizenshipCorrectionGrace replay = database.citizenshipCorrectionGraceResolution(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            if (!replay.graceId().equals(request.graceId())
                    || !request.resolution().name().equals(replay.resolution())
                    || !request.reason().equals(replay.resolutionReason())) {
                throw new IdempotencyConflictException(
                        request.serviceIdentity(), request.requestId());
            }
            return toGrace(replay);
        }
        StoredCitizenshipCorrectionGrace active =
                database.citizenshipCorrectionGrace(request.graceId());
        if (active == null) {
            throw new IllegalArgumentException(
                    "Unknown Citizenship Correction Grace " + request.graceId());
        }
        if (active.resolution() != null) {
            throw new IllegalStateException(
                    "Citizenship Correction Grace is already resolved " + request.graceId());
        }
        return toGrace(database.resolveCitizenshipCorrectionGrace(
                request.graceId(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.resolution().name(),
                request.reason(),
                clock.millis()));
    }

    public Optional<CitizenshipCorrectionGrace> activeForPlayer(UUID playerId) {
        return Optional.ofNullable(database.activeCitizenshipCorrectionGraceByPlayer(playerId))
                .map(CitizenshipCorrectionGraceRegistry::toGrace);
    }

    public Optional<CitizenshipCorrectionGrace> activeForCitizenship(UUID citizenshipId) {
        return Optional.ofNullable(database.activeCitizenshipCorrectionGraceByCitizenship(citizenshipId))
                .map(CitizenshipCorrectionGraceRegistry::toGrace);
    }

    public List<CitizenshipCorrectionGrace> activeForNation(NationId nationId) {
        if (nationId == null) {
            throw new IllegalArgumentException("Nation identity cannot be null");
        }
        return database.activeCitizenshipCorrectionGracesByNation(nationId.value()).stream()
                .map(CitizenshipCorrectionGraceRegistry::toGrace)
                .toList();
    }

    public List<CitizenshipCorrectionGrace> history(UUID playerId) {
        return database.citizenshipCorrectionGraceHistory(playerId).stream()
                .map(CitizenshipCorrectionGraceRegistry::toGrace)
                .toList();
    }

    private static void requireStartPayload(
            StoredCitizenshipCorrectionGrace stored,
            StartCitizenshipCorrectionGrace request) {
        if (!sameIdentity(stored, request)
                || stored.deadlineEpochMillis() != request.deadline().toEpochMilli()
                || !stored.reason().equals(request.reason())) {
            throw new IdempotencyConflictException(request.serviceIdentity(), request.requestId());
        }
    }

    private static boolean sameIdentity(
            StoredCitizenshipCorrectionGrace stored,
            StartCitizenshipCorrectionGrace request) {
        return stored.citizenshipId().equals(request.citizenshipId())
                && stored.playerId().equals(request.playerId())
                && stored.nationId().equals(request.nationId().value())
                && stored.ftbTeamId().equals(request.ftbTeamId());
    }

    private static CitizenshipCorrectionGrace toGrace(StoredCitizenshipCorrectionGrace stored) {
        return new CitizenshipCorrectionGrace(
                stored.graceId(),
                stored.citizenshipId(),
                stored.playerId(),
                new NationId(stored.nationId()),
                stored.ftbTeamId(),
                stored.reason(),
                Instant.ofEpochMilli(stored.startedAtEpochMillis()),
                Instant.ofEpochMilli(stored.deadlineEpochMillis()),
                Optional.ofNullable(stored.resolution())
                        .map(CitizenshipCorrectionResolution::valueOf),
                Optional.ofNullable(stored.resolutionReason()),
                Optional.ofNullable(stored.resolvedAtEpochMillis())
                        .map(Instant::ofEpochMilli));
    }
}
