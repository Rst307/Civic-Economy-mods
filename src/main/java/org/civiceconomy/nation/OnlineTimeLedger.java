package org.civiceconomy.nation;

import java.util.List;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.StoredOnlineInterval;

public final class OnlineTimeLedger {
    private final CivicDatabase database;

    public OnlineTimeLedger(CivicDatabase database) {
        this.database = database;
    }

    public OnlineInterval record(RecordOnlineTime request) {
        StoredOnlineInterval replay = database.onlineTimeRegistration(
                request.serviceIdentity().value(), request.requestId());
        if (replay != null) {
            if (!replay.playerId().equals(request.playerId())
                    || replay.startedAtEpochMillis() != request.startedAtEpochMillis()
                    || replay.endedAtEpochMillis() != request.endedAtEpochMillis()) {
                throw new IdempotencyConflictException(request.serviceIdentity(), request.requestId());
            }
            return toOnlineInterval(replay);
        }
        StoredOnlineInterval overlapping = database.overlappingOnlineTime(
                request.playerId(), request.startedAtEpochMillis(), request.endedAtEpochMillis());
        if (overlapping != null) {
            throw new OverlappingOnlineIntervalException(
                    request.playerId(), toOnlineInterval(overlapping));
        }
        return toOnlineInterval(database.recordOnlineTime(
                UUID.randomUUID(),
                request.serviceIdentity().value(),
                request.requestId(),
                request.playerId(),
                request.startedAtEpochMillis(),
                request.endedAtEpochMillis()));
    }

    public List<OnlineInterval> history(UUID playerId) {
        return database.onlineTimeHistory(playerId).stream().map(OnlineTimeLedger::toOnlineInterval).toList();
    }

    public long observedMillis(UUID playerId, long windowStartEpochMillis, long windowEndEpochMillis) {
        if (windowStartEpochMillis < 0 || windowEndEpochMillis <= windowStartEpochMillis) {
            throw new IllegalArgumentException("Online-time window must have a positive duration");
        }
        long observed = 0;
        for (OnlineInterval interval : history(playerId)) {
            long clippedStart = Math.max(windowStartEpochMillis, interval.startedAtEpochMillis());
            long clippedEnd = Math.min(windowEndEpochMillis, interval.endedAtEpochMillis());
            if (clippedEnd > clippedStart) {
                observed = Math.addExact(observed, Math.subtractExact(clippedEnd, clippedStart));
            }
        }
        return observed;
    }

    private static OnlineInterval toOnlineInterval(StoredOnlineInterval stored) {
        return new OnlineInterval(
                stored.intervalId(),
                stored.playerId(),
                stored.startedAtEpochMillis(),
                stored.endedAtEpochMillis());
    }
}
