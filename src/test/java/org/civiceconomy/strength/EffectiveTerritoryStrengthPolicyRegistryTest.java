package org.civiceconomy.strength;

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

class EffectiveTerritoryStrengthPolicyRegistryTest {
    private static final Instant NOW = Instant.parse("2026-07-18T15:00:00Z");
    private static final Instant EFFECTIVE_AT = Instant.parse("2026-07-25T00:00:00Z");
    private static final ServiceIdentity SERVICE =
            new ServiceIdentity("civiceconomy-effective-territory-strength-policy");

    @TempDir Path temporaryDirectory;

    @Test
    void scheduledPolicyChangesOnlyAtEffectiveTimeAndSurvivesRestart() {
        ScheduleEffectiveTerritoryStrengthPolicy request =
                new ScheduleEffectiveTerritoryStrengthPolicy(
                        SERVICE,
                        "initial-effective-territory-strength-policy",
                        "civic-admin-console:test",
                        new EffectiveTerritoryStrengthPolicy(4),
                        EFFECTIVE_AT,
                        "Initial Effective Territory Strength policy");
        EffectiveTerritoryStrengthPolicyVersion scheduled;

        try (CivicDatabase database = database()) {
            EffectiveTerritoryStrengthPolicyRegistry policies = registry(database);

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
        ScheduleEffectiveTerritoryStrengthPolicy original =
                new ScheduleEffectiveTerritoryStrengthPolicy(
                        SERVICE,
                        "immutable-effective-territory-strength-policy",
                        "civic-admin-console:test",
                        new EffectiveTerritoryStrengthPolicy(4),
                        EFFECTIVE_AT,
                        "Initial Effective Territory Strength policy");
        try (CivicDatabase database = database()) {
            EffectiveTerritoryStrengthPolicyRegistry policies = registry(database);
            EffectiveTerritoryStrengthPolicyVersion scheduled = policies.schedule(original);

            assertThrows(
                    IdempotencyConflictException.class,
                    () -> policies.schedule(new ScheduleEffectiveTerritoryStrengthPolicy(
                            SERVICE,
                            original.requestId(),
                            original.actorIdentity(),
                            new EffectiveTerritoryStrengthPolicy(16),
                            original.effectiveAt(),
                            original.reason())));
            assertEquals(scheduled, policies.current(EFFECTIVE_AT).orElseThrow());
        }
    }

    private EffectiveTerritoryStrengthPolicyRegistry registry(CivicDatabase database) {
        return new EffectiveTerritoryStrengthPolicyRegistry(
                database, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("effective-territory-strength-policy.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("15e99d18-e147-4105-9c4a-52fe64866268"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
