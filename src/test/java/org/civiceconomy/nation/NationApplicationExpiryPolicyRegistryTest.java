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
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class NationApplicationExpiryPolicyRegistryTest {
    private static final Instant NOW = Instant.parse("2026-07-19T10:00:00Z");
    private static final Instant EFFECTIVE_AT = Instant.parse("2026-07-26T00:00:00Z");
    private static final ServiceIdentity SERVICE =
            new ServiceIdentity("civiceconomy-nation-application-expiry-policy");

    @TempDir Path temporaryDirectory;

    @Test
    void scheduledScanIntervalChangesOnlyAtEffectiveTimeAndSurvivesRestart() {
        ScheduleNationApplicationExpiryPolicy request =
                new ScheduleNationApplicationExpiryPolicy(
                        SERVICE,
                        "initial-nation-application-expiry-policy",
                        "civic-admin-console:test",
                        new NationApplicationExpiryPolicy(Duration.ofMinutes(4)),
                        EFFECTIVE_AT,
                        "Initial Nation Application Expiry policy");
        NationApplicationExpiryPolicyVersion scheduled;

        try (CivicDatabase database = database()) {
            NationApplicationExpiryPolicyRegistry policies = registry(database);
            scheduled = policies.schedule(request);

            assertEquals(scheduled, policies.schedule(request));
            assertTrue(policies.current(EFFECTIVE_AT.minusMillis(1L)).isEmpty());
            assertEquals(
                    Duration.ofMinutes(4),
                    policies.current(EFFECTIVE_AT).orElseThrow().policy().scanInterval());
        }

        try (CivicDatabase reopened = database()) {
            assertEquals(scheduled, registry(reopened).current(EFFECTIVE_AT).orElseThrow());
        }
    }

    @Test
    void changedRequestReplayCannotRewriteExpiryPolicyHistory() {
        try (CivicDatabase database = database()) {
            NationApplicationExpiryPolicyRegistry policies = registry(database);
            ScheduleNationApplicationExpiryPolicy original =
                    new ScheduleNationApplicationExpiryPolicy(
                            SERVICE, "immutable-expiry-policy", "civic-admin-console:test",
                            new NationApplicationExpiryPolicy(Duration.ofMinutes(4)),
                            EFFECTIVE_AT, "Original expiry policy");
            policies.schedule(original);
            assertThrows(org.civiceconomy.fiscal.IdempotencyConflictException.class,
                    () -> policies.schedule(new ScheduleNationApplicationExpiryPolicy(
                            SERVICE, original.requestId(), original.actorIdentity(),
                            new NationApplicationExpiryPolicy(Duration.ofMinutes(5)),
                            original.effectiveAt(), original.reason())));
        }
    }

    private NationApplicationExpiryPolicyRegistry registry(CivicDatabase database) {
        return new NationApplicationExpiryPolicyRegistry(
                database, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("nation-application-expiry-policy.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("f93f676c-d407-4ab0-b295-df87ee6cb09d"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
