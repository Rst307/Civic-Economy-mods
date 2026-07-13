package org.civiceconomy.nation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OnlineTimeLedgerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void observedIntervalReplayPersistsExactlyOnceAcrossRestart() {
        UUID playerId = UUID.fromString("1d6765a2-95b1-46ae-a7d6-c4391c822775");
        RecordOnlineTime request = new RecordOnlineTime(
                new ServiceIdentity("civiceconomy-server"),
                "online-session-1-flush-1",
                playerId,
                Instant.parse("2026-07-14T01:00:00Z").toEpochMilli(),
                Instant.parse("2026-07-14T01:05:00Z").toEpochMilli());
        OnlineInterval recorded;

        try (CivicDatabase database = database()) {
            OnlineTimeLedger ledger = new OnlineTimeLedger(database);
            recorded = ledger.record(request);

            assertEquals(recorded, ledger.record(request));
            assertEquals(java.util.List.of(recorded), ledger.history(playerId));
        }

        try (CivicDatabase reopened = database()) {
            OnlineTimeLedger ledger = new OnlineTimeLedger(reopened);

            assertEquals(java.util.List.of(recorded), ledger.history(playerId));
        }
    }

    @Test
    void onlineTimeRequestCannotBeReusedForDifferentObservedTime() {
        UUID playerId = UUID.fromString("d877ae38-cbfe-47b6-91ba-6a921f01c609");
        ServiceIdentity identity = new ServiceIdentity("civiceconomy-server");

        try (CivicDatabase database = database()) {
            OnlineTimeLedger ledger = new OnlineTimeLedger(database);
            OnlineInterval recorded = ledger.record(new RecordOnlineTime(
                    identity,
                    "online-conflict",
                    playerId,
                    Instant.parse("2026-07-14T02:00:00Z").toEpochMilli(),
                    Instant.parse("2026-07-14T02:05:00Z").toEpochMilli()));

            assertThrows(
                    IdempotencyConflictException.class,
                    () -> ledger.record(new RecordOnlineTime(
                            identity,
                            "online-conflict",
                            playerId,
                            Instant.parse("2026-07-14T02:00:00Z").toEpochMilli(),
                            Instant.parse("2026-07-14T02:06:00Z").toEpochMilli())));
            assertEquals(java.util.List.of(recorded), ledger.history(playerId));
        }
    }

    @Test
    void overlappingObservedIntervalsCannotDoubleCountOnlineTime() {
        UUID playerId = UUID.fromString("21db80f2-7552-47a7-8a45-f94cc1838ee5");
        long firstStart = Instant.parse("2026-07-14T03:00:00Z").toEpochMilli();
        long firstEnd = Instant.parse("2026-07-14T03:05:00Z").toEpochMilli();

        try (CivicDatabase database = database()) {
            OnlineTimeLedger ledger = new OnlineTimeLedger(database);
            OnlineInterval recorded = ledger.record(new RecordOnlineTime(
                    new ServiceIdentity("civiceconomy-server"),
                    "online-overlap-first",
                    playerId,
                    firstStart,
                    firstEnd));

            assertThrows(
                    OverlappingOnlineIntervalException.class,
                    () -> ledger.record(new RecordOnlineTime(
                            new ServiceIdentity("civiceconomy-server"),
                            "online-overlap-second",
                            playerId,
                            Instant.parse("2026-07-14T03:04:00Z").toEpochMilli(),
                            Instant.parse("2026-07-14T03:06:00Z").toEpochMilli())));
            assertEquals(java.util.List.of(recorded), ledger.history(playerId));
        }
    }

    @Test
    void rollingWindowClipsAdjacentObservedIntervals() {
        UUID playerId = UUID.fromString("6cf2831b-5df2-4a08-8457-b4defd2c435c");
        long windowStart = Instant.parse("2026-07-14T04:00:00Z").toEpochMilli();
        long windowEnd = Instant.parse("2026-07-14T04:30:00Z").toEpochMilli();

        try (CivicDatabase database = database()) {
            OnlineTimeLedger ledger = new OnlineTimeLedger(database);
            record(ledger, "window-1", playerId, "2026-07-14T03:50:00Z", "2026-07-14T04:10:00Z");
            record(ledger, "window-2", playerId, "2026-07-14T04:10:00Z", "2026-07-14T04:20:00Z");
            record(ledger, "window-3", playerId, "2026-07-14T04:20:00Z", "2026-07-14T04:40:00Z");

            assertEquals(Duration.ofMinutes(30).toMillis(), ledger.observedMillis(playerId, windowStart, windowEnd));
        }
    }

    private static OnlineInterval record(
            OnlineTimeLedger ledger, String requestId, UUID playerId, String start, String end) {
        return ledger.record(new RecordOnlineTime(
                new ServiceIdentity("civiceconomy-server"),
                requestId,
                playerId,
                Instant.parse(start).toEpochMilli(),
                Instant.parse(end).toEpochMilli()));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("online-time.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("07cbf08e-f2c4-4d27-b401-65078fc5b198"),
                        "0.1.0",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
