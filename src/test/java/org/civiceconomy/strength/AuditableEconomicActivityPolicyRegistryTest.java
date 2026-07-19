package org.civiceconomy.strength;

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

class AuditableEconomicActivityPolicyRegistryTest {
    private static final Instant NOW = Instant.parse("2026-07-19T06:00:00Z");
    private static final Instant EFFECTIVE_AT = Instant.parse("2026-07-27T00:00:00Z");
    private static final ServiceIdentity SERVICE =
            new ServiceIdentity("civiceconomy-auditable-economic-activity-policy");

    @TempDir Path temporaryDirectory;

    @Test
    void scheduledPolicyChangesOnlyAtEffectiveTimeAndSurvivesRestart() {
        ScheduleAuditableEconomicActivityPolicy request =
                new ScheduleAuditableEconomicActivityPolicy(
                        SERVICE,
                        "initial-auditable-economic-activity-policy",
                        "civic-admin-console:test",
                        new AuditableEconomicActivityPolicy(Duration.ofDays(30), 10_000L),
                        EFFECTIVE_AT,
                        "Initial Auditable Economic Activity policy");
        AuditableEconomicActivityPolicyVersion scheduled;

        try (CivicDatabase database = database()) {
            AuditableEconomicActivityPolicyRegistry policies = registry(database);

            scheduled = policies.schedule(request);

            assertEquals(scheduled, policies.schedule(request));
            assertTrue(policies.current(EFFECTIVE_AT.minusMillis(1L)).isEmpty());
            assertEquals(scheduled, policies.current(EFFECTIVE_AT).orElseThrow());
        }

        try (CivicDatabase reopened = database()) {
            assertEquals(scheduled, registry(reopened).current(EFFECTIVE_AT).orElseThrow());
        }
    }

    @Test
    void changedRequestReplayCannotRewritePolicyHistory() {
        try (CivicDatabase database = database()) {
            AuditableEconomicActivityPolicyRegistry policies = registry(database);
            ScheduleAuditableEconomicActivityPolicy original =
                    new ScheduleAuditableEconomicActivityPolicy(
                            SERVICE,
                            "immutable-auditable-economic-activity-policy",
                            "civic-admin-console:test",
                            new AuditableEconomicActivityPolicy(Duration.ofDays(30), 10_000L),
                            EFFECTIVE_AT,
                            "Original policy");
            policies.schedule(original);

            assertThrows(
                    IdempotencyConflictException.class,
                    () -> policies.schedule(new ScheduleAuditableEconomicActivityPolicy(
                            SERVICE,
                            original.requestId(),
                            original.actorIdentity(),
                            new AuditableEconomicActivityPolicy(Duration.ofDays(7), 20_000L),
                            original.effectiveAt(),
                            original.reason())));
        }
    }

    private AuditableEconomicActivityPolicyRegistry registry(CivicDatabase database) {
        return new AuditableEconomicActivityPolicyRegistry(
                database, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("auditable-economic-activity-policy.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("b750acb4-5c7e-4b78-a902-47fc7d41bf9b"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
