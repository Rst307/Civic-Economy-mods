package org.civiceconomy.nation;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OnlineTimeObservationPolicyRegistryTest {
    private static final Instant NOW = Instant.parse("2026-07-19T09:00:00Z");
    private static final Instant EFFECTIVE_AT = Instant.parse("2026-07-26T00:00:00Z");
    private static final ServiceIdentity SERVICE =
            new ServiceIdentity("civiceconomy-online-time-observation-policy");

    @TempDir Path temporaryDirectory;

    @Test
    void scheduledCheckpointIntervalChangesOnlyAtEffectiveTimeAndSurvivesRestart() {
        ScheduleOnlineTimeObservationPolicy request =
                new ScheduleOnlineTimeObservationPolicy(
                        SERVICE,
                        "initial-online-time-observation-policy",
                        "civic-admin-console:test",
                        new OnlineTimeObservationPolicy(Duration.ofMinutes(3)),
                        EFFECTIVE_AT,
                        "Initial Online Time Observation policy");
        OnlineTimeObservationPolicyVersion scheduled;

        try (CivicDatabase database = database()) {
            OnlineTimeObservationPolicyRegistry policies = registry(database);

            scheduled = policies.schedule(request);

            assertEquals(scheduled, policies.schedule(request));
            assertTrue(policies.current(EFFECTIVE_AT.minusMillis(1L)).isEmpty());
            assertEquals(
                    Duration.ofMinutes(3),
                    policies.current(EFFECTIVE_AT).orElseThrow().policy().checkpointInterval());
        }

        try (CivicDatabase reopened = database()) {
            assertEquals(scheduled, registry(reopened).current(EFFECTIVE_AT).orElseThrow());
        }
    }

    @Test
    void changedRequestReplayCannotRewriteCheckpointHistory() {
        try (CivicDatabase database = database()) {
            OnlineTimeObservationPolicyRegistry policies = registry(database);
            ScheduleOnlineTimeObservationPolicy original =
                    new ScheduleOnlineTimeObservationPolicy(
                            SERVICE,
                            "immutable-online-time-observation-policy",
                            "civic-admin-console:test",
                            new OnlineTimeObservationPolicy(Duration.ofMinutes(3)),
                            EFFECTIVE_AT,
                            "Original Online Time Observation policy");
            policies.schedule(original);

            assertThrows(
                    IdempotencyConflictException.class,
                    () -> policies.schedule(new ScheduleOnlineTimeObservationPolicy(
                            SERVICE,
                            original.requestId(),
                            original.actorIdentity(),
                            new OnlineTimeObservationPolicy(Duration.ofMinutes(5)),
                            original.effectiveAt(),
                            original.reason())));
        }
    }

    private OnlineTimeObservationPolicyRegistry registry(CivicDatabase database) {
        return new OnlineTimeObservationPolicyRegistry(
                database, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("online-time-observation-policy.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("ebca8b10-e49d-4e18-923c-b161c95a70e4"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
