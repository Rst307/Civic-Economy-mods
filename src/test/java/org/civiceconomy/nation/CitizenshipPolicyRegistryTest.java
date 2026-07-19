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
import org.civiceconomy.fiscal.IdempotencyConflictException;
import org.civiceconomy.persistence.CivicDatabase;
import org.civiceconomy.persistence.DatabaseIdentity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CitizenshipPolicyRegistryTest {
    private static final Instant NOW = Instant.parse("2026-07-19T08:00:00Z");
    private static final Instant EFFECTIVE_AT = Instant.parse("2026-07-26T00:00:00Z");
    private static final ServiceIdentity SERVICE =
            new ServiceIdentity("civiceconomy-citizenship-policy");

    @TempDir Path temporaryDirectory;

    @Test
    void scheduledPolicyChangesOnlyAtEffectiveTimeAndSurvivesRestart() {
        ScheduleCitizenshipPolicy request = new ScheduleCitizenshipPolicy(
                SERVICE,
                "initial-citizenship-policy",
                "civic-admin-console:test",
                new CitizenshipPolicy(Duration.ofDays(2), Duration.ofDays(7)),
                EFFECTIVE_AT,
                "Initial Citizenship policy");
        CitizenshipPolicyVersion scheduled;

        try (CivicDatabase database = database()) {
            CitizenshipPolicyRegistry policies = registry(database);

            scheduled = policies.schedule(request);

            assertEquals(scheduled, policies.schedule(request));
            assertTrue(policies.current(EFFECTIVE_AT.minusMillis(1L)).isEmpty());
            assertEquals(Duration.ofDays(2),
                    policies.current(EFFECTIVE_AT).orElseThrow().policy().correctionGrace());
            assertEquals(Duration.ofDays(7),
                    policies.current(EFFECTIVE_AT).orElseThrow().policy().transferCooldown());
        }

        try (CivicDatabase reopened = database()) {
            assertEquals(scheduled, registry(reopened).current(EFFECTIVE_AT).orElseThrow());
        }
    }

    @Test
    void changedRequestReplayCannotRewritePolicyHistory() {
        try (CivicDatabase database = database()) {
            CitizenshipPolicyRegistry policies = registry(database);
            ScheduleCitizenshipPolicy original = new ScheduleCitizenshipPolicy(
                    SERVICE,
                    "immutable-citizenship-policy",
                    "civic-admin-console:test",
                    new CitizenshipPolicy(Duration.ofDays(2), Duration.ofDays(7)),
                    EFFECTIVE_AT,
                    "Original policy");
            policies.schedule(original);

            assertThrows(
                    IdempotencyConflictException.class,
                    () -> policies.schedule(new ScheduleCitizenshipPolicy(
                            SERVICE,
                            original.requestId(),
                            original.actorIdentity(),
                            new CitizenshipPolicy(Duration.ofDays(3), Duration.ofDays(7)),
                            original.effectiveAt(),
                            original.reason())));
        }
    }

    private CitizenshipPolicyRegistry registry(CivicDatabase database) {
        return new CitizenshipPolicyRegistry(database, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private CivicDatabase database() {
        return CivicDatabase.open(
                temporaryDirectory.resolve("citizenship-policy.sqlite3"),
                new DatabaseIdentity(
                        UUID.fromString("294ec908-a869-4d31-a45e-7469966a61fc"),
                        "0.1.0-probe",
                        "1.21-2.3.0.5",
                        "2101.1.10",
                        "2101.1.20"));
    }
}
