package org.civiceconomy.production;

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

class ProductionStrengthPolicyRegistryTest {
    private static final Instant NOW = Instant.parse("2026-07-18T12:00:00Z");
    private static final Instant EFFECTIVE_AT = Instant.parse("2026-07-25T00:00:00Z");
    private static final ServiceIdentity SERVICE =
            new ServiceIdentity("civiceconomy-production-strength-policy");

    @TempDir Path temporaryDirectory;

    @Test
    void scheduledPolicyChangesOnlyAtEffectiveTimeAndSurvivesRestart() {
        ScheduleProductionStrengthPolicy request = new ScheduleProductionStrengthPolicy(
                SERVICE,
                "initial-production-strength-policy",
                "civic-admin-console:test",
                new ProductionStrengthPolicy(
                        Duration.ofDays(30), Duration.ofDays(7), 100_000L),
                EFFECTIVE_AT,
                "Initial rolling production strength policy");
        ProductionStrengthPolicyVersion scheduled;

        try (CivicDatabase database = database()) {
            ProductionStrengthPolicyRegistry policies = registry(database);

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
    void changedRequestReplayFailsClosed() {
        ScheduleProductionStrengthPolicy original = new ScheduleProductionStrengthPolicy(
                SERVICE,
                "immutable-production-strength-policy",
                "civic-admin-console:test",
                new ProductionStrengthPolicy(
                        Duration.ofDays(30), Duration.ofDays(7), 100_000L),
                EFFECTIVE_AT,
                "Initial rolling production strength policy");
        try (CivicDatabase database = database()) {
            ProductionStrengthPolicyRegistry policies = registry(database);
            ProductionStrengthPolicyVersion scheduled = policies.schedule(original);

            assertThrows(
                    IdempotencyConflictException.class,
                    () -> policies.schedule(new ScheduleProductionStrengthPolicy(
                            SERVICE,
                            original.requestId(),
                            original.actorIdentity(),
                            new ProductionStrengthPolicy(
                                    Duration.ofDays(30), Duration.ofDays(7), 200_000L),
                            original.effectiveAt(),
                            original.reason())));
            assertEquals(scheduled, policies.current(EFFECTIVE_AT).orElseThrow());
        }
    }

    private ProductionStrengthPolicyRegistry registry(CivicDatabase database) {
        return new ProductionStrengthPolicyRegistry(
                database, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("production-strength-policy.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("8b207f16-a2d6-46ae-b2ee-d1bd0adcfec7"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
