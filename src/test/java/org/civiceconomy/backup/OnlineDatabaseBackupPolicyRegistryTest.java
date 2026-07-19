package org.civiceconomy.backup;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OnlineDatabaseBackupPolicyRegistryTest {
    private static final Instant RECORDED_AT = Instant.parse("2026-07-19T13:00:00Z");
    private static final Instant EFFECTIVE_AT = Instant.parse("2026-07-20T00:00:00Z");
    private static final ServiceIdentity SERVICE =
            new ServiceIdentity("civiceconomy-online-database-backup-policy-test");

    @TempDir Path temporaryDirectory;

    @Test
    void scheduledPolicyBecomesEffectiveWithoutInventingAnEarlierDefault() {
        OnlineDatabaseBackupPolicyVersion scheduled;
        try (CivicDatabase database = database()) {
            OnlineDatabaseBackupPolicyRegistry policies = new OnlineDatabaseBackupPolicyRegistry(
                    database, Clock.fixed(RECORDED_AT, ZoneOffset.UTC));

            scheduled = policies.schedule(
                    new ScheduleOnlineDatabaseBackupPolicy(
                            SERVICE,
                            "backup-policy-2026-07",
                            "civic-admin-console:test",
                            new OnlineDatabaseBackupPolicy(Duration.ofHours(2), 12),
                            EFFECTIVE_AT,
                            "Retain twelve recovery points every two hours"));

            assertTrue(policies.current(EFFECTIVE_AT.minusMillis(1)).isEmpty());
        }
        try (CivicDatabase database = database()) {
            OnlineDatabaseBackupPolicyRegistry policies = new OnlineDatabaseBackupPolicyRegistry(
                    database, Clock.fixed(RECORDED_AT, ZoneOffset.UTC));

            assertEquals(scheduled, policies.current(EFFECTIVE_AT).orElseThrow());
            assertEquals(Duration.ofHours(2), scheduled.policy().interval());
            assertEquals(12, scheduled.policy().retention());
        }
    }

    @Test
    void exactReplayIsStableAndChangedRetentionConflicts() {
        ScheduleOnlineDatabaseBackupPolicy request = new ScheduleOnlineDatabaseBackupPolicy(
                SERVICE,
                "backup-policy-replay",
                "civic-admin-console:test",
                new OnlineDatabaseBackupPolicy(Duration.ofMinutes(90), 6),
                EFFECTIVE_AT,
                "Govern online backup rotation");
        try (CivicDatabase database = database()) {
            OnlineDatabaseBackupPolicyRegistry policies = new OnlineDatabaseBackupPolicyRegistry(
                    database, Clock.fixed(RECORDED_AT, ZoneOffset.UTC));

            OnlineDatabaseBackupPolicyVersion first = policies.schedule(request);
            assertEquals(first, policies.schedule(request));
            assertThrows(
                    IdempotencyConflictException.class,
                    () -> policies.schedule(new ScheduleOnlineDatabaseBackupPolicy(
                            SERVICE,
                            request.requestId(),
                            request.actorIdentity(),
                            new OnlineDatabaseBackupPolicy(Duration.ofMinutes(90), 7),
                            request.effectiveAt(),
                            request.reason())));
        }
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("online-database-backup-policy.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("7a609374-0d22-4b8b-a12e-19068513676e"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
