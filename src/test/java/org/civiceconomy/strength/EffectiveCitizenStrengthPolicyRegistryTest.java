package org.civiceconomy.strength;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.fiscal.ServiceIdentity;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class EffectiveCitizenStrengthPolicyRegistryTest {
    private static final Instant NOW = Instant.parse("2026-07-18T14:00:00Z");
    private static final Instant EFFECTIVE_AT = Instant.parse("2026-07-25T00:00:00Z");
    private static final ServiceIdentity SERVICE =
            new ServiceIdentity("civiceconomy-effective-citizen-strength-policy");

    @TempDir Path temporaryDirectory;

    @Test
    void scheduledPolicyChangesOnlyAtEffectiveTimeAndSurvivesRestart() {
        ScheduleEffectiveCitizenStrengthPolicy request =
                new ScheduleEffectiveCitizenStrengthPolicy(
                        SERVICE,
                        "initial-effective-citizen-strength-policy",
                        "civic-admin-console:test",
                        new EffectiveCitizenStrengthPolicy(10),
                        EFFECTIVE_AT,
                        "Initial Effective Citizen Strength policy");
        EffectiveCitizenStrengthPolicyVersion scheduled;

        try (CivicDatabase database = database()) {
            EffectiveCitizenStrengthPolicyRegistry policies = registry(database);

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
        ScheduleEffectiveCitizenStrengthPolicy original =
                new ScheduleEffectiveCitizenStrengthPolicy(
                        SERVICE,
                        "immutable-effective-citizen-strength-policy",
                        "civic-admin-console:test",
                        new EffectiveCitizenStrengthPolicy(10),
                        EFFECTIVE_AT,
                        "Initial Effective Citizen Strength policy");
        try (CivicDatabase database = database()) {
            EffectiveCitizenStrengthPolicyRegistry policies = registry(database);
            EffectiveCitizenStrengthPolicyVersion scheduled = policies.schedule(original);

            assertThrows(
                    IdempotencyConflictException.class,
                    () -> policies.schedule(new ScheduleEffectiveCitizenStrengthPolicy(
                            SERVICE,
                            original.requestId(),
                            original.actorIdentity(),
                            new EffectiveCitizenStrengthPolicy(20),
                            original.effectiveAt(),
                            original.reason())));
            assertEquals(scheduled, policies.current(EFFECTIVE_AT).orElseThrow());
        }
    }

    private EffectiveCitizenStrengthPolicyRegistry registry(CivicDatabase database) {
        return new EffectiveCitizenStrengthPolicyRegistry(
                database, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("effective-citizen-strength-policy.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("b92e342a-1b64-42e0-bc3f-5b9fac2ad2d7"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
