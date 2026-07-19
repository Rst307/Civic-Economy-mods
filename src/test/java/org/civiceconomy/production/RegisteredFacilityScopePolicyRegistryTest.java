package org.civiceconomy.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RegisteredFacilityScopePolicyRegistryTest {
    private static final Instant NOW = Instant.parse("2026-07-19T07:00:00Z");
    private static final Instant EFFECTIVE_AT = Instant.parse("2026-07-28T00:00:00Z");
    private static final ServiceIdentity SERVICE =
            new ServiceIdentity("civiceconomy-registered-facility-scope-policy");

    @TempDir Path temporaryDirectory;

    @Test
    void scheduledPolicyChangesOnlyAtEffectiveTimeAndSurvivesRestart() {
        ScheduleRegisteredFacilityScopePolicy request =
                new ScheduleRegisteredFacilityScopePolicy(
                        SERVICE,
                        "initial-registered-facility-scope-policy",
                        "civic-admin-console:test",
                        new RegisteredFacilityScopePolicy(16),
                        EFFECTIVE_AT,
                        "Initial Registered Facility Scope policy");
        RegisteredFacilityScopePolicyVersion scheduled;

        try (CivicDatabase database = database()) {
            RegisteredFacilityScopePolicyRegistry policies = registry(database);

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
            RegisteredFacilityScopePolicyRegistry policies = registry(database);
            ScheduleRegisteredFacilityScopePolicy original =
                    new ScheduleRegisteredFacilityScopePolicy(
                            SERVICE,
                            "immutable-registered-facility-scope-policy",
                            "civic-admin-console:test",
                            new RegisteredFacilityScopePolicy(16),
                            EFFECTIVE_AT,
                            "Original policy");
            policies.schedule(original);

            assertThrows(
                    IdempotencyConflictException.class,
                    () -> policies.schedule(new ScheduleRegisteredFacilityScopePolicy(
                            SERVICE,
                            original.requestId(),
                            original.actorIdentity(),
                            new RegisteredFacilityScopePolicy(32),
                            original.effectiveAt(),
                            original.reason())));
        }
    }

    private RegisteredFacilityScopePolicyRegistry registry(CivicDatabase database) {
        return new RegisteredFacilityScopePolicyRegistry(
                database, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("registered-facility-scope-policy.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("c37d29b3-d454-4bf1-863a-a57067e9cbd8"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
