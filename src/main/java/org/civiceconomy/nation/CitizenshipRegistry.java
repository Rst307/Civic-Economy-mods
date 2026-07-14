package org.civiceconomy.nation;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredCitizenship;

public final class CitizenshipRegistry {
    private final CivicDatabase database;
    private final Duration transferCooldown;
    private final Clock clock;

    public CitizenshipRegistry(CivicDatabase database, Duration transferCooldown, Clock clock) {
        if (database == null || transferCooldown == null || clock == null) {
            throw new IllegalArgumentException("Citizenship registry dependencies cannot be null");
        }
        if (transferCooldown.isNegative()) {
            throw new IllegalArgumentException("Citizenship transfer cooldown cannot be negative");
        }
        this.database = database;
        this.transferCooldown = transferCooldown;
        this.clock = clock;
    }

    public Citizenship join(JoinCitizenship request) {
        StoredCitizenship replay = database.citizenshipJoin(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            if (!replay.playerId().equals(request.playerId())
                    || !replay.nationId().equals(request.nationId().value())) {
                throw new IdempotencyConflictException(request.serviceIdentity(), request.requestId());
            }
            return toCitizenship(replay);
        }
        if (database.nation(request.nationId().value()) == null) {
            throw new UnknownNationException(request.nationId());
        }
        Citizenship active = current(request.playerId()).orElse(null);
        if (active != null) {
            throw new ActiveCitizenshipException(request.playerId(), active.nationId());
        }
        List<Citizenship> history = history(request.playerId());
        if (!history.isEmpty()) {
            Citizenship previous = history.getLast();
            long endedAt = previous.endedAtEpochMillis().orElseThrow();
            long eligibleAt = Math.addExact(endedAt, transferCooldown.toMillis());
            if (clock.millis() < eligibleAt) {
                throw new CitizenshipTransferCooldownException(request.playerId(), eligibleAt);
            }
        }
        StoredCitizenship stored = database.joinCitizenship(
                UUID.randomUUID(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.playerId(),
                request.nationId().value(),
                clock.millis());
        return toCitizenship(stored);
    }

    public Optional<Citizenship> current(UUID playerId) {
        return Optional.ofNullable(database.currentCitizenship(playerId)).map(CitizenshipRegistry::toCitizenship);
    }

    public List<Citizenship> currentForNation(NationId nationId) {
        if (nationId == null) {
            throw new IllegalArgumentException("Nation identity cannot be null");
        }
        return database.currentCitizenships(nationId.value()).stream()
                .map(CitizenshipRegistry::toCitizenship)
                .toList();
    }

    public Citizenship leave(LeaveCitizenship request) {
        StoredCitizenship replay = database.citizenshipLeave(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            if (!replay.playerId().equals(request.playerId())
                    || !replay.nationId().equals(request.nationId().value())) {
                throw new IdempotencyConflictException(request.serviceIdentity(), request.requestId());
            }
            return toCitizenship(replay);
        }
        Citizenship active = current(request.playerId())
                .orElseThrow(() -> new NoActiveCitizenshipException(request.playerId()));
        if (!active.nationId().equals(request.nationId())) {
            throw new CitizenshipNationMismatchException(
                    request.playerId(), request.nationId(), active.nationId());
        }
        return toCitizenship(database.leaveCitizenship(
                active.citizenshipId(),
                request.serviceIdentity().value(),
                request.requestId(),
                clock.millis()));
    }

    public List<Citizenship> history(UUID playerId) {
        return database.citizenshipHistory(playerId).stream().map(CitizenshipRegistry::toCitizenship).toList();
    }

    private static Citizenship toCitizenship(StoredCitizenship stored) {
        OptionalLong endedAt = stored.endedAtEpochMillis() == null
                ? OptionalLong.empty()
                : OptionalLong.of(stored.endedAtEpochMillis());
        return new Citizenship(
                stored.citizenshipId(),
                stored.playerId(),
                new NationId(stored.nationId()),
                stored.joinedAtEpochMillis(),
                endedAt);
    }
}
