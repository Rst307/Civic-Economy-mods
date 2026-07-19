package org.civiceconomy.nation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;

public final class OnlineSessionAccumulator {
    private final ServiceIdentity serviceIdentity;
    private final Map<UUID, Session> sessions = new LinkedHashMap<>();

    public OnlineSessionAccumulator(ServiceIdentity serviceIdentity) {
        if (serviceIdentity == null) {
            throw new IllegalArgumentException("Online session service identity cannot be null");
        }
        this.serviceIdentity = serviceIdentity;
    }

    public synchronized void login(UUID playerId, long observedAtEpochMillis) {
        requireObservation(playerId, observedAtEpochMillis);
        sessions.putIfAbsent(playerId, new Session(UUID.randomUUID(), observedAtEpochMillis, 0L));
    }

    public synchronized List<RecordOnlineTime> checkpoint(long observedAtEpochMillis) {
        if (observedAtEpochMillis < 0) {
            throw new IllegalArgumentException("Online session observation time cannot be negative");
        }
        List<RecordOnlineTime> completed = new ArrayList<>();
        for (Map.Entry<UUID, Session> entry : sessions.entrySet()) {
            RecordOnlineTime interval = complete(entry.getKey(), entry.getValue(), observedAtEpochMillis);
            if (interval != null) {
                completed.add(interval);
                entry.setValue(entry.getValue().advance(observedAtEpochMillis));
            }
        }
        return List.copyOf(completed);
    }

    public synchronized List<RecordOnlineTime> logout(UUID playerId, long observedAtEpochMillis) {
        requireObservation(playerId, observedAtEpochMillis);
        Session session = sessions.remove(playerId);
        if (session == null) {
            return List.of();
        }
        RecordOnlineTime interval = complete(playerId, session, observedAtEpochMillis);
        return interval == null ? List.of() : List.of(interval);
    }

    public synchronized Set<UUID> activePlayers() {
        return Set.copyOf(sessions.keySet());
    }

    private RecordOnlineTime complete(UUID playerId, Session session, long observedAtEpochMillis) {
        if (observedAtEpochMillis <= session.lastObservedAtEpochMillis()) {
            return null;
        }
        return new RecordOnlineTime(
                serviceIdentity,
                "online-session:" + session.sessionId() + ":" + session.nextSequence(),
                playerId,
                session.lastObservedAtEpochMillis(),
                observedAtEpochMillis);
    }

    private static void requireObservation(UUID playerId, long observedAtEpochMillis) {
        if (playerId == null) {
            throw new IllegalArgumentException("Online session player cannot be null");
        }
        if (observedAtEpochMillis < 0) {
            throw new IllegalArgumentException("Online session observation time cannot be negative");
        }
    }

    private record Session(UUID sessionId, long lastObservedAtEpochMillis, long nextSequence) {
        Session advance(long observedAtEpochMillis) {
            return new Session(sessionId, observedAtEpochMillis, Math.incrementExact(nextSequence));
        }
    }
}
