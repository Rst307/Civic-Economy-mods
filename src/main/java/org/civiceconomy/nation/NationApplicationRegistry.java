package org.civiceconomy.nation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredNationApplication;
import org.civiceconomy.persistence.StoredNationApplicationTransition;
import org.civiceconomy.persistence.StoredNation;

public final class NationApplicationRegistry {
    private final CivicDatabase database;
    private final NationTeamDirectory teams;
    private final Clock clock;

    public NationApplicationRegistry(
            CivicDatabase database, NationTeamDirectory teams, Clock clock) {
        if (database == null || teams == null || clock == null) {
            throw new IllegalArgumentException("Nation Application dependencies cannot be null");
        }
        this.database = database;
        this.teams = teams;
        this.clock = clock;
    }

    public NationApplication create(CreateNationApplication request) {
        StoredNationApplication replay = database.nationApplicationRegistration(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            if (!replay.ftbTeamId().equals(request.ftbTeamId())
                    || !replay.applicantPlayerId().equals(request.applicantPlayerId())
                    || replay.expiresAtEpochMillis() != request.expiresAt().toEpochMilli()) {
                throw new IdempotencyConflictException(
                        request.serviceIdentity(), request.requestId());
            }
            return toApplication(replay);
        }
        NationTeam team = teams.find(request.ftbTeamId())
                .orElseThrow(() -> new UnknownFtbTeamException(request.ftbTeamId()));
        if (!team.headId().equals(request.applicantPlayerId())) {
            throw new NationApplicationHeadRequiredException(
                    request.ftbTeamId(), request.applicantPlayerId());
        }
        StoredNation bound = database.nationByFtbTeam(request.ftbTeamId());
        if (bound != null) {
            throw new FtbTeamAlreadyBoundException(
                    request.ftbTeamId(), new NationId(bound.nationId()));
        }
        StoredNationApplication pending =
                database.pendingNationApplicationByFtbTeam(request.ftbTeamId());
        if (pending != null) {
            throw new NationApplicationAlreadyPendingException(
                    request.ftbTeamId(), new NationApplicationId(pending.applicationId()));
        }
        if (!request.expiresAt().isAfter(clock.instant())) {
            throw new IllegalArgumentException("Nation Application expiry must be in the future");
        }
        return toApplication(database.createNationApplication(
                NationApplicationId.create().value(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.ftbTeamId(),
                request.applicantPlayerId(),
                clock.millis(),
                request.expiresAt().toEpochMilli(),
                candidates(team)));
    }

    public Optional<NationApplication> find(NationApplicationId applicationId) {
        return Optional.ofNullable(database.nationApplication(applicationId.value()))
                .map(NationApplicationRegistry::toApplication);
    }

    public Optional<NationApplication> findPendingByFtbTeam(UUID ftbTeamId) {
        return Optional.ofNullable(database.pendingNationApplicationByFtbTeam(ftbTeamId))
                .map(NationApplicationRegistry::toApplication);
    }

    public List<NationApplication> pendingExpiringAtOrBefore(Instant deadline) {
        if (deadline == null) {
            throw new IllegalArgumentException("Nation Application expiry deadline cannot be null");
        }
        return database.pendingNationApplicationsExpiringAtOrBefore(deadline.toEpochMilli()).stream()
                .map(NationApplicationRegistry::toApplication)
                .toList();
    }

    public java.util.List<NationApplicationCandidate> candidates(
            NationApplicationId applicationId) {
        return database.nationApplicationCandidates(applicationId.value()).stream()
                .map(stored -> new NationApplicationCandidate(
                        new NationApplicationId(stored.applicationId()),
                        stored.playerId(),
                        Instant.ofEpochMilli(stored.affiliatedAtEpochMillis()),
                        Optional.ofNullable(stored.endedAtEpochMillis())
                                .map(Instant::ofEpochMilli)))
                .toList();
    }

    public List<NationApplicationEvidence> claimCandidateEvidence(
            NationApplicationId applicationId, Duration observationWindow) {
        if (applicationId == null || observationWindow == null
                || observationWindow.isZero() || observationWindow.isNegative()) {
            throw new IllegalArgumentException(
                    "Nation Application evidence observation window must be positive");
        }
        long observedUntil = clock.millis();
        long windowMillis = observationWindow.toMillis();
        long windowStart = observedUntil >= windowMillis ? observedUntil - windowMillis : 0;
        return database.claimNationApplicationEvidence(
                        applicationId.value(), windowStart, observedUntil, observedUntil)
                .stream()
                .map(stored -> new NationApplicationEvidence(
                        new NationApplicationId(stored.applicationId()),
                        stored.playerId(),
                        stored.attributedMillis()))
                .toList();
    }

    public List<NationApplicationEvidence> candidateEvidence(
            NationApplicationId applicationId) {
        if (applicationId == null) {
            throw new IllegalArgumentException("Nation Application identity cannot be null");
        }
        return database.nationApplicationEvidence(applicationId.value()).stream()
                .map(stored -> new NationApplicationEvidence(
                        new NationApplicationId(stored.applicationId()),
                        stored.playerId(),
                        stored.attributedMillis()))
                .toList();
    }

    public NationApplication cancel(CancelNationApplication request) {
        StoredNationApplicationTransition replay = database.nationApplicationTransitionRegistration(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            long observationWindowMillis = request.observationWindow().toMillis();
            if (!replay.applicationId().equals(request.applicationId().value())
                    || !replay.actorPlayerId().equals(request.applicantPlayerId())
                    || !Long.valueOf(observationWindowMillis)
                            .equals(replay.observationWindowMillis())
                    || !"CANCELLED".equals(replay.toState())
                    || !replay.reason().equals(request.reason())) {
                throw new IdempotencyConflictException(
                        request.serviceIdentity(), request.requestId());
            }
            return find(request.applicationId()).orElseThrow();
        }
        NationApplication application = find(request.applicationId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown Nation Application " + request.applicationId()));
        NationTeam team = teams.find(application.ftbTeamId())
                .orElseThrow(() -> new UnknownFtbTeamException(application.ftbTeamId()));
        if (!team.headId().equals(request.applicantPlayerId())) {
            throw new NationApplicationHeadRequiredException(
                    application.ftbTeamId(), request.applicantPlayerId());
        }
        claimCandidateEvidence(request.applicationId(), request.observationWindow());
        database.transitionNationApplication(
                UUID.randomUUID(),
                request.applicationId().value(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.applicantPlayerId(),
                request.observationWindow().toMillis(),
                "CANCELLED",
                request.reason(),
                clock.millis(),
                clock.millis());
        return find(request.applicationId()).orElseThrow();
    }

    public NationApplication expire(ExpireNationApplication request) {
        StoredNationApplicationTransition replay = database.nationApplicationTransitionRegistration(
                request.serviceIdentity().value(), request.requestId());
        long observationWindowMillis = request.observationWindow().toMillis();
        if (replay != null) {
            if (!replay.applicationId().equals(request.applicationId().value())
                    || replay.actorPlayerId() != null
                    || !Long.valueOf(observationWindowMillis)
                            .equals(replay.observationWindowMillis())
                    || !"EXPIRED".equals(replay.toState())
                    || !replay.reason().equals(request.reason())) {
                throw new IdempotencyConflictException(
                        request.serviceIdentity(), request.requestId());
            }
            return find(request.applicationId()).orElseThrow();
        }
        NationApplication application = find(request.applicationId())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown Nation Application " + request.applicationId()));
        if (clock.instant().isBefore(application.expiresAt())) {
            throw new IllegalStateException(
                    "Nation Application " + request.applicationId() + " has not expired");
        }
        claimCandidateEvidence(request.applicationId(), request.observationWindow());
        database.transitionNationApplication(
                UUID.randomUUID(),
                request.applicationId().value(),
                request.serviceIdentity().value(),
                request.requestId(),
                null,
                observationWindowMillis,
                "EXPIRED",
                request.reason(),
                application.expiresAt().toEpochMilli(),
                clock.millis());
        return find(request.applicationId()).orElseThrow();
    }

    private static Set<UUID> candidates(NationTeam team) {
        Set<UUID> candidates = new HashSet<>(team.citizens());
        candidates.add(team.headId());
        return Set.copyOf(candidates);
    }

    private static NationApplication toApplication(StoredNationApplication stored) {
        return new NationApplication(
                new NationApplicationId(stored.applicationId()),
                stored.ftbTeamId(),
                stored.applicantPlayerId(),
                Instant.ofEpochMilli(stored.createdAtEpochMillis()),
                Instant.ofEpochMilli(stored.expiresAtEpochMillis()),
                NationApplicationState.valueOf(stored.state()));
    }
}
